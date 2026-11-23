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
        alice_bob: {
          chatId: "alice_bob",
          type: "direct",
          participantA: "alice",
          participantB: "bob",
        },
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
          cryptoMembership: {
            epoch: 3,
            members: { alice: "admin", bob: "member" },
          },
          messages: {},
        },
      },
      deviceKeys: {
        alice: { "device-alice-1": publicDeviceBundle("device-alice-1") },
        bob: { "device-bob-1": publicDeviceBundle("device-bob-1") },
        mallory: { "device-mallory-1": publicDeviceBundle("device-mallory-1") },
      },
      userChats: {
        alice: {
          alice_bob: {
            chatId: "alice_bob",
            type: "direct",
            participantA: "alice",
            participantB: "bob",
          },
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

test("users can manage only their own device-scoped FCM token registrations", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const aliceTokenPath = "privateUsers/alice/olmDeviceFcmTokens/device-alice-1";
  const bobTokenPath = "privateUsers/bob/olmDeviceFcmTokens/device-bob-1";

  await assertSucceeds(set(ref(aliceDb, aliceTokenPath), "alice-device-token"));
  await assertSucceeds(get(ref(aliceDb, aliceTokenPath)));
  await assertFails(set(ref(aliceDb, bobTokenPath), "spoofed-token"));
  await assertFails(set(ref(bobDb, aliceTokenPath), "stolen-token"));
  await assertFails(
    set(ref(aliceDb, "privateUsers/alice/olmDeviceFcmTokens/device-invalid"), "x".repeat(4097)),
  );

  await assertSucceeds(remove(ref(aliceDb, aliceTokenPath)));
  const removed = await assertSucceeds(get(ref(aliceDb, aliceTokenPath)));
  assert.equal(removed.exists(), false);
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

test("direct inbox previews accept only generic encrypted or empty-state text", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    update(ref(aliceDb, "userChats/alice/dm"), {
      lastMessage: "This plaintext must not enter inbox metadata",
      lastTimestamp: 2000,
      lastSenderId: "bob",
    }),
  );

  await assertSucceeds(
    update(ref(aliceDb, "userChats/alice/dm"), {
      lastMessage: "Encrypted message",
      lastTimestamp: 2000,
      lastSenderId: "bob",
    }),
  );

  await assertSucceeds(
    update(ref(aliceDb, "userChats/alice/dm"), {
      lastMessage: "No messages yet",
      lastTimestamp: 3000,
      lastSenderId: "alice",
    }),
  );
});

test("group inbox previews cannot be written by group members", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    update(ref(aliceDb, "userChats/alice/group"), {
      lastMessage: "Plaintext group preview",
      lastTimestamp: 2000,
      lastSenderId: "alice",
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

test("direct plaintext messages, replies, and attachments are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(ref(aliceDb, "chats/dm/messages/plaintext-direct"), {
      id: "plaintext-direct",
      senderId: "alice",
      senderNickname: "alice",
      text: "Direct plaintext must use Olm instead",
      timestamp: 2000,
    }),
  );

  await assertFails(
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

test("group members cannot create plaintext messages in E2EE groups", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const plaintextMessage = {
    id: "plaintext-group-message",
    senderNickname: "alice",
    text: "This must never be stored as a group message",
    timestamp: 2000,
  };

  await assertFails(
    set(ref(aliceDb, "chats/group/messages/plaintext-admin"), {
      ...plaintextMessage,
      id: "plaintext-admin",
      senderId: "alice",
    }),
  );
  await assertFails(
    set(ref(bobDb, "chats/group/messages/plaintext-member"), {
      ...plaintextMessage,
      id: "plaintext-member",
      senderNickname: "bob",
      senderId: "bob",
    }),
  );
});

test("group admins can create only the fixed group-rename activity event", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();

  await assertSucceeds(
    set(ref(aliceDb, "chats/group/messages/group-rename-event"), {
      id: "group-rename-event",
      senderId: "alice",
      senderNickname: "SYSTEM",
      text: "Group renamed",
      timestamp: 2000,
      systemEvent: "group_renamed",
    }),
  );

  await assertFails(
    set(ref(aliceDb, "chats/group/messages/forged-plaintext-event"), {
      id: "forged-plaintext-event",
      senderId: "alice",
      senderNickname: "SYSTEM",
      text: "Plaintext smuggled through a system event",
      timestamp: 2000,
      systemEvent: "group_renamed",
    }),
  );

  await assertFails(
    set(ref(bobDb, "chats/group/messages/member-rename-event"), {
      id: "member-rename-event",
      senderId: "bob",
      senderNickname: "SYSTEM",
      text: "Group renamed",
      timestamp: 2000,
      systemEvent: "group_renamed",
    }),
  );
});

test("legacy group message text cannot be edited after E2EE enforcement", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "chats/group/messages/legacy-plaintext"), {
      id: "legacy-plaintext",
      senderId: "alice",
      senderNickname: "alice",
      text: "Existing legacy content",
      timestamp: Date.now() - 1000,
    });
  });

  await assertFails(
    update(ref(aliceDb, "chats/group/messages/legacy-plaintext"), {
      text: "Edited plaintext content",
      edited: true,
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

test("user profiles reject fields outside the supported profile schema", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(
    set(ref(aliceDb, "users/alice/isAdmin"), true),
  );

  await assertFails(
    set(ref(aliceDb, "users/alice/unboundedMetadata"), "x".repeat(300000)),
  );

  await assertFails(
    set(ref(aliceDb, "users/alice/featureFlags/isAdmin"), true),
  );

  // Existing, modeled profile fields remain writable for their owner.
  await assertSucceeds(
    set(ref(aliceDb, "users/alice/displayName"), "Alice Updated"),
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

  await assertSucceeds(set(ref(bobDb, "chatReadReceipts/dm/bob"), 2000));
  await assertFails(set(ref(bobDb, "chatReadReceipts/dm/alice"), 2000));
  await assertSucceeds(get(ref(aliceDb, "chatReadReceipts/dm/bob")));
  await assertSucceeds(set(ref(bobDb, "users/bob/readReceiptsVisible"), false));
  await assertFails(get(ref(aliceDb, "chatReadReceipts/dm/bob")));
  await assertFails(set(ref(bobDb, "chatReadReceipts/dm/bob"), 3000));
  await assertSucceeds(remove(ref(bobDb, "chatReadReceipts/dm/bob")));
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
    lastSeenAt: 1700000000000,
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

test("device-key bundles reject unknown fields, malformed shape and oversized key material", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const path = "deviceKeys/alice/device-alice-1";

  await assertFails(
    set(ref(aliceDb, path), {
      ...publicDeviceBundle(),
      injectedMetadata: true,
    }),
  );

  await assertFails(
    set(ref(aliceDb, path), {
      ...publicDeviceBundle(),
      ed25519IdentityKey: "x".repeat(129),
    }),
  );

  await assertFails(
    set(ref(aliceDb, path), {
      ...publicDeviceBundle(),
      fallbackKey: {
        ...publicDeviceBundle().fallbackKey,
        unexpected: "field",
      },
    }),
  );

  await assertFails(
    set(ref(aliceDb, path), {
      ...publicDeviceBundle(),
      oneTimeKeys: Array.from({ length: 21 }, (_, index) => ({
        keyKind: "one_time",
        keyId: "one-time-" + index,
        publicKey: "one-time-public-key",
        signature: "one-time-signature",
      })),
    }),
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



function directPreKeyClaim(claimantUid, peerUid) {
  return {
    claimantUid,
    conversationType: "direct",
    conversationId: [claimantUid, peerUid].sort().join("_"),
    conversationPeerUserId: peerUid,
  };
}

function groupPreKeyClaim(claimantUid, groupId = "group", membershipEpoch = 3) {
  return {
    claimantUid,
    conversationType: "group",
    conversationId: groupId,
    membershipEpoch,
  };
}

test("prekey claim metadata is visible only to its owner and claimant", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(set(ref(bobDb, path), directPreKeyClaim("bob", "alice")));
  await assertSucceeds(get(ref(aliceDb, path)));
  await assertSucceeds(get(ref(bobDb, path)));
  await assertFails(get(ref(malloryDb, path)));
});

test("authenticated clients can claim a one-time prekey exactly once", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  const [aliceResult, bobResult] = await Promise.allSettled([
    set(ref(aliceDb, path), directPreKeyClaim("alice", "bob")),
    set(ref(bobDb, path), directPreKeyClaim("bob", "alice")),
  ]);
  assert.equal(
    Number(aliceResult.status === "fulfilled") + Number(bobResult.status === "fulfilled"),
    1,
    "only one concurrent claimant should commit",
  );

  const winnerDb = aliceResult.status === "fulfilled" ? aliceDb : bobDb;
  const loserDb = aliceResult.status === "fulfilled" ? bobDb : aliceDb;
  await assertSucceeds(get(ref(winnerDb, path)));
  await assertFails(set(ref(loserDb, path), directPreKeyClaim(
    loserDb === aliceDb ? "alice" : "bob",
    loserDb === aliceDb ? "bob" : "alice",
  )));
});
test("a one-time prekey cannot be claimed after its device is revoked", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const claimPath = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(
    set(ref(aliceDb, "deviceKeys/alice/device-alice-1"), null),
  );
  await assertFails(set(ref(bobDb, claimPath), directPreKeyClaim("bob", "alice")));
});

test("unauthenticated clients cannot claim a one-time prekey", async () => {
  const unauthenticatedDb = testEnv.unauthenticatedContext().database();
  await assertFails(
    runTransaction(
      ref(unauthenticatedDb, "deviceKeyClaims/alice/device-alice-1/one-time-1"),
      (current) => current === null ? directPreKeyClaim("alice", "bob") : undefined,
    ),
  );
});

test("an unrelated authenticated account cannot exhaust a direct chat prekey", async () => {
  const malloryDb = testEnv.authenticatedContext("mallory").database();

  await assertFails(set(
    ref(malloryDb, "deviceKeyClaims/alice/device-alice-1/one-time-1"),
    directPreKeyClaim("mallory", "alice"),
  ));
  await assertFails(set(
    ref(malloryDb, "deviceKeyClaims/alice/device-alice-1/one-time-1"),
    {
      claimantUid: "mallory",
      conversationType: "direct",
      conversationId: "alice_bob",
      conversationPeerUserId: "bob",
    },
  ));
});

test("group prekey claims require both accounts in the current server-authored epoch", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(set(ref(bobDb, path), groupPreKeyClaim("bob")));
  await assertFails(set(ref(malloryDb, path), groupPreKeyClaim("mallory")));
});

test("group prekey claims reject stale epochs and removed members", async () => {
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertFails(set(ref(bobDb, path), groupPreKeyClaim("bob", "group", 2)));
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await update(ref(context.database(), "chats/group"), {
      members: { alice: "admin" },
      cryptoMembership: { epoch: 4, members: { alice: "admin" } },
    });
  });
  await assertFails(set(ref(bobDb, path), groupPreKeyClaim("bob", "group", 4)));
});

test("a claimed one-time prekey cannot be overwritten or deleted", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "deviceKeyClaims/alice/device-alice-1/one-time-1";

  await assertSucceeds(set(ref(bobDb, path), directPreKeyClaim("bob", "alice")));
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
  conversationType: "direct",
  conversationId: "alice_bob",
  conversationPeerUserId: "bob",
  envelope: JSON.stringify({
    encryptionVersion: 1,
    algorithm: "olm",
    senderDeviceId,
    recipientDeviceId: "device-bob-1",
    sessionId: "session-1",
    ciphertext: "AQID",
  }),
});

