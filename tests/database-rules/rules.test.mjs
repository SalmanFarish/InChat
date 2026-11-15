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

test("a non-participant cannot read a direct conversation", async () => {
  const malloryDb = testEnv.authenticatedContext("mallory").database();

  await assertFails(get(ref(malloryDb, "chats/dm")));
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
