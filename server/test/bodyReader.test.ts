import { describe, expect, it } from "vitest";
import { BodyReadError, readBodyLimited } from "../src/validate";
import { handleRequest } from "../src/index";

function request(body: ReadableStream<Uint8Array>): Request {
  const init: RequestInit & { duplex: "half" } = {
    method: "POST",
    body,
    duplex: "half",
  };
  return new Request("https://example.test/v1/verify", init);
}

describe("body reader ownership", () => {
  it("releases its stream lock after a complete body", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("{}"));
        controller.close();
      },
    });
    expect(await readBodyLimited(request(stream))).toBe("{}");
    expect(stream.locked).toBe(false);
  });

  it("cancels an oversized body and releases its lock", async () => {
    let cancelled = false;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(10)); },
      cancel() { cancelled = true; },
    });
    expect(await readBodyLimited(request(stream), 4)).toBeNull();
    expect(cancelled).toBe(true);
    expect(stream.locked).toBe(false);
  });

  it("releases its lock when the underlying stream fails", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new TypeError("broken stream")); },
    });
    const failure = await readBodyLimited(request(stream)).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(BodyReadError);
    expect((failure as BodyReadError).cause).toBeInstanceOf(TypeError);
    expect(stream.locked).toBe(false);
  });

  it("rejects invalid bounds before reading", async () => {
    for (const limit of [0, -1, Number.POSITIVE_INFINITY, 1.5]) {
      await expect(readBodyLimited(new Request("https://example.test"), limit)).rejects.toThrow(RangeError);
    }
  });

  it("returns a controlled invalid-body answer when a client stream fails", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new TypeError("broken client stream")); },
    });
    const response = await handleRequest(request(stream), { PACKAGE_NAME: "com.hexnext.themer" },
      { rateLimiter: { allow: async () => true } });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ valid: false, reason: "invalid_body_stream" });
    expect(stream.locked).toBe(false);
  });

  it("answers a stream failure of any error type with 400, not a Worker exception", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new Error("Network connection lost")); },
    });
    const response = await handleRequest(request(stream), { PACKAGE_NAME: "com.hexnext.themer" },
      { rateLimiter: { allow: async () => true } });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ valid: false, reason: "invalid_body_stream" });
  });

  it("still answers 413 when cancelling an oversized body fails", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(10)); },
      cancel() { throw new Error("cancel failed"); },
    });
    expect(await readBodyLimited(request(stream), 4)).toBeNull();
    expect(stream.locked).toBe(false);
  });

  it("refuses non-byte chunks instead of skipping the size limit", async () => {
    let reads = 0; // finite, so the unfixed reader (which never trips the limit) still terminates
    const reader = {
      read: async () => (++reads > 3 ? { done: true, value: undefined } : { done: false, value: "x".repeat(64) }),
      cancel: async () => undefined,
      releaseLock: () => undefined,
    };
    const fake = { headers: new Headers(), body: { getReader: () => reader } } as unknown as Request;
    await expect(readBodyLimited(fake, 4)).rejects.toBeInstanceOf(BodyReadError);
  });
});