test("sender and recipient complete an encrypted Olm relay lifecycle, including an exact resend", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const deliveryId = "5".repeat(64);
  const inboxPath = `olmInbox/bob/device-bob-1/${deliveryId}`;
  const acknowledgementPath = `olmDeliveryAcks/alice/device-alice-1/${deliveryId}`;
  const inboxRecord = encryptedInboxRecord();
  const acknowledgement = {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-1",
    messageId: "message-encrypted-1",
    acknowledgedAt: 1_700_000_000_000,
  };

  // Sender uploads ciphertext to the recipient-device-scoped inbox.
  await assertSucceeds(set(ref(aliceDb, inboxPath), inboxRecord));
  await assertFails(get(ref(aliceDb, inboxPath)));
  await assertFails(get(ref(malloryDb, inboxPath)));

  // Only the addressed recipient can read the inbox and publish its ack.
  const firstInboxRead = await assertSucceeds(get(ref(bobDb, inboxPath)));
  assert.deepEqual(firstInboxRead.val(), inboxRecord);
  await assertSucceeds(set(ref(bobDb, acknowledgementPath), acknowledgement));

  // Recipient removes the inbox only after the acknowledgement write.
  await assertSucceeds(remove(ref(bobDb, inboxPath)));
  await assertFails(get(ref(bobDb, acknowledgementPath)));
  await assertFails(remove(ref(bobDb, acknowledgementPath)));

  // Sender consumes the metadata-only acknowledgement and deletes it.
  const firstAcknowledgementRead = await assertSucceeds(
    get(ref(aliceDb, acknowledgementPath)),
  );
  assert.deepEqual(firstAcknowledgementRead.val(), acknowledgement);
  await assertSucceeds(remove(ref(aliceDb, acknowledgementPath)));
  const removedAcknowledgement = await assertSucceeds(
    get(ref(aliceDb, acknowledgementPath)),
  );
  assert.equal(removedAcknowledgement.exists(), false);

  // Model sender recovery after its ack was consumed but its local outbox row
  // was not removed: it must be allowed to resend the exact immutable envelope
  // using the same deterministic delivery ID.
  await assertSucceeds(set(ref(aliceDb, inboxPath), inboxRecord));
  const resentInboxRead = await assertSucceeds(get(ref(bobDb, inboxPath)));
  assert.deepEqual(resentInboxRead.val(), inboxRecord);
  await assertSucceeds(set(ref(bobDb, acknowledgementPath), acknowledgement));
  await assertSucceeds(remove(ref(bobDb, inboxPath)));
  const resentAcknowledgementRead = await assertSucceeds(
    get(ref(aliceDb, acknowledgementPath)),
  );
  assert.deepEqual(resentAcknowledgementRead.val(), acknowledgement);
  await assertSucceeds(remove(ref(aliceDb, acknowledgementPath)));

  // A different account never gains access during either delivery attempt.
  await assertFails(get(ref(malloryDb, inboxPath)));
  await assertFails(get(ref(malloryDb, acknowledgementPath)));
});

