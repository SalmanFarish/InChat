const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const MESSAGE_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DELIVERY_PATTERN = /^[A-Fa-f0-9]{64}$/;

/**
 * Reads only immutable routing/expiry metadata for a committed delivery.
 * A missing binding or row returns null so legacy Firebase deliveries can
 * continue through their original relay during the migration window.
 */
export async function readOlmMailboxDeliveryMetadata(
  env,
  recipientUid,
  recipientDeviceId,
  deliveryId,
) {
  if (!UID_PATTERN.test(recipientUid || "") ||
      !DEVICE_PATTERN.test(recipientDeviceId || "") ||
      !DELIVERY_PATTERN.test(deliveryId || "")) {
    throw new TypeError("Encrypted mailbox delivery lookup identity is invalid.");
  }
  const namespace = env?.OLM_MAILBOX;
  if (!namespace ||
      typeof namespace.idFromName !== "function" ||
      typeof namespace.get !== "function") {
    return null;
  }
  const stub = namespace.get(namespace.idFromName(recipientUid + ":" + recipientDeviceId));
  if (!stub || typeof stub.fetch !== "function") {
    throw new Error("Encrypted mailbox delivery lookup is unavailable.");
  }
  const response = await stub.fetch(new Request(
    "https://olm-mailbox.internal/delivery/" + deliveryId.toLowerCase(),
  ));
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error("Could not read encrypted mailbox delivery metadata (" + response.status + ").");
  }
  const payload = await response.json();
  if (!payload || typeof payload !== "object" ||
      !payload.delivery || typeof payload.delivery !== "object") {
    throw new Error("Encrypted mailbox delivery metadata response is malformed.");
  }
  return payload.delivery;
}

/** Compare Worker-owned mailbox identity with a notification/preview request. */
export function isValidOlmMailboxDeliveryMetadata(record, expected, now = Date.now()) {
  if (!record || typeof record !== "object" || Array.isArray(record) ||
      !DELIVERY_PATTERN.test(record.deliveryId || "") ||
      !MESSAGE_PATTERN.test(record.messageId || "") ||
      !UID_PATTERN.test(record.senderUid || "") ||
      !UID_PATTERN.test(record.recipientUid || "") ||
      !DEVICE_PATTERN.test(record.senderDeviceId || "") ||
      !DEVICE_PATTERN.test(record.recipientDeviceId || "") ||
      !/^[a-f0-9]{64}$/.test(record.envelopeSha256 || "") ||
      !Number.isSafeInteger(record.expiresAt) || record.expiresAt <= now ||
      !["pending", "acknowledged", "acked"].includes(record.status)) {
    return false;
  }

  const expectedGroup = typeof expected.groupId === "string";
  return record.deliveryId === expected.deliveryId.toLowerCase() &&
    record.senderUid === expected.senderUid &&
    record.senderDeviceId === expected.senderDeviceId &&
    record.recipientUid === expected.recipientUid &&
    record.recipientDeviceId === expected.recipientDeviceId &&
    record.messageId === expected.messageId &&
    record.conversationType === (expectedGroup ? "group" : "direct") &&
    record.conversationId === (expectedGroup ? expected.groupId : expected.chatId) &&
    (expectedGroup
      ? record.groupId === expected.groupId &&
        record.membershipEpoch === expected.membershipEpoch &&
        record.conversationPeerUserId === null
      : record.groupId === null &&
        record.membershipEpoch === null &&
        record.conversationPeerUserId === expected.conversationPeerUserId);
}
