const ALLOWED_TYPES = new Set(["chat_message", "group_message"]);

function requiredText(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError("A non-empty " + label + " is required for addressed message pushes.");
  }
  return value;
}

/**
 * Builds data-only FCM fields for a push targeted at one verified account.
 * Large-group topic broadcasts deliberately use their separate payload path.
 */
export function buildAddressedMessagePushData({
  type,
  chatId,
  messageId,
  senderId,
  recipientUserId,
  senderName,
  groupName,
  message,
}) {
  if (!ALLOWED_TYPES.has(type)) {
    throw new TypeError("Unsupported addressed message push type.");
  }

  const payload = {
    type,
    chatId: requiredText(chatId, "chat ID"),
    messageId: requiredText(messageId, "message ID"),
    senderId: requiredText(senderId, "sender UID"),
    recipientUserId: requiredText(recipientUserId, "recipient UID"),
    senderName: requiredText(senderName, "sender name"),
    message: requiredText(message, "message text"),
  };

  if (type === "group_message") {
    payload.groupName = requiredText(groupName, "group name");
  }

  return payload;
}
