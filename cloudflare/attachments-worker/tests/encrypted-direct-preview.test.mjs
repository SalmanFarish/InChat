import test from "node:test";
import assert from "node:assert/strict";
import { syncEncryptedDirectPreview } from "../src/encrypted-direct-preview.js";

const env = { FIREBASE_DATABASE_URL: "https://example.invalid" };
const defaultDeliveryId = "a".repeat(64);

function relayRecord({ messageId = "message-1", deliveryId = defaultDeliveryId } = {}) {
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
    conversationType: "direct",
    conversationId: "alice_bob",
    conversationPeerUserId: "bob",
  };
}

function metadataValue(database, path) {
  const parts = path.split("/");
  if (parts[0] === "chats" && parts.length === 3) {
    const root = database.get("chats/" + parts[1]);
    return root && typeof root === "object" ? root[parts[2]] ?? null : null;
  }
  if (parts[0] === "chats" && parts.length === 2) {
    throw new Error("Whole chat root reads are forbidden in this fixture.");
  }
  return database.get(path) ?? null;
}

function fixture({
  readTimestamp = 0,
  senderInbox = { unreadCount: 0, lastTimestamp: 0 },
  recipientInbox = { unreadCount: 0, lastTimestamp: 0 },
  blocked = false,
  relayAvailable = true,
} = {}) {
  const testEnv = { ...env };
  const database = new Map([
    ["chats/alice_bob", {
      type: "direct",
      participantA: "alice",
      participantB: "bob",
    }],
    ["users/alice", { username: "Alice" }],
    ["users/bob", { username: "Bob" }],
    ["deviceKeys/alice/alice-device", {
      schemaVersion: 1,
      deviceId: "alice-device",
      ed25519IdentityKey: "alice-ed25519-key",
    }],
    ["deviceKeys/bob/bob-device", {
      schemaVersion: 1,
      deviceId: "bob-device",
      ed25519IdentityKey: "bob-ed25519-key",
    }],
    ...(relayAvailable ? [
      ["olmInbox/bob/bob-device/" + defaultDeliveryId, relayRecord()],
    ] : []),
    ["chatReadState/bob/alice_bob", readTimestamp],
    ["userChats/alice/alice_bob", senderInbox],
    ["userChats/bob/alice_bob", recipientInbox],
    ["blockedUsers/alice/bob", blocked ? true : null],
    ["blockedUsers/bob/alice", null],
  ]);
  const versions = new Map();

  const adminDatabaseRequest = async (_env, token, path) => {
    assert.equal(token, "admin-token");
    const value = metadataValue(database, path);
    return value === undefined ? null : structuredClone(value);
  };

  const fetchImpl = async (url, options = {}) => {
    const parsed = new URL(url);
    const rawSegments = parsed.pathname.split("/").filter(Boolean);
    const last = rawSegments.pop().replace(/\.json$/, "");
    const path = [...rawSegments, last].map(decodeURIComponent).join("/");
    const version = versions.get(path) || 0;

    if ((options.method || "GET") === "GET") {
      const value = metadataValue(database, path);
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
      database.set(path, JSON.parse(options.body));
      versions.set(path, version + 1);
      return new Response("null", { status: 200 });
    }

    throw new Error("Unexpected preview database method: " + options.method);
  };

  const request = (body) => syncEncryptedDirectPreview({
    env: testEnv,
    uid: "alice",
    body,
    adminToken: "admin-token",
    adminDatabaseRequest,
    fetchImpl,
    now: 5_000,
  });

  const send = (overrides = {}) => {
    const messageId = overrides.messageId || "message-1";
    const deliveryId = overrides.deliveryId || defaultDeliveryId;
    if (relayAvailable) {
      database.set(
        "olmInbox/bob/bob-device/" + deliveryId,
        relayRecord({ messageId, deliveryId }),
      );
    }
    return request({
      chatId: "alice_bob",
      messageId,
      deliveryId,
      senderDeviceId: "alice-device",
      recipientDeviceId: "bob-device",
      sentAtMillis: 1_000,
      ...overrides,
    });
  };

  return { database, send, request, env: testEnv };
}

