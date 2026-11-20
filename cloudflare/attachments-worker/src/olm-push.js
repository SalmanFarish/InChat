const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const MESSAGE_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DELIVERY_PATTERN = /^[A-Fa-f0-9]{64}$/;
const CHAT_PATTERN = /^[A-Za-z0-9_-]{3,257}$/;
const MAX_CIPHERTEXT_BYTES = 1024 * 1024;
const MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
const PUSH_LEASE_MILLIS = 30_000;

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function result(status, payload) {
  return { status, payload };
}

function validateRequest(body, authenticatedUid) {
  if (!isRecord(body)) {
    return { error: result(400, { error: "Invalid encrypted push request." }) };
  }

  const requiredFields = [
    "chatId",
    "deliveryId",
    "messageId",
    "recipientDeviceId",
    "senderDeviceId",
  ];
  const actualFields = Object.keys(body).sort();
  if (
    actualFields.length !== requiredFields.length ||
    actualFields.some((field, index) => field !== [...requiredFields].sort()[index])
  ) {
    return { error: result(400, { error: "Encrypted push request has missing or unknown fields." }) };
  }

  if (!UID_PATTERN.test(authenticatedUid)) {
    return { error: result(401, { error: "Unauthorized." }) };
  }

  if (
    typeof body.chatId !== "string" ||
    !CHAT_PATTERN.test(body.chatId) ||
    typeof body.deliveryId !== "string" ||
    !DELIVERY_PATTERN.test(body.deliveryId) ||
    typeof body.messageId !== "string" ||
    !MESSAGE_PATTERN.test(body.messageId) ||
    typeof body.senderDeviceId !== "string" ||
    !DEVICE_PATTERN.test(body.senderDeviceId) ||
    typeof body.recipientDeviceId !== "string" ||
    !DEVICE_PATTERN.test(body.recipientDeviceId) ||
    body.senderDeviceId === body.recipientDeviceId
  ) {
    return { error: result(400, { error: "Encrypted push request contains an invalid identifier." }) };
  }

  return {
    value: {
      chatId: body.chatId,
      deliveryId: body.deliveryId.toLowerCase(),
      messageId: body.messageId,
      senderDeviceId: body.senderDeviceId,
      recipientDeviceId: body.recipientDeviceId,
      senderUid: authenticatedUid,
    },
  };
}

function isPublishedDevice(value, expectedDeviceId) {
  return (
    isRecord(value) &&
    value.schemaVersion === 1 &&
    value.deviceId === expectedDeviceId &&
    typeof value.ed25519IdentityKey === "string" &&
    value.ed25519IdentityKey.length > 0
  );
}

function isValidEnvelopeRecord(record, expected) {
  if (!isRecord(record)) return false;
  const expectedFields = ["envelope", "messageId", "senderDeviceId", "senderUid"];
  const fields = Object.keys(record).sort();
  if (
    fields.length !== expectedFields.length ||
    fields.some((field, index) => field !== expectedFields[index])
  ) {
    return false;
  }

  if (
    record.senderUid !== expected.senderUid ||
    record.senderDeviceId !== expected.senderDeviceId ||
    record.messageId !== expected.messageId ||
    typeof record.envelope !== "string" ||
    new TextEncoder().encode(record.envelope).length > MAX_ENVELOPE_BYTES
  ) {
    return false;
  }

  let envelope;
  try {
    envelope = JSON.parse(record.envelope);
  } catch (_) {
    return false;
  }

  if (!isRecord(envelope)) return false;
  const expectedEnvelopeFields = [
    "algorithm",
    "ciphertext",
    "encryptionVersion",
    "recipientDeviceId",
    "senderDeviceId",
    "sessionId",
  ];
  const envelopeFields = Object.keys(envelope).sort();
  if (
    envelopeFields.length !== expectedEnvelopeFields.length ||
    envelopeFields.some((field, index) => field !== expectedEnvelopeFields[index])
  ) {
    return false;
  }

  if (
    envelope.encryptionVersion !== 1 ||
    envelope.algorithm !== "olm" ||
    envelope.senderDeviceId !== expected.senderDeviceId ||
    envelope.recipientDeviceId !== expected.recipientDeviceId ||
    typeof envelope.sessionId !== "string" ||
    envelope.sessionId.trim() === "" ||
    envelope.sessionId.length > 128 ||
    typeof envelope.ciphertext !== "string" ||
    !/^[A-Za-z0-9_-]+={0,2}$/.test(envelope.ciphertext)
  ) {
    return false;
  }

  try {
    const normalized = envelope.ciphertext.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
    const decoded = atob(padded);
    if (decoded.length < 2 || decoded.length > MAX_CIPHERTEXT_BYTES) return false;
    const canonical = btoa(decoded)
      .replace(/=/g, "")
      .replace(/\+/g, "-")
      .replace(/\//g, "_");
    const canonicalPadded =
      canonical + "=".repeat((4 - (canonical.length % 4)) % 4);
    return (
      envelope.ciphertext === canonical ||
      envelope.ciphertext === canonicalPadded
    );
  } catch (_) {
    return false;
  }
}

function databaseUrl(env, path) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  const encodedPath = path.split("/").map(encodeURIComponent).join("/");
  return base + "/" + encodedPath + ".json";
}

