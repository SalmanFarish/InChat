import { isPublishedDevice, isValidEnvelopeRecord } from "./olm-push.js";

const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const CHAT_PATTERN = /^[A-Za-z0-9_-]{3,257}$/;
const MESSAGE_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const RECENT_MESSAGE_LIMIT = 128;

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function result(status, payload) {
  return { status, payload };
}

export function databaseUrl(env, path) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  const encodedPath = path.split("/").map(encodeURIComponent).join("/");
  return base + "/" + encodedPath + ".json";
}

async export function readWithEtag(env, adminToken, path, fetchImpl) {
  const response = await fetchImpl(databaseUrl(env, path), {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
      Accept: "application/json",
    },
  });
  if (!response.ok) {
    throw new Error("Could not read encrypted direct inbox (" + response.status + ").");
  }
  return {
    value: await response.json(),
    etag: response.headers.get("ETag") || "null_etag",
  };
}

async export function putWithEtag(env, adminToken, path, etag, value, fetchImpl) {
  const response = await fetchImpl(databaseUrl(env, path), {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify(value),
  });
  if (response.status === 412) return false;
  if (!response.ok) {
    throw new Error("Could not update encrypted direct inbox (" + response.status + ").");
  }
  return true;
}

function username(profile) {
  return typeof profile?.username === "string" && profile.username.trim() ?
    profile.username.trim().slice(0, 20) :
    "User";
}

function isNewerMessage(timestamp, messageId, inbox) {
  const currentTimestamp = Number(inbox?.lastTimestamp || 0);
  if (!Number.isFinite(currentTimestamp) || timestamp > currentTimestamp) return true;
  if (timestamp < currentTimestamp) return false;
  return messageId.localeCompare(String(inbox?.lastMessageId || "")) > 0;
}

async function updateSenderInbox({
  env,
  adminToken,
  uid,
  chatId,
  peerUid,
  peerUsername,
  senderNickname,
  messageId,
  sentAtMillis,
  fetchImpl,
}) {
  const path = "userChats/" + uid + "/" + chatId;
  for (let attempt = 0; attempt < 4; attempt++) {
    const snapshot = await readWithEtag(env, adminToken, path, fetchImpl);
    const existing = isRecord(snapshot.value) ? snapshot.value : {};
    const next = {
      ...existing,
      chatId,
      chatType: "direct",
      otherUserId: peerUid,
      otherUsername: peerUsername,
      ...(typeof existing.unreadCount === "number" ? {} : { unreadCount: 0 }),
    };
    if (isNewerMessage(sentAtMillis, messageId, existing)) {
      next.lastMessage = "Encrypted message";
      next.lastTimestamp = sentAtMillis;
      next.lastSenderId = uid;
      next.lastSenderNickname = senderNickname;
      next.lastMessageId = messageId;
    }
    if (await putWithEtag(env, adminToken, path, snapshot.etag, next, fetchImpl)) {
      return true;
    }
  }
  return false;
}

async function updateRecipientInbox({
  env,
  adminToken,
  recipientUid,
  chatId,
  senderUid,
  senderNickname,
  recipientUsername,
  messageId,
  sentAtMillis,
  readRecipientLastReadTimestamp,
  fetchImpl,
}) {
  const path = "userChats/" + recipientUid + "/" + chatId;
  for (let attempt = 0; attempt < 4; attempt++) {
    const [snapshot, readValue] = await Promise.all([
      readWithEtag(env, adminToken, path, fetchImpl),
      readRecipientLastReadTimestamp(),
    ]);
    const recipientLastReadTimestamp = Number(readValue || 0);
    const existing = isRecord(snapshot.value) ? snapshot.value : {};
    const recent = Array.isArray(existing.recentEncryptedMessageIds) ?
      existing.recentEncryptedMessageIds.filter((value) =>
        typeof value === "string" && MESSAGE_PATTERN.test(value)) :
      [];
    const alreadyProcessed = recent.includes(messageId);
    const next = {
      ...existing,
      chatId,
      chatType: "direct",
      otherUserId: senderUid,
      otherUsername: senderNickname,
      recentEncryptedMessageIds: alreadyProcessed ?
        recent :
        [...recent, messageId].slice(-RECENT_MESSAGE_LIMIT),
      ...(typeof existing.unreadCount === "number" ?
        {} :
        { unreadCount: 0 }),
    };

    if (!alreadyProcessed &&
        (!Number.isFinite(recipientLastReadTimestamp) ||
          recipientLastReadTimestamp < sentAtMillis)) {
      const unreadCount = Number(next.unreadCount);
      next.unreadCount = Number.isFinite(unreadCount) ?
        Math.max(0, Math.trunc(unreadCount)) + 1 :
        1;
    }

    if (isNewerMessage(sentAtMillis, messageId, existing)) {
      next.lastMessage = "Encrypted message";
      next.lastTimestamp = sentAtMillis;
      next.lastSenderId = senderUid;
      next.lastSenderNickname = senderNickname;
      next.lastMessageId = messageId;
    }

    if (await putWithEtag(env, adminToken, path, snapshot.etag, next, fetchImpl)) {
      return true;
    }
    // A concurrent direct message changed the inbox. Re-read it and retry;
    // the bounded message-ID ledger keeps unread increments idempotent.
  }
  return false;
}


