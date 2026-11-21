import test from "node:test";
import assert from "node:assert/strict";
import { isCurrentGroupTopicSubscription } from "../src/group-topic-subscription.js";

const validChat = {
  type: "group",
  members: {
    alice: "admin",
    bob: "member",
  },
};

test("keeps a topic subscription when membership and FCM token are unchanged", () => {
  assert.equal(
    isCurrentGroupTopicSubscription({
      chat: validChat,
      uid: "bob",
      requestedToken: "fcm-token",
      currentToken: "fcm-token",
    }),
    true,
  );
});

test("rejects a topic subscription after the user was removed", () => {
  assert.equal(
    isCurrentGroupTopicSubscription({
      chat: { ...validChat, members: { alice: "admin" } },
      uid: "bob",
      requestedToken: "fcm-token",
      currentToken: "fcm-token",
    }),
    false,
  );
});

test("rejects a topic subscription after the FCM token rotates", () => {
  assert.equal(
    isCurrentGroupTopicSubscription({
      chat: validChat,
      uid: "bob",
      requestedToken: "old-token",
      currentToken: "new-token",
    }),
    false,
  );
});

test("rejects missing, malformed, or deleted group membership state", () => {
  for (const chat of [
    null,
    { type: "direct", members: validChat.members },
    { type: "group", members: null },
    { type: "group", members: { bob: "" } },
  ]) {
    assert.equal(
      isCurrentGroupTopicSubscription({
        chat,
        uid: "bob",
        requestedToken: "fcm-token",
        currentToken: "fcm-token",
      }),
      false,
    );
  }
});

test("returns the old group token only when the FCM registration changed", async () => {
  const { previousGroupNotificationToken } = await import("../src/group-topic-subscription.js");

  assert.equal(previousGroupNotificationToken("old-token", "new-token"), "old-token");
  assert.equal(previousGroupNotificationToken("same-token", "same-token"), "");
  assert.equal(previousGroupNotificationToken(" ", "new-token"), "");
  assert.equal(previousGroupNotificationToken("old-token", ""), "old-token");
});

test("group removal cleanup attempts both current and previously registered tokens once", async () => {
  const { cleanupGroupNotificationTokens } = await import("../src/group-topic-subscription.js");
  const attempts = [];
  const result = await cleanupGroupNotificationTokens({
    previousToken: " old-token ",
    currentToken: "new-token",
    unsubscribe: async (token) => attempts.push(token),
  });

  assert.deepEqual(attempts.sort(), ["new-token", "old-token"]);
  assert.deepEqual(result.tokens.sort(), ["new-token", "old-token"]);
  assert.equal(result.complete, true);
  assert.deepEqual(result.failedTokens, []);
});

test("group removal cleanup preserves retry state when an unsubscribe fails", async () => {
  const { cleanupGroupNotificationTokens } = await import("../src/group-topic-subscription.js");
  const result = await cleanupGroupNotificationTokens({
    previousToken: "old-token",
    currentToken: "new-token",
    unsubscribe: async (token) => {
      if (token === "old-token") throw new Error("temporary FCM failure");
    },
  });

  assert.equal(result.complete, false);
  assert.deepEqual(result.failedTokens, ["old-token"]);
  assert.deepEqual(result.tokens.sort(), ["new-token", "old-token"]);
});

test("group removal cleanup safely handles absent tokens", async () => {
  const { cleanupGroupNotificationTokens } = await import("../src/group-topic-subscription.js");
  let called = false;
  const result = await cleanupGroupNotificationTokens({
    previousToken: null,
    currentToken: "",
    unsubscribe: async () => { called = true; },
  });

  assert.equal(called, false);
  assert.equal(result.complete, true);
  assert.deepEqual(result.tokens, []);
});