async function readWithEtag(env, adminToken, path, fetchImpl) {
  const response = await fetchImpl(databaseUrl(env, path), {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
    },
  });
  if (!response.ok) {
    throw new Error("Could not read encrypted push receipt (" + response.status + ").");
  }
  return {
    value: await response.json(),
    etag: response.headers.get("ETag") || "null_etag",
  };
}

async function putWithEtag(env, adminToken, path, etag, value, fetchImpl) {
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
    throw new Error("Could not update encrypted push receipt (" + response.status + ").");
  }
  return true;
}

async function claimPushDispatch(
  env,
  adminToken,
  path,
  senderUid,
  messageId,
  recipientUid,
  recipientDeviceId,
  deliveryId,
  fetchImpl,
  now,
) {
  const snapshot = await readWithEtag(env, adminToken, path, fetchImpl);
  const existing = isRecord(snapshot.value) ? snapshot.value : null;

  if (existing?.status === "sent" || existing?.status === "unregistered") {
    return {
      alreadyProcessed: true,
      notificationSent: existing.status === "sent",
      status: existing.status,
    };
  }

  if (
    existing?.status === "processing" &&
    Number(existing.leaseUntil || 0) > now
  ) {
    return { busy: true };
  }

  const claimId = crypto.randomUUID();
  const attempt = Math.max(0, Number(existing?.attempt || 0)) + 1;
  const next = {
    ...(existing || {}),
    senderUid,
    messageId,
    recipientUid,
    recipientDeviceId,
    deliveryId,
    status: "processing",
    claimId,
    attempt,
    leaseUntil: now + PUSH_LEASE_MILLIS,
    updatedAt: { ".sv": "timestamp" },
  };
  const committed = await putWithEtag(
    env,
    adminToken,
    path,
    snapshot.etag,
    next,
    fetchImpl,
  );
  return committed ? { claimId } : { busy: true };
}

async function finishPushDispatch(
  env,
  adminToken,
  path,
  claimId,
  status,
  fetchImpl,
) {
  const snapshot = await readWithEtag(env, adminToken, path, fetchImpl);
  const current = snapshot.value;
  if (!isRecord(current) || current.claimId !== claimId) return false;

  return putWithEtag(
    env,
    adminToken,
    path,
    snapshot.etag,
    {
      ...current,
      status,
      notificationSent: status === "sent",
      leaseUntil: 0,
      updatedAt: { ".sv": "timestamp" },
      ...(status === "sent" ? { sentAt: { ".sv": "timestamp" } } : {}),
    },
    fetchImpl,
  );
}

async function removeDeviceTokenIfUnchanged(
  env,
  adminToken,
  uid,
  deviceId,
  token,
  fetchImpl,
) {
  const path = "privateUsers/" + uid + "/olmDeviceFcmTokens/" + deviceId;
  const url = databaseUrl(env, path);
  const read = await fetchImpl(url, {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
    },
  });
  if (!read.ok) throw new Error("Could not read stale encrypted push token.");
  const current = await read.json();
  if (current !== token) return false;

  const etag = read.headers.get("ETag");
  if (!etag) throw new Error("Firebase did not return an FCM token ETag.");
  const remove = await fetchImpl(url, {
    method: "DELETE",
    headers: {
      Authorization: "Bearer " + adminToken,
      "If-Match": etag,
    },
  });
  if (remove.status === 412) return false;
  if (!remove.ok) throw new Error("Could not remove an unregistered encrypted push token.");
  return true;
}

/**
 * Dispatches one privacy-preserving data-only wake-up for an already-uploaded
 * Olm relay record. All account/device/chat authorization is rechecked here;
 * the Android caller is not trusted to choose a recipient account or FCM token.
 */
