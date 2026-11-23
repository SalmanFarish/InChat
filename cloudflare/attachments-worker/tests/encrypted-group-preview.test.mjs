import test from "node:test";
import assert from "node:assert/strict";
import { syncEncryptedGroupPreview } from "../src/encrypted-group-preview.js";

const env = { FIREBASE_DATABASE_URL: "https://example.invalid" };
const groupId = "group-123";
const deliveryId = "a".repeat(64);
const memberRoles = {
  alice: "admin",
  bob: "member",
  carol: "member",
};

function relayRecord(messageId = "group-message-1") {
  return {
    senderUid: "alice",
    senderDeviceId: "alice-device",
    messageId,
    envelope: JSON.stringify({
      encryptionVersion: 1,
      algorithm: "olm",
      senderDeviceId: "alice-device",
      recipientDeviceId: "bob-device",
      sessionId: "olm-session-1",
      ciphertext: "AQID",
    }),
    conversationType: "group",
    conversationId: groupId,
    groupId,
    membershipEpoch: 5,
  };
}

function fixture({ epoch = 5, recipientReadTimestamp = 0, relayAvailable = true } = {}) {
  const database = new Map([
    ["chats/" + groupId, {
      type: "group",
      name: "Team Chat",
      members: { ...memberRoles },
      cryptoMembership: { epoch, members: { ...memberRoles } },
    }],
    ["users/alice", { username: "Alice" }],
    ["deviceKeys/alice/alice-device", {
      schemaVersion: 1,
      deviceId: "alice-device",
      ed25519IdentityKey: "alice-ed25519",
    }],
    ["deviceKeys/bob/bob-phone", {
      schemaVersion: 1,
      deviceId: "bob-phone",
      ed25519IdentityKey: "bob-ed25519",
    }],
    ["deviceKeys/bob/bob-device", {
      schemaVersion: 1,
      deviceId: "bob-device",
      ed25519IdentityKey: "bob-ed25519",
    }],
    ["chats/" + groupId + "/groupReadCursors/bob", recipientReadTimestamp],
    ["chats/" + groupId + "/groupReadCursors/carol", 0],
    ["userChats/alice/" + groupId, { unreadCount: 0, lastTimestamp: 0 }],
    ["userChats/bob/" + groupId, { unreadCount: 0, lastTimestamp: 0 }],
    ["userChats/carol/" + groupId, { unreadCount: 0, lastTimestamp: 0 }],
  ]);
  if (relayAvailable) {
    database.set(
      "olmInbox/bob/bob-device/" + deliveryId,
      relayRecord(),
    );
  }
  const versions = new Map();

  const adminDatabaseRequest = async (_env, token, path) => {
    assert.equal(token, "admin-token");
    const value = database.get(path);
    return value === undefined ? null : structuredClone(value);
  };

  const fetchImpl = async (url, options = {}) => {
    const parsed = new URL(url);
    const segments = parsed.pathname.split("/").filter(Boolean);
    const last = segments.pop().replace(/\.json$/, "");
    const key = [...segments, last].map(decodeURIComponent).join("/");
    const version = versions.get(key) || 0;

    if ((options.method || "GET") === "GET") {
      const value = database.get(key);
      return new Response(
        value === undefined || value === null ? "null" : JSON.stringify(value),
        {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            ETag: '"' + version + '"',
          },
        },
      );
    }
    if (options.method === "PUT") {
      if (options.headers?.["If-Match"] !== '"' + version + '"') {
        return new Response("", { status: 412 });
      }
      database.set(key, JSON.parse(options.body));
      versions.set(key, version + 1);
      return new Response("null", { status: 200 });
    }
    throw new Error("Unexpected group preview database method: " + options.method);
  };

  const request = (body) => syncEncryptedGroupPreview({
    env,
    uid: "alice",
    body,
    adminToken: "admin-token",
    adminDatabaseRequest,
    fetchImpl,
    now: 5_000,
  });

  const send = (overrides = {}) => {
    const messageId = overrides.messageId || "group-message-1";
    database.set(
      "olmInbox/bob/bob-device/" + deliveryId,
      relayRecord(messageId),
    );
    return request({
      groupId,
      messageId,
      sentAtMillis: 1_000,
      membershipEpoch: 5,
      deliveryId,
      recipientUserId: "bob",
      senderDeviceId: "alice-device",
      recipientDeviceId: "bob-device",
      ...overrides,
    });
  };

  return { database, send, request };
}

