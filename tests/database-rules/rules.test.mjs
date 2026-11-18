import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, test } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  get,
  ref,
  remove,
  runTransaction,
  set,
  update,
} from "firebase/database";

const projectId = "demo-inchat-rules";
const rules = await readFile(
  new URL("../../database.rules.json", import.meta.url),
  "utf8",
);

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    database: {
      host: "127.0.0.1",
      port: 9000,
      rules,
    },
  });
});

beforeEach(async () => {
  await testEnv.clearDatabase();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();

    await set(ref(db), {
      users: {
        alice: {
          uid: "alice",
          username: "alice",
          readReceiptsVisible: true,
          typingIndicatorVisible: true,
          discoverableByUsername: true,
        },
        bob: {
          uid: "bob",
          username: "bob",
          readReceiptsVisible: true,
          typingIndicatorVisible: true,
          discoverableByUsername: true,
        },
        mallory: {
          uid: "mallory",
          username: "mallory",
        },
      },
      presence: {
        alice: {
          onlineVisible: true,
          lastSeenVisible: true,
          lastSeen: 1000,
          connections: { alicePhone: true },
        },
        bob: {
          onlineVisible: true,
          lastSeenVisible: true,
          lastSeen: 1000,
          connections: { bobPhone: true },
        },
      },
      chats: {
        dm: {
          chatId: "dm",
          type: "direct",
          participantA: "alice",
          participantB: "bob",
          messages: {
            aliceMessage: {
              id: "aliceMessage",
              senderId: "alice",
              senderNickname: "alice",
              text: "Hello",
              timestamp: 1000,
            },
          },
        },
        group: {
          chatId: "group",
          type: "group",
          name: "Test group",
          createdBy: "alice",
          createdAt: 1000,
          members: {
            alice: "admin",
            bob: "member",
          },
          messages: {},
        },
      },
      deviceKeys: {
        alice: { "device-alice-1": publicDeviceBundle("device-alice-1") },
        bob: { "device-bob-1": publicDeviceBundle("device-bob-1") },
      },
      userChats: {
        alice: {
          dm: {
            chatId: "dm",
            otherUserId: "bob",
            otherUsername: "bob",
            lastMessage: "Hello",
            lastTimestamp: 1000,
            lastSenderId: "alice",
            unreadCount: 0,
          },
        },
        bob: {
          dm: {
            chatId: "dm",
            otherUserId: "alice",
            otherUsername: "alice",
            lastMessage: "Hello",
            lastTimestamp: 1000,
            lastSenderId: "alice",
            unreadCount: 1,
          },
        },
      },
    });
  });
});

after(async () => {
  await testEnv?.cleanup();
});

test("a user cannot forge another user's direct-chat inbox preview", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertFails(
    update(ref(bobDb, "userChats/alice/dm"), {
      lastMessage: "Forged preview",
      lastTimestamp: 2000,
    }),
  );
});

test("a user can update their own direct-chat inbox entry", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    update(ref(aliceDb, "userChats/alice/dm"), {
      lastMessage: "Updated locally",
      lastTimestamp: 2000,
      lastSenderId: "bob",
    }),
  );
});

test("clients cannot create a group directly in Realtime Database", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(ref(aliceDb, "chats/forged-group"), {
      chatId: "forged-group",
      type: "group",
      name: "Forged group",
      createdBy: "alice",
      createdAt: 2000,
      members: {
        alice: "admin",
        mallory: "member",
      },
    }),
  );
});

test("even a group admin cannot directly change the members map", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(ref(aliceDb, "chats/group/members/mallory"), "member"),
  );

  await assertFails(
    set(ref(aliceDb, "chats/group/members/bob"), "admin"),
  );
});

test("a user cannot send a direct message using someone else's sender ID", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertFails(
    set(ref(bobDb, "chats/dm/messages/forged"), {
      id: "forged",
      senderId: "alice",
      senderNickname: "alice",
      text: "This was not sent by Alice",
      timestamp: 2000,
    }),
  );
});

