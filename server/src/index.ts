import { directSaleProvider } from "./providers/directSale";
import { googlePlayProvider } from "./providers/googlePlay";
import {
  ProviderUnavailableError,
  type Env,
  type PurchaseProvider,
  type VerifyRequest,
  type VerifyResult,
} from "./providers/types";
import { cacheRateLimiter, secondsUntilNextWindow, type RateLimiter } from "./rateLimit";
import {
  MAX_BATCH_BODY_BYTES,
  MAX_BODY_BYTES,
  readBodyLimited,
  validateBatchBody,
  validateVerifyBody,
} from "./validate";

const DEFAULT_RATE_LIMIT = 60;
/** Store lookups run at most this many at a time for one batch. */
const BATCH_CONCURRENCY = 5;

const VERIFY_PATH = "/v1/verify";
const VERIFY_BATCH_PATH = "/v1/verify-batch";

const PROVIDERS: Record<string, PurchaseProvider> = {
  [googlePlayProvider.name]: googlePlayProvider,
  [directSaleProvider.name]: directSaleProvider,
};

export interface Deps {
  provider?: PurchaseProvider;
  rateLimiter?: RateLimiter;
}

export function selectProvider(env: Env): PurchaseProvider | null {
  return PROVIDERS[env.PROVIDER?.trim() || googlePlayProvider.name] ?? null;
}

function json(status: number, body: unknown, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...extraHeaders,
    },
  });
}

function invalid(status: number, reason: string): Response {
  return json(status, { valid: false, reason });
}

export async function handleRequest(request: Request, env: Env, deps: Deps = {}): Promise<Response> {
  const url = new URL(request.url);
  const batch = url.pathname === VERIFY_BATCH_PATH;
  if (!batch && url.pathname !== VERIFY_PATH) {
    return invalid(404, "not_found");
  }
  if (request.method !== "POST") {
    return invalid(405, "method_not_allowed");
  }

  // A batch counts as one request, so a cold restore of every owned pack costs one slot.
  const rateLimiter =
    deps.rateLimiter ?? cacheRateLimiter(caches.default, parseLimit(env.RATE_LIMIT_PER_MINUTE));
  const clientIp = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await rateLimiter.allow(clientIp, url.origin))) {
    return json(
      429,
      { valid: false, reason: "rate_limited" },
      { "Retry-After": String(secondsUntilNextWindow()) },
    );
  }

  const text = await readBodyLimited(request, batch ? MAX_BATCH_BODY_BYTES : MAX_BODY_BYTES);
  if (text === null) {
    return invalid(413, "body_too_large");
  }
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return invalid(400, "invalid_json");
  }

  const provider = deps.provider ?? selectProvider(env);

  if (batch) {
    const validation = validateBatchBody(body, env.PACKAGE_NAME);
    if (!validation.ok) {
      return invalid(400, validation.reason);
    }
    if (!provider) {
      return invalid(503, "unknown_provider");
    }
    const results = await verifyAll(provider, validation.value, env);
    return json(200, { results });
  }

  const validation = validateVerifyBody(body, env.PACKAGE_NAME);
  if (!validation.ok) {
    return invalid(400, validation.reason);
  }
  if (!provider) {
    return invalid(503, "unknown_provider");
  }

  try {
    const result = await provider.verify(validation.value, env);
    return json(200, result);
  } catch (error) {
    if (error instanceof ProviderUnavailableError) {
      // Logged without request data so purchase tokens never reach the logs.
      console.error(`${provider.name}: ${error.message}`);
      return invalid(503, "provider_unavailable");
    }
    console.error(`${provider.name}: unexpected error`);
    return invalid(500, "internal_error");
  }
}

/** One /v1/verify-batch answer, in request order. Tokens are never echoed back. */
export type BatchItemResult = VerifyResult & { productId: string };

/**
 * Verifies each request, BATCH_CONCURRENCY at a time. An outage for one item becomes
 * `provider_unavailable` for that item only (the client treats it as "unknown").
 */
async function verifyAll(
  provider: PurchaseProvider,
  requests: VerifyRequest[],
  env: Env,
): Promise<BatchItemResult[]> {
  const results: BatchItemResult[] = new Array(requests.length);
  let failures = 0;
  for (let start = 0; start < requests.length; start += BATCH_CONCURRENCY) {
    const slice = requests.slice(start, start + BATCH_CONCURRENCY);
    await Promise.all(
      slice.map(async (req, offset) => {
        let result: BatchItemResult;
        try {
          const verdict = await provider.verify(req, env);
          result = verdict.valid ? verdict : { ...verdict, productId: req.productId };
        } catch (error) {
          failures++;
          const reason = error instanceof ProviderUnavailableError ? "provider_unavailable" : "internal_error";
          result = { valid: false, reason, productId: req.productId };
        }
        results[start + offset] = result;
      }),
    );
  }
  if (failures > 0) {
    // Logged without request data so purchase tokens never reach the logs.
    console.error(`${provider.name}: ${failures} of ${requests.length} batch items could not be verified`);
  }
  return results;
}

function parseLimit(value: string | undefined): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_RATE_LIMIT;
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },
} satisfies ExportedHandler<Env>;