test("syncs encrypted preview from Worker mailbox metadata without Firebase olmInbox", async () => {
  const state = fixture();
  state.database.delete("olmInbox/bob/bob-device/" + defaultDeliveryId);
  state.env.OLM_MAILBOX = {
    idFromName: (name) => ({ name }),
    get: (id) => ({
      fetch: async () => {
        assert.equal(id.name, "bob:bob-device");
        return new Response(JSON.stringify({
          delivery: {
            deliveryId: defaultDeliveryId,
            messageId: "message-1",
            senderUid: "alice",
            senderDeviceId: "alice-device",
            recipientUid: "bob",
            recipientDeviceId: "bob-device",
            conversationType: "direct",
            conversationId: "alice_bob",
            conversationPeerUserId: "bob",
            groupId: null,
            membershipEpoch: null,
            envelopeSha256: "c".repeat(64),
            expiresAt: 10_000,
            status: "pending",
          },
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      },
    }),
  };
  const response = await state.request({
    chatId: "alice_bob",
    messageId: "message-1",
    deliveryId: defaultDeliveryId,
    senderDeviceId: "alice-device",
    recipientDeviceId: "bob-device",
    sentAtMillis: 1_000,
  });
  assert.equal(response.status, 200);
  assert.equal(state.database.get("userChats/bob/alice_bob").lastMessage, "Encrypted message");
});

test("syncs only a generic encrypted preview to both direct inboxes", async () => {
  const state = fixture();
  const response = await state.send();

  assert.equal(response.status, 200);
  assert.deepEqual(response.payload, { success: true, previewUpdated: true });

  const sender = state.database.get("userChats/alice/alice_bob");
  const recipient = state.database.get("userChats/bob/alice_bob");
  for (const inbox of [sender, recipient]) {
    assert.equal(inbox.lastMessage, "Encrypted message");
    assert.equal(inbox.lastTimestamp, 1_000);
    assert.equal(inbox.lastMessageId, "message-1");
    assert.equal(inbox.chatType, "direct");
  }
  assert.equal(sender.lastSenderId, "alice");
  assert.equal(recipient.lastSenderId, "alice");
  assert.deepEqual(recipient.recentEncryptedMessageIds, ["message-1"]);
  assert.equal(recipient.unreadCount, 1);
  assert.equal(JSON.stringify(sender).includes("plaintext"), false);
  assert.equal(JSON.stringify(recipient).includes("plaintext"), false);
});

test("duplicate device wake-ups do not increment the recipient unread count twice", async () => {
  const state = fixture();
  await state.send();
  const duplicate = await state.send();

  assert.equal(duplicate.status, 200);
  assert.equal(state.database.get("userChats/bob/alice_bob").unreadCount, 1);
  assert.deepEqual(
    state.database.get("userChats/bob/alice_bob").recentEncryptedMessageIds,
    ["message-1"],
  );
});

test("does not add unread count when the recipient has already read the message", async () => {
  const state = fixture({ readTimestamp: 1_000 });
  const response = await state.send();

  assert.equal(response.status, 200);
  assert.equal(state.database.get("userChats/bob/alice_bob").unreadCount, 0);
});

test("out-of-order older messages increment unread once without rolling previews backward", async () => {
  const state = fixture();
  await state.send({
    messageId: "newer-message",
    deliveryId: "b".repeat(64),
    sentAtMillis: 2_000,
  });
  await state.send({
    messageId: "older-message",
    deliveryId: "c".repeat(64),
    sentAtMillis: 1_000,
  });

  const recipient = state.database.get("userChats/bob/alice_bob");
  assert.equal(recipient.lastMessageId, "newer-message");
  assert.equal(recipient.lastTimestamp, 2_000);
  assert.equal(recipient.unreadCount, 2);
  assert.deepEqual(recipient.recentEncryptedMessageIds, [
    "newer-message",
    "older-message",
  ]);
});



test("rejects a relay record whose authenticated message metadata does not match the preview", async () => {
  const state = fixture();
  state.database.set(
    "olmInbox/bob/bob-device/" + defaultDeliveryId,
    relayRecord({ messageId: "different-message" }),
  );

  const response = await state.request({
    chatId: "alice_bob",
    messageId: "message-1",
    deliveryId: defaultDeliveryId,
    senderDeviceId: "alice-device",
    recipientDeviceId: "bob-device",
    sentAtMillis: 1_000,
  });

  assert.equal(response.status, 409);
  assert.equal(state.database.get("userChats/alice/alice_bob").lastMessage, undefined);
  assert.equal(state.database.get("userChats/bob/alice_bob").lastMessage, undefined);
});

test("rejects a preview request when its claimed ciphertext relay record is missing", async () => {
  const state = fixture({ relayAvailable: false });
  const response = await state.send();

  assert.equal(response.status, 503);
  assert.equal(response.payload.retryable, true);
  assert.equal(state.database.get("userChats/bob/alice_bob").lastMessage, undefined);
  assert.equal(state.database.get("userChats/bob/alice_bob").unreadCount, 0);
});

test("does not update inbox previews when either participant has blocked the other", async () => {
  const state = fixture({ blocked: true });
  const response = await state.send();

  assert.equal(response.status, 200);
  assert.equal(response.payload.previewUpdated, false);
  assert.equal(state.database.get("userChats/bob/alice_bob").lastMessage, undefined);
  assert.equal(state.database.get("userChats/bob/alice_bob").unreadCount, 0);
});

test("rejects unknown fields, invalid participant scope, and future timestamps", async () => {
  const state = fixture();
  const unknown = await state.send({ plaintext: "must not be accepted" });
  const future = await state.send({
    messageId: "future-message",
    deliveryId: "d".repeat(64),
    sentAtMillis: 400_000,
  });

  const badScope = await syncEncryptedDirectPreview({
    env,
    uid: "mallory",
    body: {
      chatId: "alice_bob",
      messageId: "message-1",
      deliveryId: defaultDeliveryId,
      senderDeviceId: "alice-device",
      recipientDeviceId: "bob-device",
      sentAtMillis: 1_000,
    },
    adminToken: "admin-token",
    adminDatabaseRequest: async () => ({
      type: "direct",
      participantA: "alice",
      participantB: "bob",
    }),
    fetchImpl: async () => {
      throw new Error("Inbox must not be accessed for a non-member");
    },
    now: 5_000,
  });

  assert.equal(unknown.status, 400);
  assert.equal(future.status, 400);
  assert.equal(badScope.status, 403);
});
