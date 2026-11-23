import test from "node:test";
import assert from "node:assert/strict";
import {
  PayloadTooLargeError,
  readRequestTextLimited,
} from "../src/bounded-request-body.js";

function makeRequest({ chunks = [], contentLength = null, body = true } = {}) {
  let index = 0;
  let cancelled = false;
  let reads = 0;
  const stream = body ? new ReadableStream({
    pull(controller) {
      reads += 1;
      if (index < chunks.length) {
        controller.enqueue(new TextEncoder().encode(chunks[index]));
        index += 1;
      } else {
        controller.close();
      }
    },
    cancel() {
      cancelled = true;
    },
  }, { highWaterMark: 0 }) : null;
  const headers = new Headers();
  if (contentLength !== null) headers.set("Content-Length", String(contentLength));
  return {
    request: { headers, body: stream },
    get cancelled() { return cancelled; },
    get reads() { return reads; },
  };
}

test("reads a body up to the exact byte limit and decodes UTF-8", async () => {
  const payload = "hello 🌍";
  const byteLength = new TextEncoder().encode(payload).byteLength;
  const fixture = makeRequest({ chunks: ["hello ", "🌍"], contentLength: byteLength });

  assert.equal(await readRequestTextLimited(fixture.request, byteLength), payload);
  assert.equal(fixture.cancelled, false);
});

test("rejects a declared oversized body before reading the stream", async () => {
  const fixture = makeRequest({ chunks: ["never read"], contentLength: 20 });

  await assert.rejects(
    readRequestTextLimited(fixture.request, 8),
    PayloadTooLargeError,
  );
  assert.equal(fixture.reads, 0);
});

test("rejects chunked bodies as soon as streamed bytes exceed the limit", async () => {
  const fixture = makeRequest({ chunks: ["1234", "5678", "9"] });

  await assert.rejects(
    readRequestTextLimited(fixture.request, 8),
    PayloadTooLargeError,
  );
  assert.equal(fixture.cancelled, true);
});

test("rejects malformed Content-Length values", async () => {
  const fixture = makeRequest({ chunks: ["{}"], contentLength: "8.2" });

  await assert.rejects(
    readRequestTextLimited(fixture.request, 16),
    /Invalid Content-Length/,
  );
  assert.equal(fixture.reads, 0);
});

test("returns an empty string when a request has no body", async () => {
  const fixture = makeRequest({ body: false });

  assert.equal(await readRequestTextLimited(fixture.request, 8), "");
});