test("senders cannot seed Worker-owned message receipt markers", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const baseMessage = {
    id: "reserved-marker",
    senderId: "alice",
    senderNickname: "alice",
    text: "A normal message",
    timestamp: 2000,
  };

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/forged-worker-receipt"), {
      ...baseMessage,
      id: "forged-worker-receipt",
      workerProcessed: {
        status: "sent",
        notificationSent: true,
      },
    }),
  );

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/forged-inbox-marker"), {
      ...baseMessage,
      id: "forged-inbox-marker",
      workerInboxSynced: true,
    }),
  );

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/forged-extra-field"), {
      ...baseMessage,
      id: "forged-extra-field",
      clientControlledMetadata: true,
    }),
  );
});

test("valid message replies and attachments remain writable without extra metadata", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    set(ref(aliceDb, "chats/dm/messages/valid-reply-attachment"), {
      id: "valid-reply-attachment",
      senderId: "alice",
      senderNickname: "alice",
      text: "Reply with a voice note",
      timestamp: 2000,
      replyTo: {
        messageId: "aliceMessage",
        senderId: "alice",
        senderNickname: "alice",
        text: "Hello",
      },
      attachment: {
        type: "voice",
        storageKey: "attachments/dm/valid-reply-attachment",
        fileName: "voice.m4a",
        mimeType: "audio/mp4",
        sizeBytes: 1000,
      },
    }),
  );
});

test("reply and attachment objects reject unexpected client metadata", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/reply-extra-field"), {
      id: "reply-extra-field",
      senderId: "alice",
      senderNickname: "alice",
      text: "A reply",
      timestamp: 2000,
      replyTo: {
        messageId: "aliceMessage",
        senderId: "alice",
        senderNickname: "alice",
        text: "Hello",
        admin: true,
      },
    }),
  );

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/attachment-extra-field"), {
      id: "attachment-extra-field",
      senderId: "alice",
      senderNickname: "alice",
      text: "An attachment",
      timestamp: 2000,
      attachment: {
        type: "voice",
        storageKey: "attachments/dm/attachment-extra-field",
        fileName: "voice.m4a",
        mimeType: "audio/mp4",
        sizeBytes: 1000,
        trusted: true,
      },
    }),
  );
});

test("group admins can still create approved group activity messages", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    set(ref(aliceDb, "chats/group/messages/group-rename-event"), {
      id: "group-rename-event",
      senderId: "alice",
      senderNickname: "SYSTEM",
      text: "@alice renamed the group",
      timestamp: 2000,
      systemEvent: "group_renamed",
    }),
  );
});

test("a recipient cannot delete another user's message", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertFails(
    set(ref(bobDb, "chats/dm/messages/aliceMessage"), null),
  );
});

test("a sender can delete their own direct message", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    set(ref(aliceDb, "chats/dm/messages/aliceMessage"), null),
  );
});

test("profile photo payloads above the server-side limit are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(
      ref(aliceDb, "users/alice/profilePhotoData"),
      "x".repeat(275001),
    ),
  );
});

test("privacy preferences must be booleans", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    set(ref(aliceDb, "users/alice/readReceiptsVisible"), false),
  );

  await assertFails(
    set(ref(aliceDb, "users/alice/typingIndicatorVisible"), "disabled"),
  );
});

test("online status and last-seen visibility are enforced independently", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertSucceeds(get(ref(bobDb, "presence/alice/connections")));
  await assertSucceeds(get(ref(bobDb, "presence/alice/lastSeen")));
  await assertSucceeds(set(ref(aliceDb, "presence/alice/onlineVisible"), false));
  await assertFails(get(ref(bobDb, "presence/alice/connections")));
  await assertSucceeds(get(ref(bobDb, "presence/alice/lastSeen")));
  await assertSucceeds(set(ref(aliceDb, "presence/alice/onlineVisible"), true));
  await assertSucceeds(set(ref(aliceDb, "presence/alice/lastSeenVisible"), false));
  await assertSucceeds(get(ref(bobDb, "presence/alice/connections")));
  await assertFails(get(ref(bobDb, "presence/alice/lastSeen")));
  await assertSucceeds(get(ref(aliceDb, "presence/alice/connections")));
  await assertSucceeds(get(ref(aliceDb, "presence/alice/lastSeen")));
});

