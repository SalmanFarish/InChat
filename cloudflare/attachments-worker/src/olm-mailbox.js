import { loadChatMetadata } from "./chat-metadata.js";
import { isPublishedDevice } from "./olm-push.js";
import {
  PayloadTooLargeError,
  readRequestTextLimited,
} from "./bounded-request-body.js";

const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const MESSAGE_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DELIVERY_PATTERN = /^[A-Fa-f0-9]{64}$/;
const GROUP_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
const MAX_CIPHERTEXT_BYTES = 1024 * 1024;
const MAX_REQUEST_BYTES = MAX_ENVELOPE_BYTES + 64 * 1024;
const MIN_RETENTION_MILLIS = 60 * 60 * 1000;
const MAX_RETENTION_MILLIS = 30 * 24 * 60 * 60 * 1000;
const MAX_PENDING_DELIVERIES = 200;
const MAX_PENDING_BYTES = 20 * 1024 * 1024;
const MAX_PENDING_ACKS = 500;
const MAX_PAGE_SIZE = 50;
const EXPIRY_BATCH_SIZE = 100;

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function failure(status, error, retryable = false) {
  return jsonResponse({ error, ...(retryable ? { retryable: true } : {}) }, status);
}

function parseRetentionMillis(value) {
  const parsed = typeof value === "number" ? value :
    (typeof value === "string" && /^\d+$/.test(value) ? Number(value) : NaN);
  return Number.isSafeInteger(parsed) &&
    parsed >= MIN_RETENTION_MILLIS &&
    parsed <= MAX_RETENTION_MILLIS ?
    parsed :
    null;
}

function exactKeys(value, expected) {
  if (!isRecord(value)) return false;
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length &&
    actual.every((key, index) => key === sortedExpected[index]);
}

