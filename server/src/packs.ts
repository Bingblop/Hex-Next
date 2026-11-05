import type { Env, PurchaseProvider } from "./providers/types";
import { ProviderUnavailableError } from "./providers/types";

/** POST /v1/pack: one paid pack's zip, only for a verified purchase that covers it. */
export const PACK_PATH = "/v1/pack";

const PACK_ID = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const ALL_PACKS = "all_packs";
const PACK_PREFIX = "pack_";

/** Mirrors the app's ProductIds.forPack. */
export function productForPack(packId: string): string {
  return PACK_PREFIX + packId.toLowerCase().replace(/[^a-z0-9_]/g, "_");
}

export interface PackRequest {
  packageName: string;
  packId: string;
  productId: string;
  purchaseToken: string;
}

export type PackValidation = { ok: true; value: PackRequest } | { ok: false; reason: string };

export function validatePackBody(body: unknown, packageName: string): PackValidation {
  if (typeof body !== "object" || body === null || Array.isArray(body)) return { ok: false, reason: "invalid_body" };
  const b = body as Record<string, unknown>;
  const str = (k: string) => (typeof b[k] === "string" ? (b[k] as string) : "");
  const req: PackRequest = {
    packageName: str("packageName"),
    packId: str("packId"),
    productId: str("productId"),
    purchaseToken: str("purchaseToken"),
  };
  if (req.packageName !== packageName) return { ok: false, reason: "wrong_package" };
  if (!PACK_ID.test(req.packId)) return { ok: false, reason: "invalid_pack" };
  if (req.purchaseToken.length < 1 || req.purchaseToken.length > 4096) return { ok: false, reason: "invalid_token" };
  // Only a purchase that names this pack, or the all-packs unlock, can fetch it. Bundles pick
  // their packs on the phone, which the server cannot see, so they are not accepted here yet.
  if (req.productId !== ALL_PACKS && req.productId !== productForPack(req.packId)) {
    return { ok: false, reason: "product_does_not_cover_pack" };
  }
  return { ok: true, value: req };
}

/** Minimal R2 binding surface (keeps tests free of Workers types). */
export interface PackBucket {
  get(key: string): Promise<{ body: ReadableStream; size: number } | null>;
}

export type PackEnv = Env & { PACKS?: PackBucket };

/**
 * Verifies [req] with [provider] and streams `<packId>.zip` from the private PACKS bucket.
 * The bucket is never public; without it the endpoint answers 503 so a misconfigured deploy
 * cannot fall back to anything else.
 */
export async function servePack(req: PackRequest, env: PackEnv, provider: PurchaseProvider): Promise<Response> {
  const bucket = env.PACKS;
  if (!bucket) return problem(503, "packs_unavailable");
  let verdict;
  try {
    verdict = await provider.verify(
      { packageName: req.packageName, productId: req.productId, purchaseToken: req.purchaseToken },
      env,
    );
  } catch (error) {
    if (error instanceof ProviderUnavailableError) return problem(503, "provider_unavailable");
    return problem(500, "internal_error");
  }
  if (!verdict.valid) return problem(403, verdict.reason || "not_purchased");
  const object = await bucket.get(`${req.packId}.zip`);
  if (!object) return problem(404, "pack_not_found");
  return new Response(object.body, {
    status: 200,
    headers: {
      "Content-Type": "application/zip",
      "Content-Length": String(object.size),
      "Cache-Control": "private, no-store",
      "Content-Disposition": `attachment; filename="${req.packId}.zip"`,
    },
  });
}

function problem(status: number, reason: string): Response {
  return new Response(JSON.stringify({ valid: false, reason }), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}