test("users can only create or remove their own presence connection nodes", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertSucceeds(set(ref(aliceDb, "presence/alice/connections/secondPhone"), true));
  await assertSucceeds(remove(ref(aliceDb, "presence/alice/connections/secondPhone")));
  await assertFails(set(ref(bobDb, "presence/alice/connections/forged"), true));
  await assertFails(set(ref(aliceDb, "presence/bob/connections/forged"), true));
});

test("read receipts are written by their owner and respect read-receipt privacy", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertSucceeds(set(ref(bobDb, "chats/dm/readBy/bob"), 2000));
  await assertFails(set(ref(bobDb, "chats/dm/readBy/alice"), 2000));
  await assertSucceeds(get(ref(aliceDb, "chats/dm/readBy/bob")));
  await assertSucceeds(set(ref(bobDb, "users/bob/readReceiptsVisible"), false));
  await assertFails(get(ref(aliceDb, "chats/dm/readBy/bob")));
  await assertFails(set(ref(bobDb, "chats/dm/readBy/bob"), 3000));
  await assertSucceeds(remove(ref(bobDb, "chats/dm/readBy/bob")));
});

test("a non-participant cannot read a direct conversation", async () => {
  const malloryDb = testEnv.authenticatedContext("mallory").database();

  await assertFails(get(ref(malloryDb, "chats/dm")));
});

test("a group member can write only their own mirrored group read cursor", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();

  await assertSucceeds(
    set(ref(bobDb, "chats/group/groupReadCursors/bob"), 2000),
  );
  await assertFails(
    set(ref(bobDb, "chats/group/groupReadCursors/alice"), 2000),
  );
  await assertFails(
    set(ref(malloryDb, "chats/group/groupReadCursors/mallory"), 2000),
  );
});

test("group members can read their group, non-members cannot", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();

  await assertSucceeds(get(ref(bobDb, "chats/group")));
  await assertFails(get(ref(malloryDb, "chats/group")));
});


function publicDeviceBundle(deviceId = "device-alice-1") {
  return {
    schemaVersion: 1,
    deviceId,
    ed25519IdentityKey: "ed25519-public-key",
    curve25519IdentityKey: "curve25519-public-key",
    curve25519IdentityKeySignature: "signed-curve25519-identity",
    fallbackKey: {
      keyKind: "fallback",
      keyId: "fallback-1",
      publicKey: "fallback-public-key",
      signature: "fallback-signature",
    },
    oneTimeKeys: [
      {
        keyKind: "one_time",
        keyId: "one-time-1",
        publicKey: "one-time-public-key",
        signature: "one-time-signature",
      },
    ],
    publishedAt: 1700000000000,
  };
}

test("an authenticated owner can publish a public device-key bundle", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertSucceeds(
    set(
      ref(aliceDb, "deviceKeys/alice/device-alice-1"),
      publicDeviceBundle(),
    ),
  );
});

test("authenticated users can read public device bundles", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(
      ref(context.database(), "deviceKeys/alice/device-alice-1"),
      publicDeviceBundle(),
    );
  });

  await assertSucceeds(
    get(ref(bobDb, "deviceKeys/alice/device-alice-1")),
  );
});

test("unauthenticated users cannot read the device-key directory", async () => {
  const unauthenticatedDb = testEnv.unauthenticatedContext().database();

  await assertFails(
    get(ref(unauthenticatedDb, "deviceKeys/alice")),
  );
});

test("a user cannot publish or replace another user's device bundle", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertFails(
    set(
      ref(bobDb, "deviceKeys/alice/device-alice-1"),
      publicDeviceBundle(),
    ),
  );
});



test("authenticated clients can claim a one-time prekey exactly once", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  const [aliceResult, bobResult] = await Promise.all([
    runTransaction(ref(aliceDb, path), (current) =>
      current === null ? true : undefined,
    ),
    runTransaction(ref(bobDb, path), (current) =>
      current === null ? true : undefined,
    ),
  ]);

  assert.equal(
    Number(aliceResult.committed) + Number(bobResult.committed),
    1,
    "only one concurrent claimant should commit",
  );

  const winnerDb = aliceResult.committed ? aliceDb : bobDb;
  const loserDb = aliceResult.committed ? bobDb : aliceDb;
  const retry = await runTransaction(ref(loserDb, path), (current) =>
    current === null ? true : undefined,
  );
  assert.equal(retry.committed, false, "a second claimant must not overwrite the claim");
  await assertSucceeds(get(ref(winnerDb, path)));
});

