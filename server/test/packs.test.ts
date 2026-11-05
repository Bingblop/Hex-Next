import { describe, expect, it } from "vitest";
import { handleRequest } from "../src/index";
import { productForPack } from "../src/packs";
import type { Env, PurchaseProvider, VerifyRequest } from "../src/providers/types";

const allowAll = { allow: async () => true };
const zip = new Uint8Array([0x50, 0x4b, 3, 4]);

function bucket(keys: string[]) {
  return {
    async get(key: string) {
      if (!keys.includes(key)) return null;
      return { body: new Blob([zip]).stream(), size: zip.length };
    },
  };
}

function provider(valid: boolean) {
  const calls: VerifyRequest[] = [];
  const p: PurchaseProvider = {
    name: "fake",
    async verify(req) {
      calls.push(req);
      return valid ? { valid: true, productId: req.productId, orderId: "GPA.1" } : { valid: false, reason: "not_purchased" };
    },
  };
  return { p, calls };
}

function post(payload: unknown): Request {
  return new Request("https://licence.example.test/v1/pack", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7" },
    body: JSON.stringify(payload),
  });
}

const env = (keys: string[] = ["willemite.zip"]) => ({ PACKAGE_NAME: "com.hexnext.themer", PACKS: bucket(keys) }) as Env;
const body = { packageName: "com.hexnext.themer", packId: "willemite", productId: productForPack("willemite"), purchaseToken: "t" };

describe("POST /v1/pack", () => {
  it("serves the zip for a verified purchase of that pack", async () => {
    const { p, calls } = provider(true);
    const res = await handleRequest(post(body), env(), { provider: p, rateLimiter: allowAll });
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/zip");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(zip);
    expect(calls[0].productId).toBe("pack_willemite");
  });

  it("accepts the all-packs unlock", async () => {
    const { p } = provider(true);
    const res = await handleRequest(post({ ...body, productId: "all_packs" }), env(), { provider: p, rateLimiter: allowAll });
    expect(res.status).toBe(200);
  });

  it("refuses a product that does not cover the pack, without asking the store", async () => {
    const { p, calls } = provider(true);
    const res = await handleRequest(post({ ...body, productId: "pack_agate" }), env(), { provider: p, rateLimiter: allowAll });
    expect(res.status).toBe(400);
    expect(calls).toHaveLength(0);
  });

  it("refuses an unverified purchase", async () => {
    const { p } = provider(false);
    const res = await handleRequest(post(body), env(), { provider: p, rateLimiter: allowAll });
    expect(res.status).toBe(403);
  });

  it("rejects path tricks in the pack id", async () => {
    const { p } = provider(true);
    for (const packId of ["../secret", "willemite/../../x", "", "A".repeat(80)]) {
      const res = await handleRequest(post({ ...body, packId }), env(), { provider: p, rateLimiter: allowAll });
      expect(res.status).toBe(400);
    }
  });

  it("answers 503 without a packs bucket", async () => {
    const { p } = provider(true);
    const res = await handleRequest(post(body), { PACKAGE_NAME: "com.hexnext.themer" }, { provider: p, rateLimiter: allowAll });
    expect(res.status).toBe(503);
  });
});
