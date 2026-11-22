import test from "node:test";
import assert from "node:assert/strict";
import { buildAddressedMessagePushData } from "../src/message-notification.js";

test("direct message push always identifies its recipient account", () => {
  assert.deepEqual(
    buildAddressedMessagePushData({
      type: "chat_message",
      chatId: "alice_bob",
      messageId: "message-1",
      senderId: "alice",
      recipientUserId: "bob",
      senderName: "Alice",
      message: "Hello",
    }),
    {
      type: "chat_message",
      chatId: "alice_bob",
      messageId: "message-1",
      senderId: "alice",
      recipientUserId: "bob",
      senderName: "Alice",
      message: "Hello",
    },
  );
});

test("small-group direct delivery identifies the individual recipient", () => {
  const payload = buildAddressedMessagePushData({
    type: "group_message",
    chatId: "group-1",
    messageId: "message-2",
    senderId: "alice",
    recipientUserId: "bob",
    senderName: "Alice",
    groupName: "Friends",
    message: "Hello everyone",
  });

  assert.equal(payload.recipientUserId, "bob");
  assert.equal(payload.groupName, "Friends");
});

test("addressed push builder rejects missing recipient and malformed routing values", () => {
  assert.throws(
    () => buildAddressedMessagePushData({
      type: "chat_message",
      chatId: "alice_bob",
      messageId: "message-1",
      senderId: "alice",
      recipientUserId: "",
      senderName: "Alice",
      message: "Hello",
    }),
    /recipient UID/,
  );

  assert.throws(
    () => buildAddressedMessagePushData({
      type: "chat_message",
      chatId: "alice_bob",
      messageId: "",
      senderId: "alice",
      recipientUserId: "bob",
      senderName: "Alice",
      message: "Hello",
    }),
    /message ID/,
  );
});

test("addressed push builder rejects unsupported types", () => {
  assert.throws(
    () => buildAddressedMessagePushData({
      type: "group_topic",
      chatId: "group-1",
      messageId: "message-2",
      senderId: "alice",
      recipientUserId: "bob",
      senderName: "Alice",
      groupName: "Friends",
      message: "Hello",
    }),
    /Unsupported/,
  );
});


test("large-group recipient tags are stable, deduplicated, compact, and do not expose UIDs", async () => {
  const { buildRecipientAccountTags } = await import("../src/message-notification.js");
  const first = await buildRecipientAccountTags(["bob", "alice", "bob"]);
  const second = await buildRecipientAccountTags(["alice", "bob"]);

  assert.equal(first, second);
  const tags = first.split(",");
  assert.equal(tags.length, 2);
  assert.ok(tags.every((tag) => /^[a-f0-9]{32}$/.test(tag)));
  assert.equal(first.includes("alice"), false);
  assert.equal(first.includes("bob"), false);
});

test("large-group recipient tag builder rejects malformed member IDs instead of silently omitting them", async () => {
  const { buildRecipientAccountTags } = await import("../src/message-notification.js");
  await assert.rejects(
    () => buildRecipientAccountTags(["alice", ""]),
    /invalid account ID/,
  );
  await assert.rejects(
    () => buildRecipientAccountTags(["alice", null]),
    /invalid account ID/,
  );
});

test("large-group recipient tag builder rejects invalid or oversized membership lists", async () => {
  const { buildRecipientAccountTags } = await import("../src/message-notification.js");
  await assert.rejects(
    () => buildRecipientAccountTags(Array.from({ length: 51 }, (_, index) => "user-" + index)),
    /invalid/,
  );
});

test("fresh group membership yields only current non-sender recipients", async () => {
  const { currentGroupNotificationRecipients } = await import("../src/message-notification.js");
  assert.deepEqual(
    currentGroupNotificationRecipients({
      type: "group",
      members: { bob: true, alice: true, carol: true },
    }, "alice"),
    ["bob", "carol"],
  );
});

test("fresh group membership rejects a sender who has already been removed", async () => {
  const { currentGroupNotificationRecipients } = await import("../src/message-notification.js");
  assert.equal(
    currentGroupNotificationRecipients({
      type: "group",
      members: { bob: true, carol: true },
    }, "alice"),
    null,
  );
});

test("fresh group membership rejects malformed group snapshots", async () => {
  const { currentGroupNotificationRecipients } = await import("../src/message-notification.js");
  assert.equal(currentGroupNotificationRecipients(null, "alice"), null);
  assert.equal(currentGroupNotificationRecipients({ type: "direct", members: { alice: true } }, "alice"), null);
  assert.equal(currentGroupNotificationRecipients({ type: "group", members: [] }, "alice"), null);
});

test("group direct fan-out requires a non-empty token for every current recipient", async () => {
  const { hasCompleteGroupNotificationRegistry } = await import("../src/message-notification.js");
  assert.equal(
    hasCompleteGroupNotificationRegistry(["bob", "carol"], { bob: "token-b", carol: "token-c" }),
    true,
  );
  assert.equal(
    hasCompleteGroupNotificationRegistry(["bob", "carol"], { bob: "token-b" }),
    false,
  );
  assert.equal(
    hasCompleteGroupNotificationRegistry(["bob", "carol"], { bob: "token-b", carol: "   " }),
    false,
  );
  assert.equal(hasCompleteGroupNotificationRegistry(["bob"], null), false);
});