test("a one-time prekey cannot be claimed after its device is revoked", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const claimPath = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(
    set(ref(aliceDb, "deviceKeys/alice/device-alice-1"), null),
  );
  await assertFails(set(ref(bobDb, claimPath), true));
});

test("unauthenticated clients cannot claim a one-time prekey", async () => {
  const unauthenticatedDb = testEnv.unauthenticatedContext().database();
  await assertFails(
    runTransaction(
      ref(unauthenticatedDb, "deviceKeyClaims/alice/device-alice-1/one-time-1"),
      (current) => current === null ? true : undefined,
    ),
  );
});

test("a claimed one-time prekey cannot be overwritten or deleted", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(set(ref(bobDb, path), true));
  await assertFails(set(ref(aliceDb, path), false));
  await assertFails(set(ref(bobDb, path), null));
});


test("a device owner can clear prekey claims only after revoking that device", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const claimsPath = "deviceKeyClaims/alice/device-alice-1";
  const claimPath = `${claimsPath}/one-time-1`;

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), claimPath), true);
  });

  await assertFails(set(ref(aliceDb, claimsPath), null));

  await assertSucceeds(
    set(ref(aliceDb, "deviceKeys/alice/device-alice-1"), null),
  );
  await assertSucceeds(set(ref(aliceDb, claimsPath), null));
});

test("another account cannot clear orphaned prekey claims", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const claimsPath = "deviceKeyClaims/alice/device-alice-1";
  const claimPath = `${claimsPath}/one-time-1`;

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), claimPath), true);
  });
  await assertSucceeds(
    set(ref(aliceDb, "deviceKeys/alice/device-alice-1"), null),
  );
  await assertFails(set(ref(bobDb, claimsPath), null));
});

const encryptedInboxRecord = (senderUid = "alice", senderDeviceId = "device-alice-1") => ({
  senderUid,
  senderDeviceId,
  messageId: "message-encrypted-1",
  envelope: JSON.stringify({
    encryptionVersion: 1,
    algorithm: "olm",
    senderDeviceId,
    recipientDeviceId: "device-bob-1",
    sessionId: "session-1",
    ciphertext: "AQID",
  }),
});

test("a registered sender can create a ciphertext-only delivery for a registered recipient device", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await assertSucceeds(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "a".repeat(64)),
    encryptedInboxRecord(),
  ));
});

test("only the recipient can read and remove an encrypted device inbox item", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const path = "olmInbox/bob/device-bob-1/" + "b".repeat(64);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), path), encryptedInboxRecord());
  });
  await assertFails(get(ref(aliceDb, path)));
  await assertFails(get(ref(malloryDb, path)));
  await assertSucceeds(get(ref(bobDb, path)));
  await assertSucceeds(set(ref(bobDb, path), null));
});

test("encrypted inbox records are immutable after creation", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const path = "olmInbox/bob/device-bob-1/" + "c".repeat(64);
  await assertSucceeds(set(ref(aliceDb, path), encryptedInboxRecord()));
  await assertSucceeds(set(ref(aliceDb, path), encryptedInboxRecord()));
  await assertFails(set(ref(aliceDb, path), {
    ...encryptedInboxRecord(),
    envelope: "tampered",
  }));
});

test("encrypted delivery cannot be sent from an unregistered device or to an unregistered device", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "d".repeat(64)),
    encryptedInboxRecord("alice", "not-registered"),
  ));
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/not-registered/" + "e".repeat(64)),
    encryptedInboxRecord(),
  ));
});

test("blocked users cannot send encrypted inbox deliveries in either direction", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "blockedUsers/alice/bob"), {
      uid: "bob", username: "bob", blockedAt: 1000,
    });
  });
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "f".repeat(64)),
    encryptedInboxRecord(),
  ));
});


