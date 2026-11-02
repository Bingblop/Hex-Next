import type { VerifyRequest } from "./providers/types";

export const MAX_BODY_BYTES = 4096;

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

/**
 * Reads the request body as text, stopping as soon as it exceeds `limit` bytes.
 * Returns null when the body is too large.
 */
export async function readBodyLimited(request: Request, limit = MAX_BODY_BYTES): Promise<string | null> {
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
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}
