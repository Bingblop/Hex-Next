/**
 * Naive fixed-window rate limit kept in the Cache API. Counts are per
 * Cloudflare location and best-effort (concurrent requests can race), which is
 * enough to slow down token guessing. The Cache API does nothing on
 * *.workers.dev hostnames, so the limit only applies on a custom domain.
 */
export interface RateLimiter {
  /** Returns true when the request may proceed. `origin` is the Worker's own origin. */
  allow(clientKey: string, origin: string): Promise<boolean>;
}

const WINDOW_SECONDS = 60;

export function cacheRateLimiter(cache: Cache, limitPerWindow: number): RateLimiter {
  return {
    async allow(clientKey: string, origin: string): Promise<boolean> {
      if (limitPerWindow <= 0) return true;
      const slot = Math.floor(Date.now() / 1000 / WINDOW_SECONDS);
      // Keys stay under the Worker's own hostname; the path is never routed.
      const cacheKey = new Request(
        `${origin}/__rate-limit/${encodeURIComponent(clientKey)}/${slot}`,
      );

      const cached = await cache.match(cacheKey);
      const count = cached ? Number(await cached.text()) || 0 : 0;
      if (count >= limitPerWindow) return false;

      await cache.put(
        cacheKey,
        new Response(String(count + 1), {
          headers: { "Cache-Control": `max-age=${WINDOW_SECONDS}` },
        }),
      );
      return true;
    },
  };
}
