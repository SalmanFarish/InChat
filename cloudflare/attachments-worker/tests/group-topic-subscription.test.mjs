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
