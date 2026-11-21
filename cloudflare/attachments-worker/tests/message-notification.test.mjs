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
