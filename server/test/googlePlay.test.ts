import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import {
  createSignedJwt,
  googlePlayProvider,
  interpretPurchase,
  resetTokenCache,
} from "../src/providers/googlePlay";
import { ProviderUnavailableError, type Env } from "../src/providers/types";
import { handleRequest } from "../src/index";

// A throwaway key generated per test run; no real credentials are involved.
let publicKey: CryptoKey;
let env: Env;

function toPem(der: ArrayBuffer): string {
  const base64 = Buffer.from(der).toString("base64").replace(/(.{64})/g, "$1\n");
  return `-----BEGIN PRIVATE KEY-----\n${base64}\n-----END PRIVATE KEY-----\n`;
}

function decodePart(part: string): unknown {
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
}

beforeAll(async () => {
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  publicKey = pair.publicKey;
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  env = {
    PACKAGE_NAME: "com.hexnext.themer",
    GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({
      client_email: "verifier@example.iam.gserviceaccount.com",
      private_key: toPem(pkcs8 as ArrayBuffer),
      token_uri: "https://oauth2.googleapis.com/token",
    }),
  };
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetTokenCache();
});

const request = {
  packageName: "com.hexnext.themer",
  productId: "pack_neon",
  purchaseToken: "tok/en",
};

describe("createSignedJwt", () => {
  it("produces a verifiable RS256 assertion with the expected claims", async () => {
    const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON!);
    const jwt = await createSignedJwt(account, "https://oauth2.googleapis.com/token", 1000);
    const [header, claims, signature] = jwt.split(".");
    expect(decodePart(header)).toEqual({ alg: "RS256", typ: "JWT" });
    expect(decodePart(claims)).toEqual({
      iss: "verifier@example.iam.gserviceaccount.com",
      scope: "https://www.googleapis.com/auth/androidpublisher",
      aud: "https://oauth2.googleapis.com/token",
      iat: 1000,
      exp: 4600,
    });
    const ok = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      publicKey,
      Buffer.from(signature, "base64url"),
      new TextEncoder().encode(`${header}.${claims}`),
    );
    expect(ok).toBe(true);
  });
});

describe("interpretPurchase", () => {
  it("accepts purchaseState 0", () => {
    expect(interpretPurchase({ purchaseState: 0, orderId: "GPA.9" }, "pack_neon")).toEqual({
      valid: true,
      productId: "pack_neon",
      orderId: "GPA.9",
    });
  });
  it.each([
    [{ purchaseState: 1 }, "purchase_cancelled"],
    [{ purchaseState: 2 }, "purchase_pending"],
    [{}, "not_purchased"],
    [{ purchaseState: 0, productId: "all_packs" }, "product_mismatch"],
  ])("rejects %j", (purchase, reason) => {
    expect(interpretPurchase(purchase, "pack_neon")).toEqual({ valid: false, reason });
  });
});

