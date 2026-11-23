/**
 * Reads a request body without buffering more than the caller's byte limit.
 * The Content-Length check is only an early rejection; streamed bytes are
 * counted as well because the header can be absent or untrustworthy.
 */
export class PayloadTooLargeError extends Error {
  constructor(maxBytes) {
    super("Request body exceeds " + maxBytes + " bytes.");
    this.name = "PayloadTooLargeError";
  }
}

export async function readRequestTextLimited(request, maxBytes) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) {
    throw new TypeError("A non-negative safe byte limit is required.");
  }

  const contentLength = request.headers.get("Content-Length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength)) {
      throw new TypeError("Invalid Content-Length header.");
    }
    const declaredLength = Number(contentLength);
    if (!Number.isSafeInteger(declaredLength) || declaredLength > maxBytes) {
      throw new PayloadTooLargeError(maxBytes);
    }
  }

  const reader = request.body?.getReader();
  if (!reader) return "";

  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!(value instanceof Uint8Array)) {
        throw new TypeError("Request stream yielded a non-byte chunk.");
      }
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel().catch(() => {});
        throw new PayloadTooLargeError(maxBytes);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bodyBytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bodyBytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bodyBytes);
}
