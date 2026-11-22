import test from "node:test";
import assert from "node:assert/strict";
import { processEncryptedOlmPush } from "../src/olm-push.js";

const deliveryId = "a".repeat(64);
const baseRequest = {
  chatId: "alice_bob",
  recipientUid: "bob",
  deliveryId,
  messageId: "message-123",
  senderDeviceId: "alice-device",
  recipientDeviceId: "bob-device",
};

function fixture({
  chat = { type: "direct", participantA: "alice", participantB: "bob" },
  blocked = false,
  token = "fcm-registration-token",
  relayRecord,
  fcmResult,
  fcmError,
  existingReceipt,
} = {}) {
  const database = new Map([
    ["chats/alice_bob", chat],
    ["deviceKeys/alice/alice-device", {
      schemaVersion: 1,
      deviceId: "alice-device",
      ed25519IdentityKey: "alice-public-key",
    }],
    ["deviceKeys/bob/bob-device", {
      schemaVersion: 1,
      deviceId: "bob-device",
      ed25519IdentityKey: "bob-public-key",
    }],
    ["deviceKeys/alice/bob-device", {
      schemaVersion: 1,
      deviceId: "bob-device",
      ed25519IdentityKey: "alice-secondary-public-key",
    }],
    ["privateUsers/alice/olmDeviceFcmTokens/bob-device", "alice-device-fcm-token"],
    ["olmInbox/bob/bob-device/" + deliveryId, relayRecord || {
      senderUid: "alice",
      senderDeviceId: "alice-device",
      messageId: "message-123",
      conversationType: "direct",
      conversationId: "alice_bob",
      conversationPeerUserId: "bob",
      envelope: JSON.stringify({
        encryptionVersion: 1,
        algorithm: "olm",
        senderDeviceId: "alice-device",
        recipientDeviceId: "bob-device",
        sessionId: "session-1",
        ciphertext: "AQID",
      }),
    }],
    ["privateUsers/bob/olmDeviceFcmTokens/bob-device", token],
  ]);
  database.set(
    "olmInbox/alice/bob-device/" + deliveryId,
    database.get("olmInbox/bob/bob-device/" + deliveryId),
  );
  if (blocked) database.set("blockedUsers/bob/alice", { uid: "alice" });
  if (existingReceipt) {
    database.set(
      "olmPushDispatch/bob/bob-device/" + deliveryId,
      structuredClone(existingReceipt),
    );
  }

  const versions = new Map();
  const sentMessages = [];
  const env = { FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com" };

  const adminDatabaseRequest = async (_env, accessToken, path) => {
    assert.equal(accessToken, "admin-access-token");
    return structuredClone(database.get(path) ?? null);
  };

  const fetchImpl = async (url, options = {}) => {
    const parsed = new URL(url);
    const path = decodeURIComponent(parsed.pathname)
      .replace(/^\//, "")
      .replace(/\.json$/, "");
    const method = options.method || "GET";
    const currentVersion = versions.get(path) || 0;

    if (method === "GET") {
      return new Response(JSON.stringify(database.get(path) ?? null), {
        status: 200,
        headers: { ETag: '"v' + currentVersion + '"' },
      });
    }

    const suppliedEtag = options.headers?.["If-Match"];
    const expectedEtag = '"v' + currentVersion + '"';
    if (suppliedEtag !== expectedEtag) {
      return new Response("precondition failed", { status: 412 });
    }

    if (method === "PUT") {
      database.set(path, JSON.parse(options.body));
    } else if (method === "DELETE") {
      database.delete(path);
    } else {
      return new Response("unsupported", { status: 405 });
    }
    versions.set(path, currentVersion + 1);
    return new Response("null", { status: 200 });
  };

  const fcmRequest = async (accessToken, url, method, body, toleratedStatuses) => {
    assert.equal(accessToken, "admin-access-token");
    sentMessages.push({ url, method, body, toleratedStatuses });
    if (fcmError) throw fcmError;
    return fcmResult || {
      ok: true,
      status: 200,
      body: { name: "projects/demo/messages/1" },
    };
  };

  const dispatch = (body = baseRequest, uid = "alice", now = 1_000_000) =>
    processEncryptedOlmPush({
      env,
      uid,
      body,
      adminToken: { token: "admin-access-token", projectId: "demo-project" },
      adminDatabaseRequest,
      fcmRequest,
      fetchImpl,
      now,
    });

  return { database, dispatch, sentMessages };
}

test("sends one minimal data-only FCM wake-up to the verified recipient device", async () => {
  const state = fixture();
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);

  const { url, method, body } = state.sentMessages[0];
  assert.equal(method, "POST");
  assert.match(url, /projects\/demo-project\/messages:send$/);
  assert.equal(body.message.token, "fcm-registration-token");
  assert.deepEqual(body.message.data, {
    type: "encrypted_olm",
    deliveryId,
  });
  assert.equal("notification" in body.message, false);
  assert.deepEqual(body.message.android, {
    priority: "normal",
    ttl: "86400s",
  });

  const receipt = state.database.get(
    "olmPushDispatch/bob/bob-device/" + deliveryId,
  );
  assert.equal(receipt.status, "sent");
  assert.equal(receipt.notificationSent, true);
});

test("routes sender-owned direct-device pushes to the sender account token", async () => {
  const state = fixture();
  const response = await state.dispatch({ ...baseRequest, recipientUid: "alice" });

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);
  assert.equal(state.sentMessages[0].body.message.token, "alice-device-fcm-token");
  assert.equal(state.database.has("olmPushDispatch/alice/bob-device/" + deliveryId), true);
});

