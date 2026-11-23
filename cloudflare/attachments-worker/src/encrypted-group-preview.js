import { isPublishedDevice, isValidEnvelopeRecord } from "./olm-push.js";
import { readWithEtag, putWithEtag } from "./encrypted-direct-preview.js";

const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const GROUP_PATTERN = /^[A-Za-z0-9_-]{3,257}$/;
const MESSAGE_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const RECENT_MESSAGE_LIMIT = 128;

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function result(status, payload) {
  return { status, payload };
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

function getCurrentMembership(chat, groupId, membershipEpoch) {
  if (!isRecord(chat) || chat.type !== "group" ||
      !isRecord(chat.members) || !isRecord(chat.cryptoMembership)) {
    return null;
  }
  const memberIds = Object.keys(chat.members).sort();
  const snapshot = chat.cryptoMembership;
  const snapshotIds = isRecord(snapshot.members) ?
    Object.keys(snapshot.members).sort() :
    [];
  const validRoles = memberIds.length >= 2 && memberIds.length <= 50 &&
    memberIds.every((memberId) =>
      UID_PATTERN.test(memberId) &&
      (chat.members[memberId] === "admin" || chat.members[memberId] === "member"));
  const sameRoster = validRoles &&
    memberIds.length === snapshotIds.length &&
    memberIds.every((memberId, index) =>
      memberId === snapshotIds[index] &&
      chat.members[memberId] === snapshot.members[memberId]);
  if (!sameRoster || snapshot.epoch !== membershipEpoch ||
      !Number.isSafeInteger(snapshot.epoch) || snapshot.epoch < 1 ||
      typeof chat.name !== "string" || !chat.name.trim()) {
    return null;
  }
  return { memberIds, name: chat.name.trim() };
}

/** Update one member inbox with ETag protection and message-ID deduplication. */
async function updateMemberInbox({
  env,
  adminToken,
  memberId,
  senderUid,
  senderNickname,
  groupId,
  groupName,
  memberCount,
  messageId,
  sentAtMillis,
  readCursor,
  fetchImpl,
}) {
  const path = "userChats/" + memberId + "/" + groupId;
  for (let attempt = 0; attempt < 4; attempt++) {
    const [snapshot, readValue] = await Promise.all([
      readWithEtag(env, adminToken, path, fetchImpl),
      readCursor(),
    ]);
    const cursor = Number(readValue || 0);
    const existing = isRecord(snapshot.value) ? snapshot.value : {};
    const recent = Array.isArray(existing.recentEncryptedMessageIds) ?
      existing.recentEncryptedMessageIds.filter((value) =>
        typeof value === "string" && MESSAGE_PATTERN.test(value)) :
      [];
    const alreadyProcessed = recent.includes(messageId);
    const next = {
      ...existing,
      chatId: groupId,
      chatType: "group",
      groupName,
      memberCount,
      otherUserId: "",
      otherUsername: "",
      recentEncryptedMessageIds: alreadyProcessed ?
        recent :
        [...recent, messageId].slice(-RECENT_MESSAGE_LIMIT),
      ...(typeof existing.unreadCount === "number" ? {} : { unreadCount: 0 }),
    };

    if (memberId !== senderUid && !alreadyProcessed &&
        (!Number.isFinite(cursor) || cursor < sentAtMillis)) {
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
      // A member may open the group while this preview is being synchronized.
      // Recheck the cursor and clear only if it covers the current latest item.
      const latestRead = Number((await readCursor()) || 0);
      if (Number.isFinite(latestRead) && latestRead > 0) {
        const latest = await readWithEtag(env, adminToken, path, fetchImpl);
        const latestInbox = isRecord(latest.value) ? latest.value : null;
        const latestTimestamp = Number(latestInbox?.lastTimestamp || 0);
        const unreadCount = Number(latestInbox?.unreadCount || 0);
        if (latestInbox && Number.isFinite(latestTimestamp) &&
            latestRead >= latestTimestamp && unreadCount > 0) {
          await putWithEtag(
            env,
            adminToken,
            path,
            latest.etag,
            { ...latestInbox, unreadCount: 0 },
            fetchImpl,
          );
        }
      }
      return true;
    }
  }
  return false;
}

/**
 * Sync a generic encrypted group preview after verifying that the exact Olm
 * envelope exists for a currently published member device and the current
 * group membership epoch. No Megolm plaintext is accepted or written.
 */
export async function syncEncryptedGroupPreview({
  env,
  uid,
  body,
  adminToken,
  adminDatabaseRequest,
  fetchImpl = fetch,
  now = Date.now(),
}) {
  if (!isRecord(body)) {
    return result(400, { error: "Invalid encrypted group preview request." });
  }
  const expectedFields = [
    "deliveryId",
    "groupId",
    "membershipEpoch",
    "messageId",
    "recipientDeviceId",
    "recipientUserId",
    "senderDeviceId",
    "sentAtMillis",
  ].sort();
  const actualFields = Object.keys(body).sort();
  if (actualFields.length !== expectedFields.length ||
      actualFields.some((field, index) => field !== expectedFields[index])) {
    return result(400, { error: "Encrypted group preview request has missing or unknown fields." });
  }
  if (!UID_PATTERN.test(uid || "") ||
      typeof body.groupId !== "string" || !GROUP_PATTERN.test(body.groupId) ||
      typeof body.messageId !== "string" || !MESSAGE_PATTERN.test(body.messageId) ||
      typeof body.deliveryId !== "string" || !/^[A-Fa-f0-9]{64}$/.test(body.deliveryId) ||
      typeof body.senderDeviceId !== "string" || !DEVICE_PATTERN.test(body.senderDeviceId) ||
      typeof body.recipientDeviceId !== "string" || !DEVICE_PATTERN.test(body.recipientDeviceId) ||
      typeof body.recipientUserId !== "string" || !UID_PATTERN.test(body.recipientUserId) ||
      body.recipientUserId === uid ||
      !Number.isSafeInteger(body.membershipEpoch) || body.membershipEpoch < 1 ||
      !Number.isSafeInteger(body.sentAtMillis) || body.sentAtMillis <= 0 ||
      body.sentAtMillis > now + 5 * 60_000) {
    return result(400, { error: "Encrypted group preview request contains invalid metadata." });
  }
  if (typeof adminToken !== "string" || !adminToken.trim() ||
      typeof adminDatabaseRequest !== "function") {
    return result(503, { error: "Firebase Admin access is unavailable.", retryable: true });
  }

  const groupId = body.groupId;
  const chat = await adminDatabaseRequest(env, adminToken, "chats/" + groupId);
  const membership = getCurrentMembership(chat, groupId, body.membershipEpoch);
  if (!membership) {
    return result(409, { error: "Group membership epoch is stale or invalid." });
  }
  if (!membership.memberIds.includes(uid) ||
      !membership.memberIds.includes(body.recipientUserId)) {
    return result(403, { error: "Sender or recipient is not a current group member." });
  }

  const [senderDevice, recipientDevice, relayRecord] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "deviceKeys/" + uid + "/" + body.senderDeviceId),
    adminDatabaseRequest(env, adminToken, "deviceKeys/" + body.recipientUserId + "/" + body.recipientDeviceId),
    adminDatabaseRequest(
      env,
      adminToken,
      "olmInbox/" + body.recipientUserId + "/" + body.recipientDeviceId + "/" + body.deliveryId.toLowerCase(),
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
    groupId,
    membershipEpoch: body.membershipEpoch,
  })) {
    return result(409, { error: "Encrypted relay record does not match the authenticated group preview request." });
  }

  const senderProfile = await adminDatabaseRequest(env, adminToken, "users/" + uid);
  const senderNickname = username(senderProfile);

  // Update every current group member's summary. Each row has its own bounded
  // idempotency ledger so multi-device deliveries cannot double-increment unread.
  for (const memberId of membership.memberIds) {
    const updated = await updateMemberInbox({
      env,
      adminToken,
      memberId,
      senderUid: uid,
      senderNickname,
      groupId,
      groupName: membership.name,
      memberCount: membership.memberIds.length,
      messageId: body.messageId,
      sentAtMillis: body.sentAtMillis,
      readCursor: () => adminDatabaseRequest(
        env,
        adminToken,
        "chats/" + groupId + "/groupReadCursors/" + memberId,
      ),
      fetchImpl,
    });
    if (!updated) {
      return result(503, {
        error: "A group member inbox changed repeatedly; retry encrypted group preview synchronization.",
        retryable: true,
      });
    }
  }

  // Do not continue using an obsolete roster if membership changed while
  // member inbox entries were being updated.
  const latestChat = await adminDatabaseRequest(env, adminToken, "chats/" + groupId);
  if (!getCurrentMembership(latestChat, groupId, body.membershipEpoch)) {
    return result(409, { error: "Group membership changed during preview synchronization." });
  }

  return result(200, { success: true, previewUpdated: true });
}
