import { describe, expect, it } from "vitest";
import {
  MAX_BATCH_ITEMS,
  MAX_BODY_BYTES,
  readBodyLimited,
  validateBatchBody,
  validateVerifyBody,
} from "../src/validate";

const PKG = "com.hexnext.themer";
const good = { packageName: PKG, productId: "pack_neon.v2", purchaseToken: "abc.DEF-123_xyz" };

describe("validateVerifyBody", () => {
  it("accepts a well-formed body", () => {
    expect(validateVerifyBody(good, PKG)).toEqual({ ok: true, value: good });
  });

  it("drops unknown fields", () => {
    const result = validateVerifyBody({ ...good, extra: 1 }, PKG);
    expect(result).toEqual({ ok: true, value: good });
  });

  it.each([
    [null, "body_not_object"],
    [[good], "body_not_object"],
    ["text", "body_not_object"],
    [{ ...good, packageName: 5 }, "invalid_package_name"],
    [{ ...good, packageName: "nodots" }, "invalid_package_name"],
    [{ ...good, packageName: "com.hexnext.themer.owner" }, "wrong_package"],
    [{ ...good, productId: undefined }, "invalid_product_id"],
    [{ ...good, productId: "Pack_Upper" }, "invalid_product_id"],
    [{ ...good, productId: "../tokens" }, "invalid_product_id"],
    [{ ...good, productId: "_leading" }, "invalid_product_id"],
    [{ ...good, purchaseToken: "" }, "invalid_purchase_token"],
    [{ ...good, purchaseToken: "has space" }, "invalid_purchase_token"],
    [{ ...good, purchaseToken: "x".repeat(2049) }, "invalid_purchase_token"],
  ])("rejects %j with %s", (body, reason) => {
    expect(validateVerifyBody(body, PKG)).toEqual({ ok: false, reason });
  });

  it("accepts all_packs", () => {
    expect(validateVerifyBody({ ...good, productId: "all_packs" }, PKG).ok).toBe(true);
  });
});

describe("readBodyLimited", () => {
  const post = (body: string, headers: Record<string, string> = {}) =>
    new Request("https://example.test/v1/verify", { method: "POST", body, headers });

  it("returns the body under the limit", async () => {
    expect(await readBodyLimited(post("{\"a\":1}"))).toBe("{\"a\":1}");
  });

  it("accepts exactly the limit", async () => {
    expect(await readBodyLimited(post("x".repeat(MAX_BODY_BYTES)))).toHaveLength(MAX_BODY_BYTES);
  });

  it("rejects a body over the limit", async () => {
    expect(await readBodyLimited(post("x".repeat(MAX_BODY_BYTES + 1)))).toBeNull();
  });

  it("rejects a declared Content-Length over the limit", async () => {
    expect(await readBodyLimited(post("{}", { "Content-Length": "999999" }))).toBeNull();
  });

  it("counts bytes, not characters", async () => {
    // 2 bytes per character in UTF-8.
    expect(await readBodyLimited(post("é".repeat(MAX_BODY_BYTES / 2 + 1)))).toBeNull();
  });
});

describe("validateBatchBody", () => {
  const item = { productId: "pack_neon", purchaseToken: "abc.DEF-123_xyz" };

  it("accepts up to MAX_BATCH_ITEMS purchases and adds the package name to each", () => {
    const purchases = Array.from({ length: MAX_BATCH_ITEMS }, () => item);
    const result = validateBatchBody({ packageName: PKG, purchases }, PKG);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value).toHaveLength(MAX_BATCH_ITEMS);
      expect(result.value[0]).toEqual({ packageName: PKG, ...item });
    }
  });

  it.each([
    [null, "body_not_object"],
    [{ packageName: "com.hexnext.themer.owner", purchases: [item] }, "wrong_package"],
    [{ packageName: PKG }, "invalid_purchases"],
    [{ packageName: PKG, purchases: [] }, "invalid_purchases"],
    [{ packageName: PKG, purchases: [null] }, "invalid_purchases"],
    [{ packageName: PKG, purchases: Array.from({ length: MAX_BATCH_ITEMS + 1 }, () => item) }, "too_many_purchases"],
    [{ packageName: PKG, purchases: [item, { ...item, productId: "Bad" }] }, "invalid_product_id"],
    [{ packageName: PKG, purchases: [{ ...item, purchaseToken: "has space" }] }, "invalid_purchase_token"],
  ])("rejects %j with %s", (body, reason) => {
    expect(validateBatchBody(body, PKG)).toEqual({ ok: false, reason });
  });
});
