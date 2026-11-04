import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import {
  createSignedJwt,
  googlePlayProvider,
  interpretPurchase,
  interpretSubscription,
  resetTokenCache,
} from "../src/providers/googlePlay";
import { ProviderUnavailableError, type Env } from "../src/providers/types";

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

  it("checks a subscription with the subscriptionsv2 API", async () => {
    const urls = stubFetch(() =>
      Response.json({
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        latestOrderId: "GPA.7",
        lineItems: [{ productId: "ai_monthly", expiryTime: "2999-01-01T00:00:00Z" }],
      }),
    );
    expect(
      await googlePlayProvider.verify({ ...request, productId: "ai_monthly" }, env),
    ).toEqual({ valid: true, productId: "ai_monthly", orderId: "GPA.7" });
    expect(urls[1]).toBe(
      "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
        "com.hexnext.themer/purchases/subscriptionsv2/tokens/tok%2Fen",
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
});

describe("interpretSubscription", () => {
  const now = Date.parse("2026-11-04T00:00:00Z");
  const line = (expiryTime: string) => [{ productId: "ai_monthly", expiryTime }];

  it("accepts active, grace-period and cancelled-but-paid subscriptions", () => {
    for (const state of [
      "SUBSCRIPTION_STATE_ACTIVE",
      "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
      "SUBSCRIPTION_STATE_CANCELED",
    ]) {
      expect(
        interpretSubscription(
          { subscriptionState: state, latestOrderId: "GPA.1", lineItems: line("2026-12-01T00:00:00Z") },
          "ai_monthly",
          now,
        ),
      ).toEqual({ valid: true, productId: "ai_monthly", orderId: "GPA.1" });
    }
  });

  it("rejects expired, on-hold, pending and mismatched subscriptions", () => {
    expect(
      interpretSubscription(
        { subscriptionState: "SUBSCRIPTION_STATE_CANCELED", lineItems: line("2026-11-01T00:00:00Z") },
        "ai_monthly",
        now,
      ),
    ).toEqual({ valid: false, reason: "subscription_expired" });
    expect(
      interpretSubscription(
        { subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD", lineItems: line("2026-12-01T00:00:00Z") },
        "ai_monthly",
        now,
      ),
    ).toEqual({ valid: false, reason: "subscription_inactive" });
    expect(
      interpretSubscription(
        { subscriptionState: "SUBSCRIPTION_STATE_PENDING", lineItems: line("2026-12-01T00:00:00Z") },
        "ai_monthly",
        now,
      ),
    ).toEqual({ valid: false, reason: "purchase_pending" });
    expect(
      interpretSubscription(
        { subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "other", expiryTime: "2999-01-01T00:00:00Z" }] },
        "ai_monthly",
        now,
      ),
    ).toEqual({ valid: false, reason: "product_mismatch" });
  });
});