test("accepts canonical padded Base64URL ciphertext from Android", async () => {
  const state = fixture();
  const path = "olmInbox/bob/bob-device/" + deliveryId;
  const record = state.database.get(path);
  const envelope = JSON.parse(record.envelope);
  envelope.sessionId = "session-padded";
  envelope.ciphertext = "AQI=";
  state.database.set(path, { ...record, envelope: JSON.stringify(envelope) });
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);
});

test("an exact retry does not send a second FCM wake-up", async () => {
  const state = fixture();
  const first = await state.dispatch();
  const second = await state.dispatch();

  assert.equal(first.payload.notificationSent, true);
  assert.equal(second.payload.alreadyProcessed, true);
  assert.equal(state.sentMessages.length, 1);
});

test("does not steal an unexpired push-dispatch lease", async () => {
  const state = fixture({
    existingReceipt: {
      senderUid: "alice",
      messageId: "message-123",
      recipientUid: "bob",
      recipientDeviceId: "bob-device",
      deliveryId,
      status: "processing",
      claimId: "another-worker",
      attempt: 2,
      leaseUntil: 1_000_001,
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 503);
  assert.equal(response.payload.retryable, true);
  assert.equal(state.sentMessages.length, 0);
  assert.equal(
    state.database.get("olmPushDispatch/bob/bob-device/" + deliveryId).claimId,
    "another-worker",
  );
});

test("reclaims an expired push-dispatch lease and increments its attempt", async () => {
  const state = fixture({
    existingReceipt: {
      senderUid: "alice",
      messageId: "message-123",
      recipientUid: "bob",
      recipientDeviceId: "bob-device",
      deliveryId,
      status: "processing",
      claimId: "expired-worker",
      attempt: 4,
      leaseUntil: 999_999,
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, true);
  assert.equal(state.sentMessages.length, 1);
  const receipt = state.database.get(
    "olmPushDispatch/bob/bob-device/" + deliveryId,
  );
  assert.equal(receipt.status, "sent");
  assert.equal(receipt.attempt, 5);
  assert.notEqual(receipt.claimId, "expired-worker");
});

test("suppresses push when either account has blocked the other", async () => {
  const state = fixture({ blocked: true });
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, false);
  assert.equal(response.payload.reason, "blocked");
  assert.equal(state.sentMessages.length, 0);
});

test("rejects a relay envelope whose authenticated device metadata differs", async () => {
  const state = fixture({
    relayRecord: {
      senderUid: "alice",
      senderDeviceId: "alice-device",
      messageId: "message-123",
      conversationType: "direct",
      conversationId: "alice_bob",
      conversationPeerUserId: "bob",
      envelope: JSON.stringify({
        encryptionVersion: 1,
        algorithm: "olm",
        senderDeviceId: "attacker-device",
        recipientDeviceId: "bob-device",
        sessionId: "session-1",
        ciphertext: "AQID",
      }),
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 409);
  assert.equal(state.sentMessages.length, 0);
});

test("fails closed when a conversation has a missing or unknown type", async () => {
  const missingTypeState = fixture({
    chat: { participantA: "alice", participantB: "bob" },
  });
  const unknownTypeState = fixture({
    chat: { type: "legacy", participantA: "alice", participantB: "bob" },
  });

  assert.equal((await missingTypeState.dispatch()).status, 404);
  assert.equal((await unknownTypeState.dispatch()).status, 404);
  assert.equal(missingTypeState.sentMessages.length, 0);
  assert.equal(unknownTypeState.sentMessages.length, 0);
});

test("does not send a push for a group chat or a non-member sender", async () => {
  const groupState = fixture({
    chat: { type: "group", members: { alice: "admin", bob: "member" } },
  });
  const notMemberState = fixture({
    chat: { type: "direct", participantA: "bob", participantB: "carol" },
  });

  assert.equal((await groupState.dispatch()).status, 404);
  assert.equal((await notMemberState.dispatch()).status, 403);
  assert.equal(groupState.sentMessages.length, 0);
  assert.equal(notMemberState.sentMessages.length, 0);
});

test("removes only the unchanged token when FCM reports it unregistered", async () => {
  const state = fixture({
    fcmResult: {
      ok: true,
      status: 404,
      body: { error: { details: [{ errorCode: "UNREGISTERED" }] } },
    },
  });
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, false);
  assert.equal(state.database.has("privateUsers/bob/olmDeviceFcmTokens/bob-device"), false);
  assert.equal(
    state.database.get("olmPushDispatch/bob/bob-device/" + deliveryId).status,
    "unregistered",
  );
  assert.equal(state.sentMessages.length, 1);
});

test("leaves a retryable receipt when FCM is temporarily unavailable", async () => {
  const state = fixture({ fcmError: new Error("temporary FCM failure") });
  const response = await state.dispatch();

  assert.equal(response.status, 503);
  assert.equal(response.payload.retryable, true);
  assert.equal(
    state.database.get("olmPushDispatch/bob/bob-device/" + deliveryId).status,
    "failed",
  );
});

test("rejects unknown request fields before reading relay data", async () => {
  const state = fixture();
  const response = await state.dispatch({
    ...baseRequest,
    unexpectedField: "carol",
  });

  assert.equal(response.status, 400);
  assert.equal(state.sentMessages.length, 0);
});

test("does not notify when the recipient device has no registered token", async () => {
  const state = fixture({ token: null });
  const response = await state.dispatch();

  assert.equal(response.status, 200);
  assert.equal(response.payload.notificationSent, false);
  assert.equal(response.payload.reason, "recipient_device_has_no_fcm_token");
  assert.equal(state.sentMessages.length, 0);
});