test("updates all current group member inboxes using only a generic encrypted preview", async () => {
  const state = fixture({ recipientReadTimestamp: 1_000 });
  const response = await state.send();

  assert.equal(response.status, 200);
  assert.deepEqual(response.payload, { success: true, previewUpdated: true });

  for (const member of Object.keys(memberRoles)) {
    const inbox = state.database.get("userChats/" + member + "/" + groupId);
    assert.equal(inbox.lastMessage, "Encrypted message");
    assert.equal(inbox.lastTimestamp, 1_000);
    assert.equal(inbox.lastMessageId, "group-message-1");
    assert.equal(inbox.chatType, "group");
    assert.equal(inbox.groupName, "Team Chat");
    assert.equal(inbox.memberCount, 3);
    assert.equal(inbox.lastSenderId, "alice");
    assert.equal(JSON.stringify(inbox).includes("plaintext"), false);
  }
  assert.equal(state.database.get("userChats/alice/" + groupId).unreadCount, 0);
  assert.equal(state.database.get("userChats/bob/" + groupId).unreadCount, 0);
  assert.equal(state.database.get("userChats/carol/" + groupId).unreadCount, 1);
});

test("duplicate device deliveries do not increment group unread counts more than once", async () => {
  const state = fixture();
  await state.send();
  await state.send();

  for (const member of ["bob", "carol"]) {
    const inbox = state.database.get("userChats/" + member + "/" + groupId);
    assert.equal(inbox.unreadCount, 1);
    assert.deepEqual(inbox.recentEncryptedMessageIds, ["group-message-1"]);
  }
});

test("rejects a stale membership epoch before reading or updating member inboxes", async () => {
  const state = fixture({ epoch: 6 });
  const response = await state.send({ membershipEpoch: 5 });

  assert.equal(response.status, 409);
  assert.equal(state.database.get("userChats/bob/" + groupId).lastMessage, undefined);
  assert.equal(state.database.get("userChats/carol/" + groupId).lastMessage, undefined);
});

test("rejects a recipient who is no longer a group member", async () => {
  const state = fixture();
  const response = await state.request({
    groupId,
    messageId: "group-message-1",
    sentAtMillis: 1_000,
    membershipEpoch: 5,
    deliveryId,
    recipientUserId: "mallory",
    senderDeviceId: "alice-device",
    recipientDeviceId: "bob-device",
  });

  assert.equal(response.status, 403);
  assert.equal(state.database.get("userChats/bob/" + groupId).lastMessage, undefined);
});

test("requires the exact group-scoped ciphertext relay", async () => {
  const missing = fixture({ relayAvailable: false });
  const missingResult = await missing.request({
    groupId,
    messageId: "group-message-1",
    sentAtMillis: 1_000,
    membershipEpoch: 5,
    deliveryId,
    recipientUserId: "bob",
    senderDeviceId: "alice-device",
    recipientDeviceId: "bob-device",
  });
  assert.equal(missingResult.status, 503);
  assert.equal(missingResult.payload.retryable, true);

  const mismatch = fixture();
  mismatch.database.set(
    "olmInbox/bob/bob-device/" + deliveryId,
    relayRecord("not-the-requested-message"),
  );
  const mismatchResult = await mismatch.request({
    groupId,
    messageId: "group-message-1",
    sentAtMillis: 1_000,
    membershipEpoch: 5,
    deliveryId,
    recipientUserId: "bob",
    senderDeviceId: "alice-device",
    recipientDeviceId: "bob-device",
  });
  assert.equal(mismatchResult.status, 409);
});

test("rejects unknown plaintext fields and future timestamps", async () => {
  const state = fixture();
  const unknown = await state.send({ plaintext: "must not be accepted" });
  const future = await state.send({
    messageId: "future-message",
    sentAtMillis: 400_000,
  });

  assert.equal(unknown.status, 400);
  assert.equal(future.status, 400);
});
