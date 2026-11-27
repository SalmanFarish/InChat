import test from "node:test";
import assert from "node:assert/strict";
import { processEncryptedOlmAcknowledgementPush } from "../src/olm-push.js";

const deliveryId = "a".repeat(64);
const baseRequest = {
  senderUid: "alice",
  senderDeviceId: "alice-device",
  recipientDeviceId: "bob-device",
  deliveryId,
  messageId: "message-123",
};

function fixture({
  token = "sender-fcm-token",
  acknowledgement,
  fcmError,
  fcmResult,
} = {}) {
  const database = new Map([
    ["olmDeliveryAcks/alice/alice-device/" + deliveryId, acknowledgement || {
      recipientUid: "bob",
      recipientDeviceId: "bob-device",
      messageId: "message-123",
      acknowledgedAt: 1000,
    }],
    ["deviceKeys/alice/alice-device", {
      schemaVersion: 1, deviceId: "alice-device", ed25519IdentityKey: "alice-public-key",
    }],
    ["deviceKeys/bob/bob-device", {
      schemaVersion: 1, deviceId: "bob-device", ed25519IdentityKey: "bob-public-key",
    }],
    ["deviceKeys/alice/alice-companion", {
      schemaVersion: 1, deviceId: "alice-companion", ed25519IdentityKey: "alice-companion-public-key",
    }],
    ["privateUsers/alice/olmDeviceFcmTokens/alice-device", token],
  ]);
  const versions = new Map();
  const sentMessages = [];
  const env = { FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com" };
  const adminDatabaseRequest = async (_env, accessToken, path) => {
    assert.equal(accessToken, "admin-access-token");
    return structuredClone(database.get(path) ?? null);
  };
  const fetchImpl = async (url, options = {}) => {
    const parsed = new URL(url);
    const path = decodeURIComponent(parsed.pathname).replace(/^\//, "").replace(/\.json$/, "");
    const method = options.method || "GET";
    const version = versions.get(path) || 0;
    if (method === "GET") {
      return new Response(JSON.stringify(database.get(path) ?? null), {
        status: 200, headers: { ETag: '"v' + version + '"' },
      });
    }
    if (options.headers?.["If-Match"] !== '"v' + version + '"') {
      return new Response("precondition failed", { status: 412 });
    }
    if (method === "PUT") database.set(path, JSON.parse(options.body));
    else if (method === "DELETE") database.delete(path);
    else return new Response("unsupported", { status: 405 });
    versions.set(path, version + 1);
    return new Response("null", { status: 200 });
  };
  const fcmRequest = async (accessToken, url, method, body, toleratedStatuses) => {
    assert.equal(accessToken, "admin-access-token");
    sentMessages.push({ url, method, body, toleratedStatuses });
    if (fcmError) throw fcmError;
    return fcmResult || { ok: true, status: 200, body: { name: "projects/demo/messages/1" } };
  };
  const dispatch = (body = baseRequest, uid = "bob", now = 1_000_000) =>
    processEncryptedOlmAcknowledgementPush({
      env, uid, body,
      adminToken: { token: "admin-access-token", projectId: "demo-project" },
      adminDatabaseRequest, fcmRequest, fetchImpl, now,
    });
  return { database, dispatch, sentMessages };
}

test("sends an exact metadata-only acknowledgement wake-up to the sender device", async () => {
  const state = fixture();
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);
  const message = state.sentMessages[0].body.message;
  assert.equal(message.token, "sender-fcm-token");
  assert.deepEqual(message.data, {
    type: "encrypted_olm_ack",
    deliveryId,
    senderDeviceId: "alice-device",
  });
  assert.deepEqual(message.android, { priority: "normal", ttl: "86400s" });
  assert.equal("notification" in message, false);
  assert.equal(
    state.database.get("olmAckPushDispatch/alice/alice-device/" + deliveryId).status,
    "sent",
  );
});

test("an exact acknowledgement retry does not send a duplicate wake-up", async () => {
  const state = fixture();
  const first = await state.dispatch();
  const second = await state.dispatch();

  assert.equal(first.payload.notificationSent, true);
  assert.equal(second.payload.alreadyProcessed, true);
  assert.equal(state.sentMessages.length, 1);
});

test("rejects an acknowledgement written for a different recipient device", async () => {
  const state = fixture({
    acknowledgement: {
      recipientUid: "bob",
      recipientDeviceId: "another-device",
      messageId: "message-123",
      acknowledgedAt: 1000,
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 409);
  assert.equal(state.sentMessages.length, 0);
});

test("rejects an acknowledgement with a different message identity", async () => {
  const state = fixture({
    acknowledgement: {
      recipientUid: "bob",
      recipientDeviceId: "bob-device",
      messageId: "another-message",
      acknowledgedAt: 1000,
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 409);
  assert.equal(state.sentMessages.length, 0);
});

test("rejects a wake-up request with extra fields", async () => {
  const state = fixture();
  const response = await state.dispatch({ ...baseRequest, chatId: "untrusted-route" });

  assert.equal(response.status, 400);
  assert.equal(state.sentMessages.length, 0);
});

test("supports acknowledgements from another device on the sender's own account", async () => {
  const state = fixture({
    acknowledgement: {
      recipientUid: "alice",
      recipientDeviceId: "alice-companion",
      messageId: "message-123",
      acknowledgedAt: 1000,
    },
  });
  const response = await state.dispatch({
    ...baseRequest,
    recipientDeviceId: "alice-companion",
  }, "alice");

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);
  assert.equal(state.sentMessages[0].body.message.token, "sender-fcm-token");
});

test("does not send when the sender device has no FCM token", async () => {
  const state = fixture({ token: null });
  const response = await state.dispatch();

  assert.equal(response.status, 503);
  assert.equal(response.payload.success, false);
  assert.equal(response.payload.retryable, true);
  assert.equal(response.payload.notificationSent, false);
  assert.equal(response.payload.reason, "sender_device_has_no_fcm_token");
  assert.equal(state.sentMessages.length, 0);
});

test("rejects an acknowledgement that is absent from the durable relay", async () => {
  const state = fixture({ acknowledgement: null });
  state.database.delete("olmDeliveryAcks/alice/alice-device/" + deliveryId);
  const response = await state.dispatch();

  assert.equal(response.status, 404);
  assert.equal(state.sentMessages.length, 0);
});

test("removes an unregistered sender token and keeps the acknowledgement wake retryable", async () => {
  const state = fixture({
    fcmResult: {
      ok: true,
      status: 404,
      body: { error: { details: [{ errorCode: "UNREGISTERED" }] } },
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 503);
  assert.equal(response.payload.success, false);
  assert.equal(response.payload.retryable, true);
  assert.equal(response.payload.notificationSent, false);
  assert.equal(response.payload.reason, "sender_device_token_unregistered");
  assert.equal(state.database.has("privateUsers/alice/olmDeviceFcmTokens/alice-device"), false);
  assert.equal(
    state.database.get("olmAckPushDispatch/alice/alice-device/" + deliveryId).status,
    "unregistered",
  );
});