const sasVerificationRecord = (overrides = {}) => {
  const createdAtMillis = Date.now();
  const message = {
    schemaVersion: 1,
    transactionId: "sas-transaction-1",
    senderUserId: "alice",
    senderDeviceId: "device-alice-1",
    recipientUserId: "bob",
    recipientDeviceId: "device-bob-1",
    type: "commitment",
    payload: "a".repeat(64),
    createdAtMillis,
    expiresAtMillis: createdAtMillis + 5 * 60 * 1000,
    ...overrides,
  };
  return {
    senderUid: message.senderUserId,
    senderDeviceId: message.senderDeviceId,
    transactionId: message.transactionId,
    type: message.type,
    payload: message.payload,
    createdAtMillis: message.createdAtMillis,
    expiresAtMillis: message.expiresAtMillis,
    canonicalMessage: JSON.stringify(message),
  };
};

test("a registered device can create a short-lived SAS verification message", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await assertSucceeds(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "1".repeat(64)),
    sasVerificationRecord(),
  ));
});

test("only the recipient can read and acknowledge a SAS verification message", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const path = "sasVerificationInbox/bob/device-bob-1/" + "2".repeat(64);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), path), sasVerificationRecord());
  });
  await assertFails(get(ref(aliceDb, path)));
  await assertFails(get(ref(malloryDb, path)));
  await assertSucceeds(get(ref(bobDb, path)));
  await assertSucceeds(set(ref(bobDb, path), null));
});

test("SAS verification messages are immutable and exact retries are idempotent", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const path = "sasVerificationInbox/bob/device-bob-1/" + "3".repeat(64);
  const record = sasVerificationRecord();
  await assertSucceeds(set(ref(aliceDb, path), record));
  await assertSucceeds(set(ref(aliceDb, path), record));
  await assertFails(set(ref(aliceDb, path), {
    ...record,
    payload: "b".repeat(64),
  }));
});

test("SAS messages require registered sender and recipient devices", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "4".repeat(64)),
    sasVerificationRecord({ senderDeviceId: "unregistered-device" }),
  ));
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/unregistered-device/" + "5".repeat(64)),
    sasVerificationRecord({ recipientDeviceId: "unregistered-device" }),
  ));
});

test("expired and malformed SAS verification messages are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const now = Date.now();
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "6".repeat(64)),
    sasVerificationRecord({ createdAtMillis: now - 700000, expiresAtMillis: now - 1000 }),
  ));
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "7".repeat(64)),
    sasVerificationRecord({ type: "unknown" }),
  ));
});

test("blocked users cannot start SAS verification in either direction", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "blockedUsers/alice/bob"), {
      uid: "bob", username: "bob", blockedAt: 1000,
    });
  });
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "8".repeat(64)),
    sasVerificationRecord(),
  ));
});


test("a device owner can update the activity timestamp without replacing its public keys", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await assertSucceeds(set(
    ref(aliceDb, "deviceKeys/alice/device-alice-1/lastSeenAt"),
    Date.now(),
  ));
  await assertSucceeds(get(ref(aliceDb, "deviceKeys/alice/device-alice-1/ed25519IdentityKey")));
});

test("another account cannot update a user's device activity timestamp", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();
  await assertFails(set(
    ref(bobDb, "deviceKeys/alice/device-alice-1/lastSeenAt"),
    Date.now(),
  ));
});


function deviceTransferMetadata(overrides = {}) {
  const createdAtMillis = Date.now();
  return {
    protocolVersion: 1,
    sourceDeviceId: "device-alice-1",
    targetDeviceId: "device-alice-2",
    transactionId: "transfer-transaction-1",
    createdAtMillis,
    expiresAtMillis: createdAtMillis + 120000,
    payloadSize: 3,
    chunkCount: 1,
    payloadSha256: "a".repeat(64),
    ...overrides,
  };
}

test("same-account registered devices can upload a bounded encrypted transfer relay", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "deviceKeys/alice/device-alice-2"), publicDeviceBundle("device-alice-2"));
  });
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  await assertSucceeds(set(ref(aliceDb, path + "/metadata"), deviceTransferMetadata()));
  await assertSucceeds(set(ref(aliceDb, path + "/chunks/0"), "AQID"));
  await assertSucceeds(set(ref(aliceDb, path + "/ready"), true));
});