async function clearRecipientUnreadIfRead({
  env,
  adminToken,
  recipientUid,
  chatId,
  readValue,
  fetchImpl,
}) {
  const lastReadTimestamp = Number(readValue || 0);
  if (!Number.isFinite(lastReadTimestamp) || lastReadTimestamp <= 0) return;

  const path = "userChats/" + recipientUid + "/" + chatId;
  const snapshot = await readWithEtag(env, adminToken, path, fetchImpl);
  const inbox = isRecord(snapshot.value) ? snapshot.value : null;
  const latestTimestamp = Number(inbox?.lastTimestamp || 0);
  const unreadCount = Number(inbox?.unreadCount || 0);
  if (!inbox || !Number.isFinite(latestTimestamp) ||
      lastReadTimestamp < latestTimestamp || unreadCount <= 0) {
    return;
  }

  // ETag protects a newer concurrent encrypted or legacy message from
  // having its unread count cleared by this delayed read-cursor repair.
  await putWithEtag(
    env,
    adminToken,
    path,
    snapshot.etag,
    { ...inbox, unreadCount: 0 },
    fetchImpl,
  );
}

/**
 * Rebuild direct inbox previews from authenticated ciphertext metadata only.
 * No message body, attachment name, or decrypted content is accepted.
 */
export async function syncEncryptedDirectPreview({
  env,
  uid,
  body,
  adminToken,
  adminDatabaseRequest,
  fetchImpl = fetch,
  now = Date.now(),
}) {
  if (!isRecord(body)) {
    return result(400, { error: "Invalid encrypted direct preview request." });
  }
  const expectedFields = [
    "chatId",
    "deliveryId",
    "messageId",
    "recipientDeviceId",
    "senderDeviceId",
    "sentAtMillis",
  ].sort();
  const actualFields = Object.keys(body).sort();
  if (actualFields.length !== expectedFields.length ||
      actualFields.some((field, index) => field !== expectedFields[index])) {
    return result(400, { error: "Encrypted direct preview request has missing or unknown fields." });
  }
  if (!UID_PATTERN.test(uid || "") ||
      typeof body.chatId !== "string" || !CHAT_PATTERN.test(body.chatId) ||
      typeof body.messageId !== "string" || !MESSAGE_PATTERN.test(body.messageId) ||
      typeof body.deliveryId !== "string" || !/^[A-Fa-f0-9]{64}$/.test(body.deliveryId) ||
      typeof body.senderDeviceId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.senderDeviceId) ||
      typeof body.recipientDeviceId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.recipientDeviceId) ||
      !Number.isSafeInteger(body.sentAtMillis) || body.sentAtMillis <= 0 ||
      body.sentAtMillis > now + 5 * 60_000) {
    return result(400, { error: "Encrypted direct preview request contains invalid metadata." });
  }
  if (typeof adminToken !== "string" || !adminToken.trim() ||
      typeof adminDatabaseRequest !== "function") {
    return result(503, { error: "Firebase Admin access is unavailable.", retryable: true });
  }

  const chat = await adminDatabaseRequest(
    env,
    adminToken,
    "chats/" + body.chatId,
  );
  if (!isRecord(chat) || chat.type !== "direct") {
    return result(404, { error: "Direct conversation not found." });
  }
  const participantA = chat.participantA;
  const participantB = chat.participantB;
  if (!UID_PATTERN.test(participantA || "") ||
      !UID_PATTERN.test(participantB || "") ||
      participantA === participantB ||
      (participantA !== uid && participantB !== uid) ||
      [participantA, participantB].sort().join("_") !== body.chatId) {
    return result(403, { error: "Authenticated user is not a participant in this direct conversation." });
  }

  const peerUid = participantA === uid ? participantB : participantA;
  const [senderBlockedPeer, peerBlockedSender] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "blockedUsers/" + uid + "/" + peerUid),
    adminDatabaseRequest(env, adminToken, "blockedUsers/" + peerUid + "/" + uid),
  ]);
  if (senderBlockedPeer || peerBlockedSender) {
    return result(200, { success: true, previewUpdated: false, reason: "blocked" });
  }

  // A caller may update previews only after its exact encrypted delivery has
  // reached the recipient's relay path. The server never trusts a client-only
  // message ID/timestamp as proof that a ciphertext send actually occurred.
  const [senderDevice, recipientDevice, relayRecord] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "deviceKeys/" + uid + "/" + body.senderDeviceId),
    adminDatabaseRequest(env, adminToken, "deviceKeys/" + peerUid + "/" + body.recipientDeviceId),
    adminDatabaseRequest(
      env,
      adminToken,
      "olmInbox/" + peerUid + "/" + body.recipientDeviceId + "/" + body.deliveryId.toLowerCase(),
    ),
  ]);
  if (!isPublishedDevice(senderDevice, body.senderDeviceId) ||
      !isPublishedDevice(recipientDevice, body.recipientDeviceId)) {
    return result(404, { error: "Sender or recipient device is no longer published." });
  }
  if (!isRecord(relayRecord)) {
    return result(503, { error: "Encrypted relay record is missing.", retryable: true });
  }
  if (!isValidEnvelopeRecord(relayRecord, {
    senderUid: uid,
    senderDeviceId: body.senderDeviceId,
    recipientDeviceId: body.recipientDeviceId,
    messageId: body.messageId,
    chatId: body.chatId,
    conversationPeerUserId: peerUid,
  })) {
    return result(409, { error: "Encrypted relay record does not match the authenticated preview request." });
  }

  const [senderProfile, recipientProfile] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "users/" + uid),
    adminDatabaseRequest(env, adminToken, "users/" + peerUid),
  ]);
  const senderNickname = username(senderProfile);
  const recipientUsername = username(recipientProfile);

  const recipientUpdated = await updateRecipientInbox({
    env,
    adminToken,
    recipientUid: peerUid,
    chatId: body.chatId,
    senderUid: uid,
    senderNickname,
    recipientUsername,
    messageId: body.messageId,
    sentAtMillis: body.sentAtMillis,
    readRecipientLastReadTimestamp: () => adminDatabaseRequest(
      env,
      adminToken,
      "chatReadState/" + peerUid + "/" + body.chatId,
    ),
    fetchImpl,
  });
  if (!recipientUpdated) {
    return result(503, { error: "Recipient inbox changed repeatedly; retry preview synchronization.", retryable: true });
  }

  await clearRecipientUnreadIfRead({
    env,
    adminToken,
    recipientUid: peerUid,
    chatId: body.chatId,
    readValue: await adminDatabaseRequest(
      env,
      adminToken,
      "chatReadState/" + peerUid + "/" + body.chatId,
    ),
    fetchImpl,
  });

  const senderUpdated = await updateSenderInbox({
    env,
    adminToken,
    uid,
    chatId: body.chatId,
    peerUid,
    peerUsername: recipientUsername,
    senderNickname,
    messageId: body.messageId,
    sentAtMillis: body.sentAtMillis,
    fetchImpl,
  });
  if (!senderUpdated) {
    return result(503, { error: "Sender inbox changed repeatedly; retry preview synchronization.", retryable: true });
  }

  return result(200, { success: true, previewUpdated: true });
}
