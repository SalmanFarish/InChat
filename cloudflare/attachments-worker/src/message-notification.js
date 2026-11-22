const ALLOWED_TYPES = new Set(["chat_message", "group_message"]);

/**
 * Returns current group recipients only when the sender is still a member.
 * A null result means the membership snapshot is not safe for notification.
 */
export function currentGroupNotificationRecipients(chat, senderUid) {
  if (
    !chat ||
    typeof chat !== "object" ||
    chat.type !== "group" ||
    !chat.members ||
    typeof chat.members !== "object" ||
    Array.isArray(chat.members) ||
    typeof senderUid !== "string" ||
    senderUid.trim() === "" ||
    !Object.prototype.hasOwnProperty.call(chat.members, senderUid)
  ) {
    return null;
  }

  return Object.keys(chat.members)
    .filter((memberId) => memberId !== senderUid && memberId.trim() !== "")
    .sort();
}

/**
 * A direct fan-out is safe only when every current recipient has a usable
 * token. Missing or blank registry values must fall back to group topics.
 */
export function hasCompleteGroupNotificationRegistry(memberIds, registry) {
  if (!Array.isArray(memberIds) || !registry || typeof registry !== "object") {
    return false;
  }
  return memberIds.every((memberId) =>
    typeof registry[memberId] === "string" && registry[memberId].trim() !== ""
  );
}

/**
 * Returns a compact, one-way account tag for membership checks on broadcast
 * topic pushes. Tags reveal no raw Firebase UIDs and are deterministic per UID.
 */
export async function recipientAccountTag(uid) {
  if (typeof uid !== "string" || uid.trim() === "") {
    throw new TypeError("A non-empty account UID is required.");
  }
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(uid.trim()),
  );
  return Array.from(new Uint8Array(digest).slice(0, 16))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Creates a stable, deduplicated recipient tag list for a large-group topic.
 * The chat contains at most 50 members, so the compact tags fit FCM data limits.
 */
export async function buildRecipientAccountTags(memberUids) {
  if (!Array.isArray(memberUids) || memberUids.length > 50) {
    throw new TypeError("Group member UID list is invalid.");
  }
  if (memberUids.some((uid) =>
    typeof uid !== "string" || uid.trim() === ""
  )) {
    throw new TypeError("Group member UID list contains an invalid account ID.");
  }
  const normalizedUids = [...new Set(
    memberUids.map((uid) => uid.trim()),
  )].sort();
  const tags = await Promise.all(normalizedUids.map(recipientAccountTag));
  return tags.sort().join(",");
}

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
  };

  return payload;
}
