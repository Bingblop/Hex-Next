import { describe, expect, it } from "vitest";
import { handleRequest, selectProvider, type Deps } from "../src/index";
import {
  ProviderUnavailableError,
  type Env,
  type PurchaseProvider,
  type VerifyRequest,
} from "../src/providers/types";

const env: Env = { PACKAGE_NAME: "com.hexnext.themer" };
const body = {
  packageName: "com.hexnext.themer",
  productId: "pack_neon",
  purchaseToken: "token-123",
};

function fakeProvider(impl: (req: VerifyRequest) => Promise<unknown>) {
  const calls: VerifyRequest[] = [];
  const provider: PurchaseProvider = {
    name: "fake",
    async verify(req) {
      calls.push(req);
      return (await impl(req)) as never;
    },
  };
  return { provider, calls };
}

const allowAll = { allow: async () => true };

function post(payload: unknown, path = "/v1/verify"): Request {
  return new Request(`https://licence.example.test${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7" },
    body: typeof payload === "string" ? payload : JSON.stringify(payload),
  });
}

async function call(request: Request, deps: Deps) {
  const response = await handleRequest(request, env, deps);
  return { status: response.status, json: await response.json() };
}

describe("handleRequest", () => {
  it("returns the provider's result for a valid request", async () => {
    const { provider, calls } = fakeProvider(async (req) => ({
      valid: true,
      productId: req.productId,
      orderId: "GPA.1",
    }));
    const result = await call(post(body), { provider, rateLimiter: allowAll });
    expect(result).toEqual({
      status: 200,
      json: { valid: true, productId: "pack_neon", orderId: "GPA.1" },
    });
    expect(calls).toEqual([body]);
  });

  it("rejects other paths and methods", async () => {
    const { provider } = fakeProvider(async () => ({}));
    expect((await call(post(body, "/verify"), { provider, rateLimiter: allowAll })).status).toBe(404);
    const get = new Request("https://licence.example.test/v1/verify");
    expect((await call(get, { provider, rateLimiter: allowAll })).status).toBe(405);
  });

  it("rejects invalid JSON and invalid fields without calling the provider", async () => {
    const { provider, calls } = fakeProvider(async () => ({}));
    expect(await call(post("{not json"), { provider, rateLimiter: allowAll })).toEqual({
      status: 400,
      json: { valid: false, reason: "invalid_json" },
    });
    expect(
      await call(post({ ...body, packageName: "com.other.app" }), { provider, rateLimiter: allowAll }),
    ).toEqual({ status: 400, json: { valid: false, reason: "wrong_package" } });
    expect(calls).toHaveLength(0);
  });

  it("rejects bodies over 4 KB", async () => {
    const { provider, calls } = fakeProvider(async () => ({}));
    const big = { ...body, padding: "x".repeat(5000) };
    expect(await call(post(big), { provider, rateLimiter: allowAll })).toEqual({
      status: 413,
      json: { valid: false, reason: "body_too_large" },
    });
    expect(calls).toHaveLength(0);
  });

  it("returns 429 when the rate limiter refuses", async () => {
    const { provider, calls } = fakeProvider(async () => ({}));
    const keys: string[] = [];
    const deny = {
      allow: async (key: string) => {
        keys.push(key);
        return false;
      },
    };
    const response = await handleRequest(post(body), env, { provider, rateLimiter: deny });
    expect(response.status).toBe(429);
    const retryAfter = Number(response.headers.get("Retry-After"));
    expect(retryAfter).toBeGreaterThanOrEqual(1);
    expect(retryAfter).toBeLessThanOrEqual(60);
    expect(keys).toEqual(["203.0.113.7"]);
    expect(calls).toHaveLength(0);
  });

  it("maps provider outages to 503", async () => {
    const { provider } = fakeProvider(async () => {
      throw new ProviderUnavailableError("down");
    });
    expect(await call(post(body), { provider, rateLimiter: allowAll })).toEqual({
      status: 503,
      json: { valid: false, reason: "provider_unavailable" },
    });
  });
});

describe("handleRequest /v1/verify-batch", () => {
  const batchBody = {
    packageName: "com.hexnext.themer",
    purchases: [
      { productId: "pack_neon", purchaseToken: "token-1" },
      { productId: "pack_gone", purchaseToken: "token-2" },
      { productId: "pack_down", purchaseToken: "token-3" },
    ],
  };

  it("answers every item in request order without echoing tokens", async () => {
    const { provider, calls } = fakeProvider(async (req) => {
      if (req.productId === "pack_gone") return { valid: false, reason: "purchase_not_found" };
      if (req.productId === "pack_down") throw new ProviderUnavailableError("down");
      return { valid: true, productId: req.productId, orderId: "GPA.1" };
    });
    const result = await call(post(batchBody, "/v1/verify-batch"), { provider, rateLimiter: allowAll });
    expect(result).toEqual({
      status: 200,
      json: {
        results: [
          { valid: true, productId: "pack_neon", orderId: "GPA.1" },
          { valid: false, reason: "purchase_not_found", productId: "pack_gone" },
          { valid: false, reason: "provider_unavailable", productId: "pack_down" },
        ],
      },
    });
    expect(calls.map((c) => c.purchaseToken)).toEqual(["token-1", "token-2", "token-3"]);
    expect(JSON.stringify(result.json)).not.toContain("token-");
  });

  it("counts a whole batch as one rate-limited request", async () => {
    const { provider } = fakeProvider(async (req) => ({ valid: true, productId: req.productId, orderId: "" }));
    let count = 0;
    const counting = {
      allow: async () => {
        count++;
        return true;
      },
    };
    const many = {
      packageName: "com.hexnext.themer",
      purchases: Array.from({ length: 25 }, (_, i) => ({ productId: `pack_${i}`, purchaseToken: `t${i}` })),
    };
    const result = await call(post(many, "/v1/verify-batch"), { provider, rateLimiter: counting });
    expect(result.status).toBe(200);
    expect((result.json as { results: unknown[] }).results).toHaveLength(25);
    expect(count).toBe(1);
  });

  it("rejects more than 25 purchases, an empty list and a wrong package", async () => {
    const { provider, calls } = fakeProvider(async () => ({}));
    const tooMany = {
      packageName: "com.hexnext.themer",
      purchases: Array.from({ length: 26 }, (_, i) => ({ productId: `pack_${i}`, purchaseToken: `t${i}` })),
    };
    expect(await call(post(tooMany, "/v1/verify-batch"), { provider, rateLimiter: allowAll })).toEqual({
      status: 400,
      json: { valid: false, reason: "too_many_purchases" },
    });
    expect(
      await call(post({ ...batchBody, purchases: [] }, "/v1/verify-batch"), { provider, rateLimiter: allowAll }),
    ).toEqual({ status: 400, json: { valid: false, reason: "invalid_purchases" } });
    expect(
      await call(post({ ...batchBody, packageName: "com.other.app" }, "/v1/verify-batch"), {
        provider,
        rateLimiter: allowAll,
      }),
    ).toEqual({ status: 400, json: { valid: false, reason: "wrong_package" } });
    expect(calls).toHaveLength(0);
  });

  it("returns 429 with Retry-After when the rate limiter refuses", async () => {
    const { provider, calls } = fakeProvider(async () => ({}));
    const response = await handleRequest(post(batchBody, "/v1/verify-batch"), env, {
      provider,
      rateLimiter: { allow: async () => false },
    });
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).not.toBeNull();
    expect(calls).toHaveLength(0);
  });
});

describe("selectProvider", () => {
  it("defaults to Google Play", () => {
    expect(selectProvider(env)?.name).toBe("google_play");
  });
  it("selects the direct-sale provider", () => {
    expect(selectProvider({ ...env, PROVIDER: "direct_sale" })?.name).toBe("direct_sale");
  });
  it("returns null for an unknown provider", () => {
    expect(selectProvider({ ...env, PROVIDER: "nope" })).toBeNull();
  });
});
