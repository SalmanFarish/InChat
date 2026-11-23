import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

test("attachment PUT is rejected until encrypted media envelopes are supported", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    requests.push({ url, method: init.method || "GET" });

    if (url.startsWith("https://identitytoolkit.googleapis.com/")) {
      return new Response(JSON.stringify({
        users: [{ localId: "sender-account" }],
      }), { status: 200 });
    }

    if (url === "https://example-default-rtdb.firebaseio.com/chats/chat_1.json") {
      return new Response(JSON.stringify({
        participantA: "sender-account",
        participantB: "recipient-account",
      }), { status: 200 });
    }

    throw new Error("Unexpected network request: " + url);
  };

  try {
    const response = await worker.fetch(
      new Request("https://worker.example/v1/attachments/chat_1/message_1", {
        method: "PUT",
        headers: {
          Authorization: "Bearer test-firebase-id-token",
          "Content-Type": "image/jpeg",
          "Content-Length": "4",
          "X-InChat-Attachment-Type": "view_once_photo",
        },
        body: new Uint8Array([1, 2, 3, 4]),
      }),
      {
        FIREBASE_WEB_API_KEY: "test-web-api-key",
        FIREBASE_DATABASE_URL: "https://example-default-rtdb.firebaseio.com",
      },
      {},
    );

    assert.equal(response.status, 501);
    assert.match((await response.json()).error, /encrypted attachment uploads/i);
    assert.equal(requests.length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