function validateEnvelope(envelopeText, senderDeviceId, recipientDeviceId) {
  if (typeof envelopeText !== "string") return false;
  const bytes = new TextEncoder().encode(envelopeText);
  if (bytes.length < 2 || bytes.length > MAX_ENVELOPE_BYTES) return false;

  let envelope;
  try {
    envelope = JSON.parse(envelopeText);
  } catch (_) {
    return false;
  }
  if (!exactKeys(envelope, [
    "algorithm",
    "ciphertext",
    "encryptionVersion",
    "recipientDeviceId",
    "senderDeviceId",
    "sessionId",
  ])) return false;

  if (
    envelope.encryptionVersion !== 1 ||
    envelope.algorithm !== "olm" ||
    envelope.senderDeviceId !== senderDeviceId ||
    envelope.recipientDeviceId !== recipientDeviceId ||
    typeof envelope.sessionId !== "string" ||
    envelope.sessionId.trim() === "" ||
    envelope.sessionId.length > 128 ||
    typeof envelope.ciphertext !== "string" ||
    !/^[A-Za-z0-9_-]+={0,2}$/.test(envelope.ciphertext)
  ) return false;

  try {
    const normalized = envelope.ciphertext.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
    const decoded = atob(padded);
    if (decoded.length < 2 || decoded.length > MAX_CIPHERTEXT_BYTES) return false;
    const canonical = btoa(decoded)
      .replace(/=/g, "")
      .replace(/\+/g, "-")
      .replace(/\//g, "_");
    const canonicalPadded = canonical + "=".repeat((4 - (canonical.length % 4)) % 4);
    return envelope.ciphertext === canonical ||
      envelope.ciphertext === canonicalPadded;
  } catch (_) {
    return false;
  }
}

function validateEnqueueBody(body, senderUid) {
  if (!isRecord(body)) return { error: failure(400, "Invalid encrypted mailbox request.") };

  const isGroup = body.conversationType === "group";
  const fields = isGroup ? [
    "conversationId",
    "conversationType",
    "deliveryId",
    "envelope",
    "groupId",
    "membershipEpoch",
    "messageId",
    "recipientDeviceId",
    "recipientUid",
    "senderDeviceId",
  ] : [
    "conversationId",
    "conversationPeerUserId",
    "conversationType",
    "deliveryId",
    "envelope",
    "messageId",
    "recipientDeviceId",
    "recipientUid",
    "senderDeviceId",
  ];

  if (!exactKeys(body, fields)) {
    return { error: failure(400, "Encrypted mailbox request has missing or unexpected fields.") };
  }

  const { deliveryId, messageId, recipientUid, recipientDeviceId, senderDeviceId } = body;
  if (
    !UID_PATTERN.test(senderUid) ||
    !UID_PATTERN.test(recipientUid || "") ||
    !DEVICE_PATTERN.test(senderDeviceId || "") ||
    !DEVICE_PATTERN.test(recipientDeviceId || "") ||
    !MESSAGE_PATTERN.test(messageId || "") ||
    !DELIVERY_PATTERN.test(deliveryId || "") ||
    typeof body.conversationId !== "string" ||
    body.conversationId.length < 1 ||
    body.conversationId.length > 257
  ) {
    return { error: failure(400, "Encrypted mailbox request contains an invalid identifier.") };
  }

  if (isGroup) {
    if (
      !GROUP_PATTERN.test(body.groupId || "") ||
      body.conversationId !== body.groupId ||
      !Number.isSafeInteger(body.membershipEpoch) ||
      body.membershipEpoch < 1
    ) {
      return { error: failure(400, "Encrypted group delivery scope is invalid.") };
    }
  } else if (
    body.conversationType !== "direct" ||
    !UID_PATTERN.test(body.conversationPeerUserId || "") ||
    body.conversationPeerUserId === senderUid ||
    ![senderUid, body.conversationPeerUserId].includes(recipientUid) ||
    body.conversationId !== [senderUid, body.conversationPeerUserId].sort().join("_")
  ) {
    return { error: failure(400, "Encrypted direct delivery scope is invalid.") };
  }

  return {
    value: {
      deliveryId: deliveryId.toLowerCase(),
      messageId,
      senderUid,
      senderDeviceId,
      recipientUid,
      recipientDeviceId,
      envelope: body.envelope,
      conversationType: body.conversationType,
      conversationId: body.conversationId,
      ...(isGroup ? {
        groupId: body.groupId,
        membershipEpoch: body.membershipEpoch,
      } : {
        conversationPeerUserId: body.conversationPeerUserId,
      }),
    },
  };
}

function validGroupSnapshot(chat, expectedEpoch) {
  const snapshot = chat?.cryptoMembership;
  const members = snapshot?.members;
  if (chat?.type !== "group" || !isRecord(snapshot) || !isRecord(members)) return false;
  const memberIds = Object.keys(members).sort();
  if (
    memberIds.length < 2 ||
    memberIds.length > 50 ||
    !Number.isSafeInteger(snapshot.epoch) ||
    snapshot.epoch !== expectedEpoch ||
    snapshot.epoch < 1
  ) return false;
  return memberIds.every((memberId) =>
    memberId && (members[memberId] === "admin" || members[memberId] === "member"));
}

async function parseRequestBody(request, maxBytes = MAX_REQUEST_BYTES) {
  const text = await readRequestTextLimited(request, maxBytes);
  if (!text) throw new TypeError("A JSON request body is required.");
  try {
    return JSON.parse(text);
  } catch (_) {
    throw new TypeError("Request body must contain valid JSON.");
  }
}

async function publishedDevice(adminDatabaseRequest, env, adminToken, uid, deviceId) {
  if (!UID_PATTERN.test(uid || "") || !DEVICE_PATTERN.test(deviceId || "")) return false;
  const value = await adminDatabaseRequest(
    env,
    adminToken,
    "deviceKeys/" + uid + "/" + deviceId,
  );
  return isPublishedDevice(value, deviceId);
}

async function authorizeEnqueue({ input, env, adminToken, adminDatabaseRequest }) {
  const [chat, senderDevice, recipientDevice] = await Promise.all([
    loadChatMetadata(adminDatabaseRequest, env, adminToken, input.conversationId),
    publishedDevice(adminDatabaseRequest, env, adminToken, input.senderUid, input.senderDeviceId),
    publishedDevice(adminDatabaseRequest, env, adminToken, input.recipientUid, input.recipientDeviceId),
  ]);

  if (!senderDevice || !recipientDevice) {
    return failure(404, "Sender or recipient device is no longer published.");
  }

  const [senderBlockedRecipient, recipientBlockedSender] =
    input.senderUid === input.recipientUid ? [null, null] :
      await Promise.all([
        adminDatabaseRequest(
          env, adminToken,
          "blockedUsers/" + input.senderUid + "/" + input.recipientUid,
        ),
        adminDatabaseRequest(
          env, adminToken,
          "blockedUsers/" + input.recipientUid + "/" + input.senderUid,
        ),
      ]);
  if (senderBlockedRecipient || recipientBlockedSender) {
    return failure(403, "Encrypted delivery is not allowed for this conversation.");
  }

  if (input.conversationType === "direct") {
    // Direct chats are stored as participantA/participantB records and
    // intentionally have no "type" field. Only explicit group records are
    // excluded from this direct-conversation authorization path.
    if (!isRecord(chat) || chat.type === "group") {
      return failure(404, "Direct conversation not found.");
    }
    const participantA = chat.participantA;
    const participantB = chat.participantB;
    const expectedParticipants = [input.senderUid, input.conversationPeerUserId].sort();
    if (
      !UID_PATTERN.test(participantA || "") ||
      !UID_PATTERN.test(participantB || "") ||
      participantA === participantB ||
      participantA !== expectedParticipants[0] ||
      participantB !== expectedParticipants[1] ||
      ![input.senderUid, input.conversationPeerUserId].includes(input.recipientUid)
    ) {
      return failure(403, "Sender or recipient is not a participant in this direct conversation.");
    }
    return null;
  }

  if (!validGroupSnapshot(chat, input.membershipEpoch)) {
    return failure(409, "Group membership epoch is stale or inconsistent.");
  }
  if (
    !Object.prototype.hasOwnProperty.call(chat.members, input.senderUid) ||
    !Object.prototype.hasOwnProperty.call(chat.members, input.recipientUid)
  ) {
    return failure(403, "Sender or recipient is not a current group member.");
  }
  return null;
}

function mailboxStub(env, uid, deviceId) {
  if (!env.OLM_MAILBOX ||
      typeof env.OLM_MAILBOX.idFromName !== "function" ||
      typeof env.OLM_MAILBOX.get !== "function") {
    return null;
  }
  const id = env.OLM_MAILBOX.idFromName(uid + ":" + deviceId);
  return env.OLM_MAILBOX.get(id);
}

async function proxyMailbox(stub, path, method = "GET", body = undefined) {
  if (!stub) return failure(503, "Encrypted mailbox storage is not configured.", true);
  try {
    const response = await stub.fetch(new Request("https://olm-mailbox.internal" + path, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }));
    const text = await response.text();
    if (response.status === 204) {
      return new Response(null, {
        status: 204,
        headers: { "Cache-Control": "no-store" },
      });
    }
    return new Response(text, {
      status: response.status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      },
    });
  } catch (_) {
    return failure(503, "Encrypted mailbox is temporarily unavailable.", true);
  }
}