test("device transfer relay expiry is capped at fifteen minutes", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "deviceKeys/alice/device-alice-1"), publicDeviceBundle("device-alice-1"));
  });
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  const createdAtMillis = Date.now();
  await assertSucceeds(set(ref(aliceDb, path + "/metadata"), deviceTransferMetadata({
    createdAtMillis,
    expiresAtMillis: createdAtMillis + 900000,
  })));
  await assertSucceeds(set(ref(aliceDb, path), null));

  await assertFails(set(ref(aliceDb, path + "/metadata"), deviceTransferMetadata({
    createdAtMillis,
    expiresAtMillis: createdAtMillis + 900001,
  })));
});

test("device transfer relay is private to the signed-in account", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), path), {
      metadata: deviceTransferMetadata(),
      chunks: { "0": "AQID" },
      ready: true,
    });
  });
  await assertSucceeds(get(ref(aliceDb, path)));
  await assertFails(get(ref(bobDb, path)));
});

test("device transfer relay rejects unregistered devices and oversized metadata", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1/metadata";
  await assertFails(set(ref(aliceDb, path), deviceTransferMetadata({
    sourceDeviceId: "not-registered",
  })));
  await assertFails(set(ref(aliceDb, path), deviceTransferMetadata({
    payloadSize: 9437185,
  })));
});

test("device transfer chunks are immutable and the ready marker is one-way", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "deviceKeys/alice/device-alice-2"), publicDeviceBundle("device-alice-2"));
  });
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  await assertSucceeds(set(ref(aliceDb, path + "/metadata"), deviceTransferMetadata()));
  await assertSucceeds(set(ref(aliceDb, path + "/chunks/0"), "AQID"));
  await assertFails(set(ref(aliceDb, path + "/chunks/0"), "BAUG"));
  await assertSucceeds(set(ref(aliceDb, path + "/ready"), true));
  await assertFails(set(ref(aliceDb, path + "/ready"), false));
});

test("a paired destination can receive a relay before its new identity is published", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "deviceKeys/alice/device-alice-1"), publicDeviceBundle("device-alice-1"));
  });
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  await assertSucceeds(set(ref(aliceDb, path + "/metadata"), deviceTransferMetadata()));
});

test("an incomplete relay can be removed, but a ready relay needs an acknowledgement", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const incompletePath = "deviceTransferInbox/alice/device-alice-2/incomplete-transfer";
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "deviceKeys/alice/device-alice-1"), publicDeviceBundle("device-alice-1"));
  });
  const metadata = deviceTransferMetadata({ transactionId: "incomplete-transfer" });
  await assertSucceeds(set(ref(aliceDb, incompletePath + "/metadata"), metadata));
  await assertSucceeds(set(ref(aliceDb, incompletePath + "/chunks/0"), "AQID"));
  await assertSucceeds(set(ref(aliceDb, incompletePath), null));

  const completedPath = "deviceTransferInbox/alice/device-alice-2/completed-transfer";
  await assertSucceeds(set(ref(aliceDb, completedPath + "/metadata"), deviceTransferMetadata({
    transactionId: "completed-transfer",
  })));
  await assertSucceeds(set(ref(aliceDb, completedPath + "/chunks/0"), "AQID"));
  await assertSucceeds(set(ref(aliceDb, completedPath + "/ready"), true));
  await assertSucceeds(set(ref(aliceDb, completedPath + "/acknowledgement"), "A".repeat(88)));
  await assertFails(set(ref(aliceDb, completedPath + "/acknowledgement"), "B".repeat(88)));
  await assertSucceeds(set(ref(aliceDb, completedPath), null));
});

test("only the account owner can remove a completed device transfer relay", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceTransferInbox/alice/device-alice-2/transfer-transaction-1";
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), path), {
      metadata: deviceTransferMetadata(),
      chunks: { "0": "AQID" },
      ready: true,
      acknowledgement: "A".repeat(88),
    });
  });
  await assertFails(set(ref(bobDb, path), null));
  await assertSucceeds(set(ref(aliceDb, path), null));
});