test("only the addressed recipient device can acknowledge an existing encrypted delivery", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const deliveryId = "9".repeat(64);
  const inboxPath = `olmInbox/bob/device-bob-1/${deliveryId}`;
  const ackPath = `olmDeliveryAcks/alice/device-alice-1/${deliveryId}`;
  const acknowledgement = {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-1",
    messageId: "message-encrypted-1",
    acknowledgedAt: Date.now(),
  };

  await assertSucceeds(set(ref(aliceDb, inboxPath), encryptedInboxRecord()));
  await assertSucceeds(set(ref(bobDb, ackPath), acknowledgement));
  await assertFails(get(ref(bobDb, ackPath)));
  await assertFails(set(ref(bobDb, ackPath), null));
  await assertSucceeds(get(ref(aliceDb, ackPath)));
  await assertFails(set(ref(malloryDb, ackPath), acknowledgement));
  await assertFails(set(ref(malloryDb, ackPath), null));
  await assertSucceeds(set(ref(aliceDb, ackPath), null));
});

test("a recipient cannot acknowledge a missing or mismatched encrypted delivery", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const deliveryId = "8".repeat(64);
  const ackPath = `olmDeliveryAcks/alice/device-alice-1/${deliveryId}`;
  const acknowledgement = {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-1",
    messageId: "message-encrypted-1",
    acknowledgedAt: Date.now(),
  };

  await assertFails(set(ref(bobDb, ackPath), acknowledgement));
  await assertSucceeds(set(
    ref(aliceDb, `olmInbox/bob/device-bob-1/${deliveryId}`),
    encryptedInboxRecord(),
  ));
  await assertFails(set(ref(bobDb, ackPath), {
    ...acknowledgement,
    messageId: "different-message",
  }));
});