/**
 * Authenticated HTTP facade for the ciphertext-only Durable Object mailboxes.
 * Firebase is used here only to re-check public device and conversation metadata.
 * Message envelopes and acknowledgements are stored only in the DO SQLite mailbox.
 */
export async function processOlmMailboxRequest({
  request,
  env,
  uid,
  adminToken,
  adminDatabaseRequest,
  now = Date.now(),
}) {
  const url = new URL(request.url);
  if (!url.pathname.startsWith("/v1/olm/mailbox/")) {
    return failure(404, "Not found.");
  }

  const retentionMillis = parseRetentionMillis(env.OLM_MAILBOX_RETENTION_MS);
  if (retentionMillis === null) {
    return failure(503, "Encrypted mailbox retention is not configured; message delivery is disabled.", true);
  }
  if (!mailboxStub(env, uid, "placeholder")) {
    return failure(503, "Encrypted mailbox storage is not configured.", true);
  }

  const adminAccessToken = typeof adminToken === "string" ? adminToken :
    (isRecord(adminToken) ? adminToken.token : "");
  if (typeof adminAccessToken !== "string" || !adminAccessToken) {
    return failure(503, "Mailbox authorization metadata is unavailable.", true);
  }

  try {
    if (request.method === "POST" && url.pathname === "/v1/olm/mailbox/enqueue") {
      const parsed = validateEnqueueBody(
        await parseRequestBody(request),
        uid,
      );
      if (parsed.error) return parsed.error;
      const authorizationFailure = await authorizeEnqueue({
        input: parsed.value,
        env,
        adminToken: adminAccessToken,
        adminDatabaseRequest,
      });
      if (authorizationFailure) return authorizationFailure;

      return proxyMailbox(
        mailboxStub(env, parsed.value.recipientUid, parsed.value.recipientDeviceId),
        "/enqueue",
        "POST",
        { ...parsed.value, retentionMillis, acceptedAt: now },
      );
    }

    if (request.method === "GET" && url.pathname === "/v1/olm/mailbox/inbox") {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!await publishedDevice(adminDatabaseRequest, env, adminAccessToken, uid, deviceId)) {
        return failure(404, "Recipient device is no longer published.");
      }
      const limit = url.searchParams.get("limit") || "50";
      const cursor = url.searchParams.get("cursor") || "0";
      if (!/^\d{1,2}$/.test(limit) || Number(limit) < 1 || Number(limit) > MAX_PAGE_SIZE ||
          !/^\d{1,16}$/.test(cursor) || !Number.isSafeInteger(Number(cursor))) {
        return failure(400, "Mailbox page cursor or limit is invalid.");
      }
      return proxyMailbox(
        mailboxStub(env, uid, deviceId),
        "/inbox?limit=" + limit + "&cursor=" + cursor,
      );
    }

    if (request.method === "POST" &&
        url.pathname === "/v1/olm/mailbox/acknowledge") {
      const body = await parseRequestBody(request, 4096);
      if (!exactKeys(body, ["deliveryId"]) ||
          !DELIVERY_PATTERN.test(body.deliveryId || "")) {
        return failure(400, "Mailbox acknowledgement is invalid.");
      }
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!await publishedDevice(adminDatabaseRequest, env, adminAccessToken, uid, deviceId)) {
        return failure(404, "Recipient device is no longer published.");
      }
      return proxyMailbox(
        mailboxStub(env, uid, deviceId),
        "/acknowledge",
        "POST",
        { deliveryId: body.deliveryId.toLowerCase(), retentionMillis, acceptedAt: now },
      );
    }

    const deleteDelivery = url.pathname.match(
      /^\/v1\/olm\/mailbox\/inbox\/([A-Fa-f0-9]{64})$/,
    );
    if (request.method === "DELETE" && deleteDelivery) {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!await publishedDevice(adminDatabaseRequest, env, adminAccessToken, uid, deviceId)) {
        return failure(404, "Recipient device is no longer published.");
      }
      return proxyMailbox(
        mailboxStub(env, uid, deviceId),
        "/inbox/" + deleteDelivery[1].toLowerCase(),
        "DELETE",
        { acceptedAt: now, retentionMillis },
      );
    }

    if (request.method === "GET" && url.pathname === "/v1/olm/mailbox/acks") {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!await publishedDevice(adminDatabaseRequest, env, adminAccessToken, uid, deviceId)) {
        return failure(404, "Sender device is no longer published.");
      }
      const limit = url.searchParams.get("limit") || "50";
      const cursor = url.searchParams.get("cursor") || "0";
      if (!/^\d{1,2}$/.test(limit) || Number(limit) < 1 || Number(limit) > MAX_PAGE_SIZE ||
          !/^\d{1,16}$/.test(cursor) || !Number.isSafeInteger(Number(cursor))) {
        return failure(400, "Acknowledgement page cursor or limit is invalid.");
      }
      return proxyMailbox(
        mailboxStub(env, uid, deviceId),
        "/acks?limit=" + limit + "&cursor=" + cursor,
      );
    }

    const deleteMatch = url.pathname.match(
      /^\/v1\/olm\/mailbox\/acks\/([A-Fa-f0-9]{64})$/,
    );
    if (request.method === "DELETE" && deleteMatch) {
      const deviceId = url.searchParams.get("deviceId") || "";
      if (!await publishedDevice(adminDatabaseRequest, env, adminAccessToken, uid, deviceId)) {
        return failure(404, "Sender device is no longer published.");
      }
      return proxyMailbox(
        mailboxStub(env, uid, deviceId),
        "/acks/" + deleteMatch[1].toLowerCase(),
        "DELETE",
      );
    }

    return failure(404, "Not found.");
  } catch (error) {
    if (error instanceof PayloadTooLargeError) {
      return failure(413, "Encrypted mailbox request exceeds the supported size.");
    }
    if (error instanceof TypeError) {
      return failure(400, error.message);
    }
    return failure(503, "Mailbox authorization or storage is temporarily unavailable.", true);
  }
}

