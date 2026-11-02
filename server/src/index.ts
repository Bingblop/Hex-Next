import { directSaleProvider } from "./providers/directSale";
import { googlePlayProvider } from "./providers/googlePlay";
import { ProviderUnavailableError, type Env, type PurchaseProvider } from "./providers/types";
import { cacheRateLimiter, type RateLimiter } from "./rateLimit";
import { readBodyLimited, validateVerifyBody } from "./validate";

const DEFAULT_RATE_LIMIT = 30;

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

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

function invalid(status: number, reason: string): Response {
  return json(status, { valid: false, reason });
}

export async function handleRequest(request: Request, env: Env, deps: Deps = {}): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname !== "/v1/verify") {
    return invalid(404, "not_found");
  }
  if (request.method !== "POST") {
    return invalid(405, "method_not_allowed");
  }

  const rateLimiter =
    deps.rateLimiter ?? cacheRateLimiter(caches.default, parseLimit(env.RATE_LIMIT_PER_MINUTE));
  const clientIp = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await rateLimiter.allow(clientIp, url.origin))) {
    return invalid(429, "rate_limited");
  }

  const text = await readBodyLimited(request);
  if (text === null) {
    return invalid(413, "body_too_large");
  }
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return invalid(400, "invalid_json");
  }

  const validation = validateVerifyBody(body, env.PACKAGE_NAME);
  if (!validation.ok) {
    return invalid(400, validation.reason);
  }

  const provider = deps.provider ?? selectProvider(env);
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

function parseLimit(value: string | undefined): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_RATE_LIMIT;
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },
} satisfies ExportedHandler<Env>;