test("group relay writes require the current server-authored membership epoch and both members", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const { conversationPeerUserId, ...groupBase } = encryptedInboxRecord();
  const groupRecord = { ...groupBase, conversationType: "group", conversationId: "group", groupId: "group", membershipEpoch: 3 };
  const path = "olmInbox/bob/device-bob-1/" + "7".repeat(64);
  await assertSucceeds(set(ref(aliceDb, path), groupRecord));
  await assertSucceeds(set(ref(aliceDb, path), groupRecord));
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "8".repeat(64)), { ...groupRecord, membershipEpoch: 2 }));
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "9".repeat(64)), { ...groupRecord, groupId: "missing-group" }));
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  await assertFails(set(ref(malloryDb, "olmInbox/bob/device-bob-1/" + "a".repeat(64)), {
    ...groupRecord, senderUid: "mallory", senderDeviceId: "device-mallory-1",
  }));
  await assertFails(set(ref(aliceDb, "olmInbox/mallory/device-mallory-1/" + "b".repeat(64)), {
    ...groupRecord,
  }));
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "chats/group/cryptoMembership/epoch"), 4);
  });
  await assertFails(set(ref(aliceDb, path), groupRecord));
});

test("group relay writes fail closed when legacy membership diverges from the crypto snapshot", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const { conversationPeerUserId, ...groupBase } = encryptedInboxRecord();
  const groupRecord = {
    ...groupBase,
    conversationType: "group",
    conversationId: "group",
    groupId: "group",
    membershipEpoch: 3,
  };

  // A removed sender must not be authorized by a stale crypto snapshot.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "chats/group/members"), {
      bob: "member",
    });
  });
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "c".repeat(64)),
    groupRecord,
  ));

  // A removed recipient must not receive a relay via the stale snapshot.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "chats/group/members"), {
      alice: "admin",
    });
  });
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "d".repeat(64)),
    groupRecord,
  ));

  // Matching identities with a changed role are also an inconsistent snapshot.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "chats/group/members"), {
      alice: "member",
      bob: "member",
    });
  });
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "e".repeat(64)),
    groupRecord,
  ));
});

