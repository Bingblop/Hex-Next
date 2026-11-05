import type { VerifyRequest } from "./providers/types";

export const MAX_BODY_BYTES = 4096;
/** Most purchases in one POST /v1/verify-batch body. */
/** Kept well under the Workers Free limit of 50 subrequests per call: one Play lookup per item plus OAuth refreshes. */
export const MAX_BATCH_ITEMS = 25;
/** Body limit for /v1/verify-batch: room for MAX_BATCH_ITEMS full-length tokens. */
export const MAX_BATCH_BODY_BYTES = 128 * 1024;

// Play product ids: lowercase letters, digits, '_' and '.', starting with a letter or digit.
const PRODUCT_ID = /^[a-z0-9][a-z0-9._]{0,148}$/;
// Android application ids: dot-separated Java identifiers, at least two segments.
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
// Purchase tokens are opaque; allow printable ASCII without spaces.
const PURCHASE_TOKEN = /^[\x21-\x7e]{1,2048}$/;

export type ValidationResult =
  | { ok: true; value: VerifyRequest }
  | { ok: false; reason: string };

/** Checks a parsed JSON body against the /v1/verify contract. */
export function validateVerifyBody(body: unknown, expectedPackageName: string): ValidationResult {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, reason: "body_not_object" };
  }
  const { packageName, productId, purchaseToken } = body as Record<string, unknown>;

  if (typeof packageName !== "string" || !PACKAGE_NAME.test(packageName)) {
    return { ok: false, reason: "invalid_package_name" };
  }
  if (packageName !== expectedPackageName) {
    return { ok: false, reason: "wrong_package" };
  }
  if (typeof productId !== "string" || !PRODUCT_ID.test(productId)) {
    return { ok: false, reason: "invalid_product_id" };
  }
  if (typeof purchaseToken !== "string" || !PURCHASE_TOKEN.test(purchaseToken)) {
    return { ok: false, reason: "invalid_purchase_token" };
  }
  return { ok: true, value: { packageName, productId, purchaseToken } };
}

export type BatchValidationResult =
  | { ok: true; value: VerifyRequest[] }
  | { ok: false; reason: string };

/**
 * Checks a parsed JSON body against the /v1/verify-batch contract:
 * `{ packageName, purchases: [{ productId, purchaseToken }] }` with 1 to MAX_BATCH_ITEMS entries.
 * One malformed entry rejects the whole body.
 */
export function validateBatchBody(body: unknown, expectedPackageName: string): BatchValidationResult {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, reason: "body_not_object" };
  }
  const { packageName, purchases } = body as Record<string, unknown>;
  if (typeof packageName !== "string" || !PACKAGE_NAME.test(packageName)) {
    return { ok: false, reason: "invalid_package_name" };
  }
  if (packageName !== expectedPackageName) {
    return { ok: false, reason: "wrong_package" };
  }
  if (!Array.isArray(purchases) || purchases.length === 0) {
    return { ok: false, reason: "invalid_purchases" };
  }
  if (purchases.length > MAX_BATCH_ITEMS) {
    return { ok: false, reason: "too_many_purchases" };
  }
  const value: VerifyRequest[] = [];
  for (const item of purchases) {
    if (typeof item !== "object" || item === null || Array.isArray(item)) {
      return { ok: false, reason: "invalid_purchases" };
    }
    const { productId, purchaseToken } = item as Record<string, unknown>;
    if (typeof productId !== "string" || !PRODUCT_ID.test(productId)) {
      return { ok: false, reason: "invalid_product_id" };
    }
    if (typeof purchaseToken !== "string" || !PURCHASE_TOKEN.test(purchaseToken)) {
      return { ok: false, reason: "invalid_purchase_token" };
    }
    value.push({ packageName, productId, purchaseToken });
  }
  return { ok: true, value };
}

/** The request body stream failed or yielded something other than bytes; the client sent no usable body. */
export class BodyReadError extends Error {
  constructor(options?: { cause?: unknown }) {
    super("Request body could not be read", options);
    this.name = "BodyReadError";
  }
}

/**
 * Reads the request body as text, stopping as soon as it exceeds `limit` bytes.
 * Returns null when the body is too large. Throws [BodyReadError] when the body stream fails, and
 * a RangeError for a `limit` that is not a positive safe integer. The stream lock is always released.
 */
export async function readBodyLimited(request: Request, limit = MAX_BODY_BYTES): Promise<string | null> {
  if (!Number.isSafeInteger(limit) || limit <= 0) {
    throw new RangeError("Body limit must be a positive safe integer");
  }
  const declared = request.headers.get("Content-Length");
  if (declared !== null && Number(declared) > limit) {
    return null;
  }
  if (!request.body) {
    return "";
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (error) {
        throw new BodyReadError({ cause: error });
      }
      if (chunk.done) break;
      const value: unknown = chunk.value;
      // A non-byte chunk has no byteLength: the size check would compare NaN and never trip.
      if (!(value instanceof Uint8Array)) {
        await reader.cancel().catch(() => undefined);
        throw new BodyReadError();
      }
      total += value.byteLength;
      if (total > limit) {
        // Best effort: an oversized body is still answered 413 when cancelling fails.
        await reader.cancel().catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}