function mailboxIdentity(id) {
  const name = id && typeof id.name === "string" ? id.name : "";
  const separator = name.indexOf(":");
  if (separator < 1 || name.indexOf(":", separator + 1) !== -1) return null;
  const uid = name.slice(0, separator);
  const deviceId = name.slice(separator + 1);
  if (!UID_PATTERN.test(uid) || !DEVICE_PATTERN.test(deviceId)) return null;
  return { uid, deviceId };
}

function immutableFieldsMatch(existing, incoming) {
  const names = [
    "delivery_id", "message_id", "sender_uid", "sender_device_id",
    "recipient_uid", "recipient_device_id", "conversation_type",
    "conversation_id", "conversation_peer_user_id", "group_id",
    "membership_epoch", "envelope_sha256",
  ];
  return names.every((name) => (existing[name] ?? null) === (incoming[name] ?? null));
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * One SQLite-backed object per account/device mailbox.
 *
 * The object is only reachable through the Worker's namespace binding. It does
 * not authenticate Firebase tokens itself; the Worker facade performs all
 * account, device, block, and conversation checks before calling this object.
 */
export class OlmDeviceMailboxDurableObject {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.identity = mailboxIdentity(ctx.id);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS deliveries (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        delivery_id TEXT NOT NULL UNIQUE,
        message_id TEXT NOT NULL,
        sender_uid TEXT NOT NULL,
        sender_device_id TEXT NOT NULL,
        recipient_uid TEXT NOT NULL,
        recipient_device_id TEXT NOT NULL,
        conversation_type TEXT NOT NULL,
        conversation_id TEXT NOT NULL,
        conversation_peer_user_id TEXT,
        group_id TEXT,
        membership_epoch INTEGER,
        envelope TEXT,
        envelope_sha256 TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        cleanup_at INTEGER,
        byte_size INTEGER NOT NULL,
        status TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS deliveries_pending_page
        ON deliveries(status, seq, expires_at);
      CREATE INDEX IF NOT EXISTS deliveries_expiry
        ON deliveries(status, expires_at, cleanup_at);
      CREATE TABLE IF NOT EXISTS acknowledgements (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        delivery_id TEXT NOT NULL UNIQUE,
        message_id TEXT NOT NULL,
        sender_uid TEXT NOT NULL,
        sender_device_id TEXT NOT NULL,
        recipient_uid TEXT NOT NULL,
        recipient_device_id TEXT NOT NULL,
        acknowledged_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS acknowledgements_page
        ON acknowledgements(seq, expires_at);
      CREATE INDEX IF NOT EXISTS acknowledgements_expiry
        ON acknowledgements(expires_at);
    `);
  }

  async fetch(request) {
    if (!this.identity) return failure(500, "Mailbox identity is invalid.");
    const url = new URL(request.url);
    try {
      if (request.method === "DELETE" && url.pathname === "/purge") {
        return await this.purge();
      }
      if (request.method === "POST" && url.pathname === "/enqueue") {
        return await this.enqueue(request);
      }
      if (request.method === "GET" && url.pathname === "/inbox") {
        return await this.readInbox(url);
      }
      const deliveryLookup = url.pathname.match(/^\/delivery\/([A-Fa-f0-9]{64})$/);
      if (request.method === "GET" && deliveryLookup) {
        return await this.readDeliveryMetadata(deliveryLookup[1].toLowerCase());
      }
      if (request.method === "POST" && url.pathname === "/acknowledge") {
        return await this.acknowledge(request);
      }
      const deleteDelivery = url.pathname.match(/^\/inbox\/([A-Fa-f0-9]{64})$/);
      if (request.method === "DELETE" && deleteDelivery) {
        return await this.removeDelivery(deleteDelivery[1].toLowerCase(), request);
      }
      if (request.method === "POST" && url.pathname === "/ack-record") {
        return await this.storeAcknowledgementRecord(request);
      }
      if (request.method === "GET" && url.pathname === "/acks") {
        return await this.readAcknowledgements(url);
      }
      const deleteAck = url.pathname.match(/^\/acks\/([A-Fa-f0-9]{64})$/);
      if (request.method === "DELETE" && deleteAck) {
        return await this.removeAcknowledgement(deleteAck[1].toLowerCase());
      }
      return failure(404, "Mailbox operation not found.");
    } catch (error) {
      const message = error && typeof error.message === "string" ? error.message : "";
      if (message.includes("SQLITE_FULL") || message.includes("quota")) {
        return failure(503, "Free mailbox storage capacity is temporarily exhausted.", true);
      }
      return failure(503, "Mailbox storage is temporarily unavailable.", true);
    }
  }

  async purge() {
    // The outer Worker derives this object's account/device identity from the
    // authenticated owner token. Purge both ciphertext and sender ACK state,
    // including tombstones, when a device is retired or revoked.
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("DELETE FROM deliveries");
      this.sql.exec("DELETE FROM acknowledgements");
    });
    await this.ctx.storage.deleteAlarm();
    return jsonResponse({ purged: true });
  }

  async enqueue(request) {
    const body = await parseRequestBody(request);
    if (!isRecord(body)) return failure(400, "Invalid mailbox delivery.");
    const allowed = [
      "acceptedAt", "conversationId", "conversationPeerUserId",
      "conversationType", "deliveryId", "envelope", "groupId",
      "membershipEpoch", "messageId", "recipientDeviceId", "recipientUid",
      "retentionMillis", "senderDeviceId", "senderUid",
    ];
    const isGroup = body.conversationType === "group";
    const expectedFields = isGroup ?
      allowed.filter((key) => key !== "conversationPeerUserId") :
      allowed.filter((key) => key !== "groupId" && key !== "membershipEpoch");
    if (!exactKeys(body, expectedFields)) {
      return failure(400, "Mailbox delivery has missing or unexpected fields.");
    }
    const { uid, deviceId } = this.identity;
    const retention = parseRetentionMillis(body.retentionMillis);
    if (
      retention === null ||
      !Number.isSafeInteger(body.acceptedAt) ||
      body.acceptedAt < 0 ||
      body.recipientUid !== uid ||
      body.recipientDeviceId !== deviceId ||
      !UID_PATTERN.test(body.senderUid || "") ||
      !DEVICE_PATTERN.test(body.senderDeviceId || "") ||
      !MESSAGE_PATTERN.test(body.messageId || "") ||
      !DELIVERY_PATTERN.test(body.deliveryId || "") ||
      !validateEnvelope(body.envelope, body.senderDeviceId, deviceId)
    ) return failure(400, "Mailbox delivery identity or envelope is invalid.");

    if (isGroup) {
      if (!exactKeys(body, allowed.filter((key) => key !== "conversationPeerUserId")) ||
          !GROUP_PATTERN.test(body.groupId || "") ||
          body.groupId !== body.conversationId ||
          !Number.isSafeInteger(body.membershipEpoch) ||
          body.membershipEpoch < 1) {
        return failure(400, "Mailbox group scope is invalid.");
      }
    } else if (
      body.conversationType !== "direct" ||
      !exactKeys(body, allowed.filter((key) =>
        key !== "groupId" && key !== "membershipEpoch")) ||
      !UID_PATTERN.test(body.conversationPeerUserId || "") ||
      body.conversationPeerUserId === body.senderUid ||
      ![body.senderUid, body.conversationPeerUserId].includes(uid) ||
      body.conversationId !== [body.senderUid, body.conversationPeerUserId].sort().join("_")
    ) {
      return failure(400, "Mailbox direct scope is invalid.");
    }

    const payloadBytes = new TextEncoder().encode(body.envelope).length;
    const envelopeHash = await sha256Hex(body.envelope);
    const createdAt = Math.max(0, Math.floor(body.acceptedAt));
    const expiresAt = createdAt + retention;
    if (!Number.isSafeInteger(expiresAt)) return failure(400, "Mailbox expiry is invalid.");

    const incoming = {
      delivery_id: body.deliveryId.toLowerCase(),
      message_id: body.messageId,
      sender_uid: body.senderUid,
      sender_device_id: body.senderDeviceId,
      recipient_uid: uid,
      recipient_device_id: deviceId,
      conversation_type: body.conversationType,
      conversation_id: body.conversationId,
      conversation_peer_user_id: body.conversationPeerUserId ?? null,
      group_id: body.groupId ?? null,
      membership_epoch: body.membershipEpoch ?? null,
      envelope_sha256: envelopeHash,
      envelope: body.envelope,
      created_at: createdAt,
      expires_at: expiresAt,
      byte_size: payloadBytes,
      status: "pending",
    };

    const result = this.ctx.storage.transactionSync(() => {
      const existing = this.sql.exec(
        "SELECT * FROM deliveries WHERE delivery_id = ?",
        incoming.delivery_id,
      ).toArray()[0];
      if (existing) {
        if (!immutableFieldsMatch(existing, incoming)) return { status: "conflict" };
        if (existing.status === "expired") return { status: "expired" };
        if (existing.status === "acked") {
          // The sender deliberately deletes a received ACK before persisting
          // its Room receipt. If that process stops in between, Android resends
          // the same immutable delivery ID. Re-stage the original ciphertext
          // so the recipient's durable receipt ledger can deduplicate it and
          // publish the ACK again instead of stranding the sender outbox.
          const usage = this.sql.exec(
            "SELECT COUNT(*) AS pending_count, COALESCE(SUM(byte_size), 0) AS pending_bytes " +
            "FROM deliveries WHERE status IN ('pending','acknowledged')",
          ).toArray()[0];
          if (
            Number(usage.pending_count) >= MAX_PENDING_DELIVERIES ||
            Number(usage.pending_bytes) + incoming.byte_size > MAX_PENDING_BYTES
          ) return { status: "full" };

          this.sql.exec(
            "UPDATE deliveries SET envelope = ?, created_at = ?, expires_at = ?, cleanup_at = NULL, " +
            "byte_size = ?, status = 'pending' WHERE delivery_id = ? AND status = 'acked'",
            incoming.envelope,
            incoming.created_at,
            incoming.expires_at,
            incoming.byte_size,
            incoming.delivery_id,
          );
          return {
            status: "stored",
            createdAt: incoming.created_at,
            expiresAt: incoming.expires_at,
            duplicate: true,
            replayedAfterAck: true,
          };
        }
        return {
          status: "stored",
          createdAt: existing.created_at,
          expiresAt: existing.expires_at,
          duplicate: true,
          replayedAfterAck: false,
        };
      }

      const usage = this.sql.exec(
        "SELECT COUNT(*) AS pending_count, COALESCE(SUM(byte_size), 0) AS pending_bytes " +
        "FROM deliveries WHERE status IN ('pending','acknowledged')",
      ).toArray()[0];
      if (
        Number(usage.pending_count) >= MAX_PENDING_DELIVERIES ||
        Number(usage.pending_bytes) + payloadBytes > MAX_PENDING_BYTES
      ) return { status: "full" };

      this.sql.exec(
        "INSERT INTO deliveries (" +
          "delivery_id,message_id,sender_uid,sender_device_id,recipient_uid,recipient_device_id," +
          "conversation_type,conversation_id,conversation_peer_user_id,group_id,membership_epoch," +
          "envelope,envelope_sha256,created_at,expires_at,cleanup_at,byte_size,status" +
        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        incoming.delivery_id,
        incoming.message_id,
        incoming.sender_uid,
        incoming.sender_device_id,
        incoming.recipient_uid,
        incoming.recipient_device_id,
        incoming.conversation_type,
        incoming.conversation_id,
        incoming.conversation_peer_user_id,
        incoming.group_id,
        incoming.membership_epoch,
        incoming.envelope,
        incoming.envelope_sha256,
        incoming.created_at,
        incoming.expires_at,
        null,
        incoming.byte_size,
        incoming.status,
      );
      return { status: "stored", createdAt, expiresAt, duplicate: false };
    });

    if (result.status === "conflict") return failure(409, "Delivery ID was reused with different immutable content.");
    if (result.status === "expired") return failure(410, "This delivery has expired.");
    if (result.status === "full") return failure(503, "Recipient mailbox is full; sender must retry later.", true);
    await this.scheduleNextAlarm();
    return jsonResponse({
      accepted: true,
      duplicate: result.duplicate,
      replayedAfterAck: result.replayedAfterAck === true,
      createdAt: result.createdAt,
      expiresAt: result.expiresAt,
    });
  }

  async readInbox(url) {
    const limit = this.parsePageLimit(url);
    if (limit.error) return limit.error;
    const cursor = this.parseCursor(url);
    if (cursor.error) return cursor.error;
    const now = Date.now();
    const rows = this.sql.exec(
      "SELECT seq,delivery_id,message_id,sender_uid,sender_device_id,envelope," +
        "conversation_type,conversation_id,conversation_peer_user_id,group_id,membership_epoch " +
        "FROM deliveries WHERE status IN ('pending','acknowledged') AND expires_at > ? AND seq > ? " +
        "ORDER BY seq ASC LIMIT ?",
      now,
      cursor.value,
      limit.value + 1,
    ).toArray();
    const hasMore = rows.length > limit.value;
    const page = hasMore ? rows.slice(0, limit.value) : rows;
    return jsonResponse({
      items: page.map((row) => ({
        deliveryId: row.delivery_id,
        senderUid: row.sender_uid,
        senderDeviceId: row.sender_device_id,
        messageId: row.message_id,
        envelope: row.envelope,
        conversationType: row.conversation_type,
        conversationId: row.conversation_id,
        ...(row.conversation_type === "group" ? {
          groupId: row.group_id,
          membershipEpoch: row.membership_epoch,
        } : {
          conversationPeerUserId: row.conversation_peer_user_id,
        }),
      })),
      nextCursor: hasMore && page.length ? String(page[page.length - 1].seq) : null,
    });
  }

  async readDeliveryMetadata(deliveryId) {
    const row = this.sql.exec(
      "SELECT delivery_id,message_id,sender_uid,sender_device_id,recipient_uid,recipient_device_id," +
        "conversation_type,conversation_id,conversation_peer_user_id,group_id,membership_epoch," +
        "envelope_sha256,expires_at,status FROM deliveries WHERE delivery_id = ?",
      deliveryId,
    ).toArray()[0];
    if (!row || row.status === "expired" || row.expires_at <= Date.now()) {
      return failure(404, "Mailbox delivery was not found.");
    }
    // The Worker needs proof that a particular ciphertext was accepted, not
    // the ciphertext itself. Do not expose envelope bytes through this lookup.
    return jsonResponse({
      delivery: {
        deliveryId: row.delivery_id,
        messageId: row.message_id,
        senderUid: row.sender_uid,
        senderDeviceId: row.sender_device_id,
        recipientUid: row.recipient_uid,
        recipientDeviceId: row.recipient_device_id,
        conversationType: row.conversation_type,
        conversationId: row.conversation_id,
        conversationPeerUserId: row.conversation_peer_user_id,
        groupId: row.group_id,
        membershipEpoch: row.membership_epoch,
        envelopeSha256: row.envelope_sha256,
        expiresAt: row.expires_at,
        status: row.status,
      },
    });
  }

  async acknowledge(request) {
    const body = await parseRequestBody(request, 4096);
    if (!exactKeys(body, ["acceptedAt", "deliveryId", "retentionMillis"]) ||
        !DELIVERY_PATTERN.test(body.deliveryId || "")) {
      return failure(400, "Mailbox acknowledgement is invalid.");
    }
    const retention = parseRetentionMillis(body.retentionMillis);
    if (retention === null || !Number.isSafeInteger(body.acceptedAt) || body.acceptedAt < 0) {
      return failure(400, "Mailbox acknowledgement metadata is invalid.");
    }
    const deliveryId = body.deliveryId.toLowerCase();
    const delivery = this.sql.exec(
      "SELECT * FROM deliveries WHERE delivery_id = ?",
      deliveryId,
    ).toArray()[0];
    if (!delivery) return failure(404, "Mailbox delivery was not found.");
    if (delivery.status === "expired" || delivery.expires_at <= Date.now()) {
      return failure(410, "Mailbox delivery has expired.");
    }
    const alreadyAcknowledged = delivery.status === "acknowledged";
    if (delivery.status !== "pending" && !alreadyAcknowledged) {
      return failure(409, "Mailbox delivery is not acknowledgeable.");
    }

    const acknowledgement = {
      senderUid: delivery.sender_uid,
      senderDeviceId: delivery.sender_device_id,
      recipientUid: delivery.recipient_uid,
      recipientDeviceId: delivery.recipient_device_id,
      deliveryId: delivery.delivery_id,
      messageId: delivery.message_id,
      acknowledgedAt: Math.max(0, Math.floor(body.acceptedAt)),
      retentionMillis: retention,
    };
    const senderMailbox = this.env.OLM_MAILBOX?.get(
      this.env.OLM_MAILBOX.idFromName(
        acknowledgement.senderUid + ":" + acknowledgement.senderDeviceId,
      ),
    );
    if (!senderMailbox) return failure(503, "Sender acknowledgement mailbox is unavailable.", true);

    // Cross-object changes cannot share a transaction. Persist the sender ACK
    // first; only then remove the recipient ciphertext. Exact retries converge.
    const storedAck = await senderMailbox.fetch(new Request(
      "https://olm-mailbox.internal/ack-record",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(acknowledgement),
      },
    ));
    if (!storedAck.ok) {
      if (storedAck.status === 409) return failure(409, "Acknowledgement identity conflicts with an existing receipt.");
      return failure(503, "Sender acknowledgement could not be stored; retry safely.", true);
    }

    if (!alreadyAcknowledged) {
      this.ctx.storage.transactionSync(() => {
        this.sql.exec(
          "UPDATE deliveries SET status = 'acknowledged' " +
          "WHERE delivery_id = ? AND status = 'pending'",
          deliveryId,
        );
      });
    }
    return jsonResponse({ acknowledged: true, duplicate: alreadyAcknowledged });
  }

  async removeDelivery(deliveryId, request) {
    const body = await parseRequestBody(request, 4096);
    if (!exactKeys(body, ["acceptedAt", "retentionMillis"])) {
      return failure(400, "Mailbox removal metadata is invalid.");
    }
    const retention = parseRetentionMillis(body.retentionMillis);
    if (retention === null || !Number.isSafeInteger(body.acceptedAt) || body.acceptedAt < 0) {
      return failure(400, "Mailbox removal metadata is invalid.");
    }
    const current = this.sql.exec(
      "SELECT status FROM deliveries WHERE delivery_id = ?",
      deliveryId,
    ).toArray()[0];
    if (!current) return failure(404, "Mailbox delivery was not found.");
    if (current.status === "acked") {
      return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    }
    if (current.status !== "acknowledged") {
      return failure(409, "Mailbox delivery cannot be removed before its acknowledgement is stored.");
    }
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        "UPDATE deliveries SET status = 'acked', envelope = NULL, byte_size = 0, cleanup_at = ? " +
        "WHERE delivery_id = ? AND status = 'acknowledged'",
        Math.floor(body.acceptedAt) + retention,
        deliveryId,
      );
    });
    await this.scheduleNextAlarm();
    return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  }

  async storeAcknowledgementRecord(request) {
    const body = await parseRequestBody(request, 4096);
    if (!exactKeys(body, [
      "acknowledgedAt", "deliveryId", "messageId", "recipientDeviceId",
      "recipientUid", "retentionMillis", "senderDeviceId", "senderUid",
    ])) return failure(400, "Acknowledgement record has missing or unexpected fields.");

    const { uid, deviceId } = this.identity;
    const retention = parseRetentionMillis(body.retentionMillis);
    if (
      retention === null ||
      body.senderUid !== uid ||
      body.senderDeviceId !== deviceId ||
      !UID_PATTERN.test(body.recipientUid || "") ||
      !DEVICE_PATTERN.test(body.recipientDeviceId || "") ||
      !DELIVERY_PATTERN.test(body.deliveryId || "") ||
      !MESSAGE_PATTERN.test(body.messageId || "") ||
      !Number.isSafeInteger(body.acknowledgedAt) ||
      body.acknowledgedAt < 0
    ) return failure(400, "Acknowledgement record identity is invalid.");

    const expiresAt = body.acknowledgedAt + retention;
    if (!Number.isSafeInteger(expiresAt)) return failure(400, "Acknowledgement expiry is invalid.");
    const result = this.ctx.storage.transactionSync(() => {
      const existing = this.sql.exec(
        "SELECT * FROM acknowledgements WHERE delivery_id = ?",
        body.deliveryId.toLowerCase(),
      ).toArray()[0];
      if (existing) {
        const same = existing.message_id === body.messageId &&
          existing.sender_uid === uid &&
          existing.sender_device_id === deviceId &&
          existing.recipient_uid === body.recipientUid &&
          existing.recipient_device_id === body.recipientDeviceId;
        return same ? "duplicate" : "conflict";
      }

      const count = this.sql.exec(
        "SELECT COUNT(*) AS total FROM acknowledgements WHERE expires_at > ?",
        body.acknowledgedAt,
      ).toArray()[0];
      if (Number(count.total) >= MAX_PENDING_ACKS) return "full";

      this.sql.exec(
        "INSERT INTO acknowledgements (" +
          "delivery_id,message_id,sender_uid,sender_device_id,recipient_uid,recipient_device_id," +
          "acknowledged_at,expires_at" +
        ") VALUES (?,?,?,?,?,?,?,?)",
        body.deliveryId.toLowerCase(),
        body.messageId,
        uid,
        deviceId,
        body.recipientUid,
        body.recipientDeviceId,
        body.acknowledgedAt,
        expiresAt,
      );
      return "stored";
    });

    if (result === "conflict") return failure(409, "Acknowledgement delivery ID conflicts with an existing receipt.");
    if (result === "full") return failure(503, "Sender acknowledgement mailbox is full.", true);
    await this.scheduleNextAlarm();
    return jsonResponse({ stored: true, duplicate: result === "duplicate" });
  }

  async readAcknowledgements(url) {
    const limit = this.parsePageLimit(url);
    if (limit.error) return limit.error;
    const cursor = this.parseCursor(url);
    if (cursor.error) return cursor.error;
    const rows = this.sql.exec(
      "SELECT seq,delivery_id,message_id,recipient_uid,recipient_device_id,acknowledged_at " +
      "FROM acknowledgements WHERE expires_at > ? AND seq > ? " +
      "ORDER BY seq ASC LIMIT ?",
      Date.now(),
      cursor.value,
      limit.value + 1,
    ).toArray();
    const hasMore = rows.length > limit.value;
    const page = hasMore ? rows.slice(0, limit.value) : rows;
    return jsonResponse({
      items: page.map((row) => ({
        deliveryId: row.delivery_id,
        recipientUid: row.recipient_uid,
        recipientDeviceId: row.recipient_device_id,
        messageId: row.message_id,
        acknowledgedAt: row.acknowledged_at,
      })),
      nextCursor: hasMore && page.length ? String(page[page.length - 1].seq) : null,
    });
  }

  async removeAcknowledgement(deliveryId) {
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        "DELETE FROM acknowledgements WHERE delivery_id = ? AND sender_uid = ? AND sender_device_id = ?",
        deliveryId,
        this.identity.uid,
        this.identity.deviceId,
      );
    });
    await this.scheduleNextAlarm();
    return new Response(null, {
      status: 204,
      headers: { "Cache-Control": "no-store" },
    });
  }

  parsePageLimit(url) {
    const raw = url.searchParams.get("limit") || "50";
    if (!/^\d{1,2}$/.test(raw) || Number(raw) < 1 || Number(raw) > MAX_PAGE_SIZE) {
      return { error: failure(400, "Mailbox page limit is invalid.") };
    }
    return { value: Number(raw) };
  }

  parseCursor(url) {
    const raw = url.searchParams.get("cursor") || "0";
    if (!/^\d{1,16}$/.test(raw) || !Number.isSafeInteger(Number(raw))) {
      return { error: failure(400, "Mailbox page cursor is invalid.") };
    }
    return { value: Number(raw) };
  }

  async scheduleNextAlarm() {
    const delivery = this.sql.exec(
      "SELECT MIN(CASE WHEN status IN ('pending','acknowledged') THEN expires_at ELSE cleanup_at END) AS next_time " +
      "FROM deliveries WHERE status IN ('pending','acknowledged','expired','acked')",
    ).toArray()[0]?.next_time;
    const acknowledgement = this.sql.exec(
      "SELECT MIN(expires_at) AS next_time FROM acknowledgements",
    ).toArray()[0]?.next_time;
    const candidates = [delivery, acknowledgement]
      .filter((value) => value !== null && value !== undefined)
      .map(Number)
      .filter((value) => Number.isSafeInteger(value) && value > 0);
    const next = candidates.length ? Math.min(...candidates) : null;
    const current = await this.ctx.storage.getAlarm();
    if (next === null) {
      if (current !== null) await this.ctx.storage.deleteAlarm();
    } else if (current === null || next < current) {
      await this.ctx.storage.setAlarm(next);
    }
  }

  async alarm() {
    const now = Date.now();
    const retention = parseRetentionMillis(this.env.OLM_MAILBOX_RETENTION_MS);
    const tombstoneUntil = now + (retention || MIN_RETENTION_MILLIS);
    this.ctx.storage.transactionSync(() => {
      const expired = this.sql.exec(
        "SELECT seq FROM deliveries WHERE status IN ('pending','acknowledged') AND expires_at <= ? " +
        "ORDER BY expires_at ASC LIMIT ?",
        now,
        EXPIRY_BATCH_SIZE,
      ).toArray();
      for (const row of expired) {
        this.sql.exec(
          "UPDATE deliveries SET status = 'expired', envelope = NULL, byte_size = 0, cleanup_at = ? " +
          "WHERE seq = ? AND status IN ('pending','acknowledged')",
          tombstoneUntil,
          row.seq,
        );
      }

      const oldTombstones = this.sql.exec(
        "SELECT seq FROM deliveries WHERE status IN ('expired','acked') AND cleanup_at <= ? " +
        "ORDER BY cleanup_at ASC LIMIT ?",
        now,
        EXPIRY_BATCH_SIZE,
      ).toArray();
      for (const row of oldTombstones) {
        this.sql.exec("DELETE FROM deliveries WHERE seq = ?", row.seq);
      }

      const expiredAcks = this.sql.exec(
        "SELECT seq FROM acknowledgements WHERE expires_at <= ? ORDER BY expires_at ASC LIMIT ?",
        now,
        EXPIRY_BATCH_SIZE,
      ).toArray();
      for (const row of expiredAcks) {
        this.sql.exec("DELETE FROM acknowledgements WHERE seq = ?", row.seq);
      }
    });

    // Keep alarms bounded. If a batch was full, another pass starts shortly.
    const remainingExpired = this.sql.exec(
      "SELECT 1 AS present FROM deliveries WHERE (status IN ('pending','acknowledged') AND expires_at <= ?) " +
      "OR (status IN ('expired','acked') AND cleanup_at <= ?) LIMIT 1",
      now,
      now,
    ).toArray().length > 0;
    const remainingExpiredAcks = this.sql.exec(
      "SELECT 1 AS present FROM acknowledgements WHERE expires_at <= ? LIMIT 1",
      now,
    ).toArray().length > 0;
    if (remainingExpired || remainingExpiredAcks) {
      await this.ctx.storage.setAlarm(now + 1000);
      return;
    }
    await this.scheduleNextAlarm();
  }
}