test("relay scope is mandatory and direct scope must match the canonical chat participants", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const base = encryptedInboxRecord();
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "f".repeat(64)), {
    senderUid: base.senderUid, senderDeviceId: base.senderDeviceId,
    messageId: base.messageId, envelope: base.envelope,
  }));
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "0".repeat(64)), {
    ...base, conversationId: "alice_mallory",
  }));
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "3".repeat(64)), {
    ...base, conversationId: "custom-alice-bob-chat",
  }));
  await assertFails(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "1".repeat(64)), {
    ...base, conversationType: "group",
  }));
  await assertSucceeds(set(ref(aliceDb, "olmInbox/bob/device-bob-1/" + "2".repeat(64)), base));
  await assertSucceeds(set(ref(aliceDb, "olmInbox/alice/device-alice-1/" + "4".repeat(64)), base));

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
  await assertFails(set(ref(aliceDb, path), null));
  await assertFails(set(ref(malloryDb, path), null));
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


test("encrypted inbox IDs and records reject malformed or forged metadata", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();

  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/not-a-delivery-hash"),
    encryptedInboxRecord(),
  ));
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "1".repeat(64)),
    {
      ...encryptedInboxRecord(),
      injectedMetadata: true,
    },
  ));
  await assertFails(set(
    ref(aliceDb, "olmInbox/bob/device-bob-1/" + "2".repeat(64)),
    encryptedInboxRecord("mallory", "device-alice-1"),
  ));
});