export async function processEncryptedOlmPush({
  env,
  uid,
  body,
  adminToken,
  adminDatabaseRequest,
  fcmRequest,
  fetchImpl = fetch,
  now = Date.now(),
}) {
  const validation = validateRequest(body, uid);
  if (validation.error) return validation.error;
  const input = validation.value;

  const chat = await adminDatabaseRequest(
    env,
    adminToken,
    "chats/" + input.chatId,
  );
  if (!isRecord(chat) || chat.type === "group") {
    return result(404, { error: "Direct conversation not found." });
  }

  const participantA = chat.participantA;
  const participantB = chat.participantB;
  if (
    !UID_PATTERN.test(participantA || "") ||
    !UID_PATTERN.test(participantB || "") ||
    participantA === participantB ||
    (participantA !== uid && participantB !== uid)
  ) {
    return result(403, { error: "Sender is not a participant in this direct conversation." });
  }

  const recipientUid = participantA === uid ? participantB : participantA;
  const canonicalChatId = [uid, recipientUid].sort().join("_");
  if (input.chatId !== canonicalChatId) {
    return result(409, { error: "Conversation ID does not match its participants." });
  }

  const [senderBlockedRecipient, recipientBlockedSender] = await Promise.all([
    adminDatabaseRequest(
      env,
      adminToken,
      "blockedUsers/" + uid + "/" + recipientUid,
    ),
    adminDatabaseRequest(
      env,
      adminToken,
      "blockedUsers/" + recipientUid + "/" + uid,
    ),
  ]);
  if (senderBlockedRecipient || recipientBlockedSender) {
    return result(200, {
      success: true,
      notificationSent: false,
      reason: "blocked",
    });
  }

  const [senderDevice, recipientDevice] = await Promise.all([
    adminDatabaseRequest(
      env,
      adminToken,
      "deviceKeys/" + uid + "/" + input.senderDeviceId,
    ),
    adminDatabaseRequest(
      env,
      adminToken,
      "deviceKeys/" + recipientUid + "/" + input.recipientDeviceId,
    ),
  ]);
  if (
    !isPublishedDevice(senderDevice, input.senderDeviceId) ||
    !isPublishedDevice(recipientDevice, input.recipientDeviceId)
  ) {
    return result(404, { error: "Sender or recipient device is no longer published." });
  }

  const relayPath =
    "olmInbox/" + recipientUid + "/" + input.recipientDeviceId + "/" + input.deliveryId;
  const relayRecord = await adminDatabaseRequest(env, adminToken, relayPath);
  if (!relayRecord) {
    return result(503, {
      error: "Encrypted delivery has not reached the relay yet.",
      retryable: true,
    });
  }

  if (!isValidEnvelopeRecord(relayRecord, {
    senderUid: uid,
    senderDeviceId: input.senderDeviceId,
    recipientDeviceId: input.recipientDeviceId,
    messageId: input.messageId,
  })) {
    return result(409, { error: "Encrypted relay record does not match the authenticated request." });
  }

  const tokenValue = await adminDatabaseRequest(
    env,
    adminToken,
    "privateUsers/" + recipientUid + "/olmDeviceFcmTokens/" + input.recipientDeviceId,
  );
  const registrationToken =
    typeof tokenValue === "string" && tokenValue.trim().length <= 4096 ?
      tokenValue.trim() :
      "";
  if (!registrationToken) {
    return result(200, {
      success: true,
      notificationSent: false,
      reason: "recipient_device_has_no_fcm_token",
    });
  }

  const receiptPath =
    "olmPushDispatch/" + recipientUid + "/" + input.recipientDeviceId + "/" + input.deliveryId;
  const claim = await claimPushDispatch(
    env,
    adminToken,
    receiptPath,
    uid,
    input.messageId,
    recipientUid,
    input.recipientDeviceId,
    input.deliveryId,
    fetchImpl,
    now,
  );

  if (claim.alreadyProcessed) {
    return result(200, {
      success: true,
      notificationSent: claim.notificationSent,
      alreadyProcessed: true,
    });
  }
  if (claim.busy || typeof claim.claimId !== "string") {
    return result(503, {
      success: false,
      retryable: true,
      error: "This encrypted push is already being processed.",
    });
  }

  const fcmUrl =
    "https://fcm.googleapis.com/v1/projects/" +
    encodeURIComponent(adminToken.projectId) +
    "/messages:send";

  let delivery;
  try {
    delivery = await fcmRequest(
      adminToken.token,
      fcmUrl,
      "POST",
      {
        message: {
          token: registrationToken,
          data: {
            type: "encrypted_olm",
            deliveryId: input.deliveryId,
          },
          android: {
            priority: "normal",
            ttl: "86400s",
          },
        },
      },
      [404],
    );
  } catch (error) {
    const completed = await finishPushDispatch(
      env,
      adminToken.token,
      receiptPath,
      claim.claimId,
      "failed",
      fetchImpl,
    );
    if (!completed) {
      return result(503, {
        success: false,
        retryable: true,
        error: "Encrypted push failed and its retry state could not be saved.",
      });
    }
    return result(503, {
      success: false,
      retryable: true,
      error: "Firebase Cloud Messaging could not accept the encrypted push.",
    });
  }

  const unregistered =
    delivery.status === 404 ||
    JSON.stringify(delivery.body || {}).includes("UNREGISTERED");

  if (unregistered) {
    try {
      await removeDeviceTokenIfUnchanged(
        env,
        adminToken.token,
        recipientUid,
        input.recipientDeviceId,
        registrationToken,
        fetchImpl,
      );
    } catch (error) {
      console.warn("Could not clean up an unregistered encrypted FCM token.", error);
    }
  }

  const finalStatus = unregistered ? "unregistered" : "sent";
  const completed = await finishPushDispatch(
    env,
    adminToken.token,
    receiptPath,
    claim.claimId,
    finalStatus,
    fetchImpl,
  );
  if (!completed) {
    return result(503, {
      success: false,
      retryable: true,
      error: "Encrypted push was accepted but its delivery receipt could not be saved.",
    });
  }

  return result(200, {
    success: true,
    notificationSent: !unregistered,
    ...(unregistered ? { reason: "recipient_device_token_unregistered" } : {}),
  });
}