describe("googlePlayProvider.verify", () => {
  function stubFetch(purchaseResponse: () => Response) {
    const urls: string[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      if (url.startsWith("https://oauth2.googleapis.com/")) {
        return Response.json({ access_token: "at-1", expires_in: 3600 });
      }
      return purchaseResponse();
    });
    vi.stubGlobal("fetch", fetchMock);
    return urls;
  }

  it("verifies a purchase and caches the access token", async () => {
    const urls = stubFetch(() => Response.json({ purchaseState: 0, orderId: "GPA.1" }));
    expect(await googlePlayProvider.verify(request, env)).toEqual({
      valid: true,
      productId: "pack_neon",
      orderId: "GPA.1",
    });
    await googlePlayProvider.verify(request, env);
    expect(urls.filter((u) => u.includes("oauth2"))).toHaveLength(1);
    expect(urls[1]).toBe(
      "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
        "com.hexnext.themer/purchases/products/pack_neon/tokens/tok%2Fen",
    );
  });

  it("treats 404 as an unknown purchase", async () => {
    stubFetch(() => new Response("", { status: 404 }));
    expect(await googlePlayProvider.verify(request, env)).toEqual({
      valid: false,
      reason: "purchase_not_found",
    });
  });

  it("throws ProviderUnavailableError on a server error", async () => {
    stubFetch(() => new Response("", { status: 500 }));
    await expect(googlePlayProvider.verify(request, env)).rejects.toBeInstanceOf(
      ProviderUnavailableError,
    );
  });

  it("throws ProviderUnavailableError when the secret is missing", async () => {
    await expect(
      googlePlayProvider.verify(request, { PACKAGE_NAME: "com.hexnext.themer" }),
    ).rejects.toBeInstanceOf(ProviderUnavailableError);
  });

  it.each([
    "http://oauth2.googleapis.com/token",
    "https://attacker.invalid/token",
    "https://oauth2.googleapis.com.attacker.invalid/token",
    "https://oauth2.googleapis.com@attacker.invalid/token",
    "https://oauth2.googleapis.com/token?forward=attacker",
    "https://oauth2.googleapis.com/token#ignored",
    "",
    null,
    42,
  ])("rejects an unapproved OAuth destination before network access: %j", async (tokenUri) => {
    const account: Record<string, unknown> = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON!);
    account.token_uri = tokenUri;
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(googlePlayProvider.verify(request, {
      ...env,
      GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify(account),
    })).rejects.toBeInstanceOf(ProviderUnavailableError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("uses the fixed Google endpoint when optional token_uri is absent", async () => {
    const account: Record<string, unknown> = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON!);
    delete account.token_uri;
    const urls = stubFetch(() => Response.json({ purchaseState: 0 }));
    await googlePlayProvider.verify(request, {
      ...env,
      GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify(account),
    });
    expect(urls[0]).toBe("https://oauth2.googleapis.com/token");
  });

  it("never follows a redirect with the bearer token on the Play API call", async () => {
    const inits: (RequestInit | undefined)[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      inits.push(init);
      return String(input).startsWith("https://oauth2.googleapis.com/")
        ? Response.json({ access_token: "token", expires_in: 3600 })
        : Response.json({ purchaseState: 0 });
    }));
    await googlePlayProvider.verify(request, env);
    expect(inits.map((init) => init?.redirect)).toEqual(["error", "error"]);
  });

  it("uses one OAuth exchange for a cold batch", async () => {
    const urls = stubFetch(() => Response.json({ purchaseState: 0 }));
    const response = await handleRequest(new Request("https://example.test/v1/verify-batch", {
      method: "POST",
      body: JSON.stringify({
        packageName: request.packageName,
        purchases: Array.from({ length: 25 }, (_, i) => ({ productId: `pack_${i}`, purchaseToken: `t${i}` })),
      }),
    }), env, { rateLimiter: { allow: async () => true } });
    const body = await response.json() as { results: { valid: boolean }[] };
    expect(response.status).toBe(200);
    expect(body.results).toHaveLength(25);
    expect(body.results.every((result) => result.valid)).toBe(true);
    expect(urls.filter((url) => url.includes("oauth2"))).toHaveLength(1);
    expect(urls.filter((url) => url.includes("androidpublisher"))).toHaveLength(25);
  });

  it("returns an unknown result for each item when batch authentication fails", async () => {
    const fetchMock = vi.fn(async () => new Response("down", { status: 503 }));
    vi.stubGlobal("fetch", fetchMock);
    const response = await handleRequest(new Request("https://example.test/v1/verify-batch", {
      method: "POST",
      body: JSON.stringify({ packageName: request.packageName, purchases: [
        { productId: "pack_one", purchaseToken: "secret-1" },
        { productId: "pack_two", purchaseToken: "secret-2" },
      ] }),
    }), env, { rateLimiter: { allow: async () => true } });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ results: [
      { valid: false, reason: "provider_unavailable", productId: "pack_one" },
      { valid: false, reason: "provider_unavailable", productId: "pack_two" },
    ] });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("refreshes the token when the service account configuration changes", async () => {
    const urls = stubFetch(() => Response.json({ purchaseState: 0 }));
    await googlePlayProvider.verify(request, env);
    const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON!);
    // A different PEM representation is enough to prove the key participates in the cache identity.
    account.private_key += "\n";
    await googlePlayProvider.verify(request, { ...env, GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify(account) });
    expect(urls.filter((url) => url.includes("oauth2"))).toHaveLength(2);
  });

  it("treats malformed provider configuration and responses as unavailable", async () => {
    await expect(googlePlayProvider.verify(request, { ...env, GOOGLE_SERVICE_ACCOUNT_JSON: "null" }))
      .rejects.toBeInstanceOf(ProviderUnavailableError);
    vi.stubGlobal("fetch", vi.fn(async () => new Response("not json")));
    await expect(googlePlayProvider.verify(request, env)).rejects.toBeInstanceOf(ProviderUnavailableError);
    resetTokenCache();
    stubFetch(() => new Response("not json"));
    await expect(googlePlayProvider.verify(request, env)).rejects.toBeInstanceOf(ProviderUnavailableError);
  });
});