test("recipient acknowledgements can be retried but cannot change delivery identity", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const deliveryId = "3".repeat(64);
  const inboxPath = `olmInbox/bob/device-bob-1/${deliveryId}`;
  const ackPath = `olmDeliveryAcks/alice/device-alice-1/${deliveryId}`;
  const acknowledgement = {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-1",
    messageId: "message-encrypted-1",
    acknowledgedAt: Date.now(),
  };

  await assertSucceeds(set(ref(aliceDb, inboxPath), encryptedInboxRecord()));
  await assertSucceeds(set(ref(bobDb, ackPath), acknowledgement));

  // A retry may refresh only its timestamp while retaining the same identity.
  await assertSucceeds(set(ref(bobDb, ackPath), {
    ...acknowledgement,
    acknowledgedAt: acknowledgement.acknowledgedAt + 1,
  }));
  await assertFails(set(ref(bobDb, ackPath), {
    ...acknowledgement,
    messageId: "different-message",
  }));
  await assertFails(set(ref(bobDb, ackPath), {
    ...acknowledgement,
    recipientUid: "mallory",
  }));
  await assertFails(set(ref(bobDb, ackPath), {
    ...acknowledgement,
    injectedMetadata: true,
  }));
  await assertFails(set(ref(aliceDb, ackPath), {
    ...acknowledgement,
    acknowledgedAt: acknowledgement.acknowledgedAt + 2,
  }));
  await assertFails(set(ref(malloryDb, ackPath), null));
  await assertFails(set(ref(bobDb, ackPath), null));
  await assertSucceeds(set(ref(aliceDb, ackPath), null));
});

test("acknowledgements must name the exact recipient device addressed by the inbox path", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const deliveryId = "4".repeat(64);
  const inboxPath = `olmInbox/bob/device-bob-1/${deliveryId}`;
  const ackPath = `olmDeliveryAcks/alice/device-alice-1/${deliveryId}`;

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(
      ref(context.database(), "deviceKeys/bob/device-bob-2"),
      publicDeviceBundle("device-bob-2"),
    );
  });
  await assertSucceeds(set(ref(aliceDb, inboxPath), encryptedInboxRecord()));
  await assertFails(set(ref(bobDb, ackPath), {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-2",
    messageId: "message-encrypted-1",
    acknowledgedAt: Date.now(),
  }));
  await assertSucceeds(set(ref(bobDb, ackPath), {
    recipientUid: "bob",
    recipientDeviceId: "device-bob-1",
    messageId: "message-encrypted-1",
    acknowledgedAt: Date.now(),
  }));
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
  await assertFails(set(ref(aliceDb, path), null));
  await assertSucceeds(get(ref(bobDb, path)));
  await assertSucceeds(set(ref(bobDb, path), null));
});

test("SAS sender cannot spoof its account identity or add unrecognized fields", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const spoofedPath = "sasVerificationInbox/bob/device-bob-1/" + "9".repeat(64);
  const extraFieldPath = "sasVerificationInbox/bob/device-bob-1/" + "a".repeat(64);
  const record = sasVerificationRecord();

  await assertFails(set(ref(aliceDb, spoofedPath), {
    ...record,
    senderUid: "mallory",
  }));
  await assertFails(set(ref(aliceDb, extraFieldPath), {
    ...record,
    unexpected: "must-not-be-stored",
  }));
});

test("SAS relay rejects timestamps outside the allowed clock and lifetime bounds", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const now = Date.now();

  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "b".repeat(64)),
    sasVerificationRecord({
      createdAtMillis: now + 180001,
      expiresAtMillis: now + 300000,
    }),
  ));
  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "c".repeat(64)),
    sasVerificationRecord({
      createdAtMillis: now,
      expiresAtMillis: now + 600001,
    }),
  ));
});

test("a sender cannot delete a delivered SAS message", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const path = "sasVerificationInbox/bob/device-bob-1/" + "d".repeat(64);

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), path), sasVerificationRecord());
  });
  await assertFails(set(ref(aliceDb, path), null));
  await assertSucceeds(set(ref(bobDb, path), null));
});

test("a block in the reverse direction also prevents SAS message creation", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database(), "blockedUsers/bob/alice"), {
      uid: "alice", username: "alice", blockedAt: 1000,
    });
  });

  await assertFails(set(
    ref(aliceDb, "sasVerificationInbox/bob/device-bob-1/" + "e".repeat(64)),
    sasVerificationRecord(),
  ));
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


test("encrypted push dispatch receipts are server-only", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").database();
  const bobDb = testEnv.authenticatedContext("bob").database();
  const malloryDb = testEnv.authenticatedContext("mallory").database();
  const path = "olmPushDispatch/bob/device-bob-1/" + "a".repeat(64);

  for (const database of [aliceDb, bobDb, malloryDb]) {
    await assertFails(get(ref(database, path)));
    await assertFails(set(ref(database, path), {
      status: "sent",
      deliveryId: "a".repeat(64),
    }));
  }
});
