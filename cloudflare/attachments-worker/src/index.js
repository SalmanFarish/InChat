const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
const MAX_VOICE_BYTES = 1 * 1024 * 1024;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function getBearer(request) {
  const header = request.headers.get("Authorization") || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

function cleanSegment(value, label) {
  if (!value || !/^[A-Za-z0-9_-]{1,120}$/.test(value)) {
    throw new Response("Invalid " + label, { status: 400 });
  }
  return value;
}

function cleanFileName(value) {
  return (value || "attachment")
    .trim()
    .replace(/[\\/\r\n"]/g, "_")
    .slice(0, 180) || "attachment";
}

async function authenticate(token, env) {
  if (!token || !env.FIREBASE_WEB_API_KEY) {
    throw new Response("Unauthorized", { status: 401 });
  }

  const response = await fetch(
    "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" +
      encodeURIComponent(env.FIREBASE_WEB_API_KEY),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: token }),
    },
  );

  if (!response.ok) {
    throw new Response("Unauthorized", { status: 401 });
  }

  const body = await response.json();
  const user = body.users && body.users[0];

  if (!user || typeof user.localId !== "string" || user.localId.length === 0) {
    throw new Response("Unauthorized", { status: 401 });
  }

  return { uid: user.localId, token };
}

async function loadChat(chatId, token, env) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  const url =
    base + "/chats/" + encodeURIComponent(chatId) +
    ".json?auth=" + encodeURIComponent(token);

  const response = await fetch(url, {
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Response("Could not verify chat access", { status: 403 });
  }

  return response.json();
}

function isMember(chat, uid) {
  if (!chat || typeof chat !== "object") return false;

  if (chat.type === "group") {
    return Boolean(
      chat.members &&
      Object.prototype.hasOwnProperty.call(chat.members, uid),
    );
  }

  return chat.participantA === uid || chat.participantB === uid;
}

async function authorizeChat(chatId, uid, token, env) {
  const chat = await loadChat(chatId, token, env);

  if (!isMember(chat, uid)) {
    throw new Response("Forbidden", { status: 403 });
  }

  return chat;
}

async function loadMessage(chatId, messageId, token, env) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  const url =
    base +
    "/chats/" +
    encodeURIComponent(chatId) +
    "/messages/" +
    encodeURIComponent(messageId) +
    ".json?auth=" +
    encodeURIComponent(token);

  const response = await fetch(url, {
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Response("Could not verify message access", { status: 403 });
  }

  const message = await response.json();
  return message && typeof message === "object" ? message : null;
}

async function authorizeMessageSender(
  chatId,
  messageId,
  uid,
  token,
  env,
) {
  const message = await loadMessage(chatId, messageId, token, env);

  if (!message || message.senderId !== uid) {
    throw new Response("Only the message sender can upload this attachment.", {
      status: 403,
    });
  }

  if (message.attachment?.storageKey !== attachmentKey(chatId, messageId)) {
    throw new Response("Message does not reference this attachment.", {
      status: 409,
    });
  }

  return message;
}

let cachedAdminToken = "";
let cachedAdminTokenExpiresAt = 0;
let cachedAdminTokenEmail = "";
let cachedAdminTokenProjectId = "";

function base64Url(value) {
  const bytes = typeof value === "string" ?
    new TextEncoder().encode(value) :
    value;
  let binary = "";
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  return btoa(binary)
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

async function getFirebaseAdminToken(env) {
  let serviceAccount;

  if (env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    try {
      serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
    } catch (_) {
      throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is invalid JSON.");
    }
  } else {
    // Support the split Cloudflare variables already used by the deployed
    // Worker, while also accepting a single service-account JSON secret.
    serviceAccount = {
      client_email: env.FIREBASE_CLIENT_EMAIL,
      private_key: env.FIREBASE_PRIVATE_KEY,
      project_id: env.FIREBASE_PROJECT_ID,
    };
  }

  if (
    typeof serviceAccount.client_email !== "string" ||
    !serviceAccount.client_email.trim() ||
    typeof serviceAccount.private_key !== "string" ||
    !serviceAccount.private_key.trim() ||
    typeof serviceAccount.project_id !== "string" ||
    !serviceAccount.project_id.trim()
  ) {
    throw new Error(
      "Configure FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_CLIENT_EMAIL, " +
      "FIREBASE_PRIVATE_KEY and FIREBASE_PROJECT_ID.",
    );
  }

  const clientEmail = serviceAccount.client_email.trim();
  const projectId = serviceAccount.project_id.trim();
  const normalizedPrivateKey = serviceAccount.private_key
    .trim()
    .replace(/\\n/g, "\n")
    .replace(/\\r/g, "")
    .replace(/\r/g, "");

  const now = Math.floor(Date.now() / 1000);
  if (
    cachedAdminToken &&
    cachedAdminTokenEmail === clientEmail &&
    cachedAdminTokenProjectId === projectId &&
    cachedAdminTokenExpiresAt > now + 60
  ) {
    return {
      token: cachedAdminToken,
      projectId: projectId,
    };
  }

  const keyMatch = normalizedPrivateKey.match(
    /-----BEGIN PRIVATE KEY-----([\s\S]*?)-----END PRIVATE KEY-----/,
  );
  if (!keyMatch) {
    throw new Error("FIREBASE_PRIVATE_KEY must be a PKCS#8 PEM private key.");
  }

  const pem = keyMatch[1].replace(/\s/g, "");
  const keyBytes = Uint8Array.from(
    atob(pem),
    (character) => character.charCodeAt(0),
  );

  const privateKey = await crypto.subtle.importKey(
    "pkcs8",
    keyBytes,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"],
  );

  const header = base64Url(JSON.stringify({
    alg: "RS256",
    typ: "JWT",
  }));
  const claim = base64Url(JSON.stringify({
    iss: clientEmail,
    scope:
      "https://www.googleapis.com/auth/userinfo.email " +
      "https://www.googleapis.com/auth/firebase.database " +
      "https://www.googleapis.com/auth/firebase.messaging " +
      "https://www.googleapis.com/auth/identitytoolkit",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = header + "." + claim;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(unsigned),
  );
  const assertion = unsigned + "." + base64Url(new Uint8Array(signature));

  const tokenResponse = await fetch(
    "https://oauth2.googleapis.com/token",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        grant_type:
          "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: assertion,
      }),
    },
  );

  const tokenBody = await tokenResponse.json();
  if (!tokenResponse.ok || !tokenBody.access_token) {
    console.error("Firebase service-account token exchange failed", tokenBody);
    throw new Error("Could not authorize with Firebase.");
  }

  cachedAdminToken = tokenBody.access_token;
  cachedAdminTokenEmail = clientEmail;
  cachedAdminTokenProjectId = projectId;
  cachedAdminTokenExpiresAt = now + Number(tokenBody.expires_in || 3600);

  return {
    token: cachedAdminToken,
    projectId: projectId,
  };
}

async function adminDatabaseRequest(
  env,
  accessToken,
  path,
  method = "GET",
  body = undefined,
  query = "",
) {
  const cleanPath = path ?
    "/" + path.split("/").map(encodeURIComponent).join("/") :
    "";
  const queryString = query ? "?" + query : "";
  const response = await fetch(
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
      cleanPath +
      ".json" +
      queryString,
    {
      method: method,
      headers: {
        Authorization: "Bearer " + accessToken,
        ...(body === undefined ?
          {} :
          { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
  );

  if (!response.ok) {
    const details = await response.text();
    throw new Error(
      "Realtime Database request failed (" +
      response.status +
      "): " +
      details.slice(0, 300),
    );
  }

  if (response.status === 204) return null;
  const responseText = await response.text();
  return responseText ? JSON.parse(responseText) : null;
}

async function claimMessageEvent(
  env,
  adminToken,
  chatId,
  messageId,
  senderId,
) {
  const receiptPath =
    "/chats/" +
    encodeURIComponent(chatId) +
    "/messages/" +
    encodeURIComponent(messageId) +
    "/workerProcessed.json";
  const receiptUrl =
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") + receiptPath;

  const readResponse = await fetch(receiptUrl, {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
    },
  });

  if (!readResponse.ok) {
    throw new Error(
      "Could not check notification event receipt (" +
      readResponse.status +
      ").",
    );
  }

  const existing = await readResponse.json();
  if (existing !== null) return false;

  const etag = readResponse.headers.get("ETag") || "null_etag";
  const writeResponse = await fetch(receiptUrl, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify({
      senderId: senderId,
      processedAt: { ".sv": "timestamp" },
    }),
  });

  if (writeResponse.status === 412) return false;
  if (!writeResponse.ok) {
    throw new Error(
      "Could not claim notification event (" +
      writeResponse.status +
      ").",
    );
  }

  return true;
}

async function fcmRequest(
  accessToken,
  url,
  method,
  body,
  toleratedStatuses = [],
) {
  const response = await fetch(url, {
    method: method,
    headers: {
      Authorization: "Bearer " + accessToken,
      "Content-Type": "application/json",
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  const responseText = await response.text();
  let responseBody = {};
  try {
    responseBody = responseText ? JSON.parse(responseText) : {};
  } catch (_) {
    responseBody = { message: responseText.slice(0, 300) };
  }

  if (!response.ok && !toleratedStatuses.includes(response.status)) {
    console.error("FCM request failed", {
      status: response.status,
      response: responseBody,
    });
    throw new Error("FCM request failed (" + response.status + ").");
  }

  return {
    ok: response.ok || toleratedStatuses.includes(response.status),
    status: response.status,
    body: responseBody,
  };
}

function notificationText(value) {
  const characters = Array.from(
    typeof value === "string" ? value.trim() : "",
  );
  if (characters.length <= 700) return characters.join("");
  return characters.slice(0, 700).join("") + "…";
}

async function privateTopicName(env, kind, identity) {
  const secret = env.FCM_TOPIC_SECRET;
  if (typeof secret !== "string" || secret.length < 32) {
    throw new Error("FCM_TOPIC_SECRET must contain at least 32 characters.");
  }

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const digest = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(kind + ":" + identity),
  );

  return "inchat_" + kind + "_" + base64Url(new Uint8Array(digest));
}

function groupTopicName(env, chatId) {
  return privateTopicName(env, "group", chatId);
}

function userTopicName(env, uid) {
  return privateTopicName(env, "user", uid);
}

async function changeTopicSubscription(
  accessToken,
  projectId,
  topic,
  registrationToken,
  subscribe,
) {
  const registration = encodeURIComponent(registrationToken);
  const base =
    "https://fcm.googleapis.com/v1/projects/" +
    encodeURIComponent(projectId) +
    "/registrations/" +
    registration +
    "/topicSubscriptions";

  if (subscribe) {
    const result = await fcmRequest(
      accessToken,
      base + "?topic_name=" + encodeURIComponent(topic),
      "POST",
      {},
      [409],
    );
    return {
      subscribed: result.status !== 409,
      alreadySubscribed: result.status === 409,
    };
  }

  await fcmRequest(
    accessToken,
    base + "/" + encodeURIComponent(topic),
    "DELETE",
    undefined,
    [404],
  );
  return { unsubscribed: true };
}

async function updateGroupTopic(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const chatId = cleanSegment(body.chatId, "chatId");
  const targetUid = cleanSegment(body.targetUid, "targetUid");
  const subscribe = body.subscribe === true;

  if (typeof body.subscribe !== "boolean") {
    return json({ error: "subscribe must be a boolean." }, 400);
  }

  const chat = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "chats/" + chatId,
  );

  if (!chat || chat.type !== "group") {
    return json({ error: "Group not found." }, 404);
  }

  const actorRole = chat.members && chat.members[uid];
  const targetRole = chat.members && chat.members[targetUid];

  if (subscribe) {
    if (
      !targetRole ||
      (uid !== targetUid && actorRole !== "admin")
    ) {
      return json({
        error: "Only an admin can subscribe an existing group member.",
      }, 403);
    }
  } else if (uid !== targetUid && actorRole !== "admin") {
    return json({
      error: "Only the user or a group admin can remove this topic.",
    }, 403);
  }

  const registrationToken = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "privateUsers/" + targetUid + "/fcmToken",
  );

  if (
    typeof registrationToken !== "string" ||
    registrationToken.trim() === ""
  ) {
    return json({
      success: true,
      skipped: true,
      reason: "No FCM token is registered for this member.",
    });
  }

  const groupTopic = await groupTopicName(env, chatId);
  if (subscribe) {
    await changeTopicSubscription(
      adminAuth.token,
      adminAuth.projectId,
      await userTopicName(env, targetUid),
      registrationToken,
      true,
    );
  }

  const result = await changeTopicSubscription(
    adminAuth.token,
    adminAuth.projectId,
    groupTopic,
    registrationToken,
    subscribe,
  );

  return json({
    success: true,
    chatId: chatId,
    targetUid: targetUid,
    ...result,
  });
}

async function clearPersonalNotificationTopic(
  env,
  uid,
  adminAuth,
) {
  const registrationToken = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "privateUsers/" + uid + "/fcmToken",
  );

  if (
    typeof registrationToken !== "string" ||
    registrationToken.trim() === ""
  ) {
    return json({
      success: true,
      skipped: true,
      reason: "No FCM token is registered for this account.",
    });
  }

  await changeTopicSubscription(
    adminAuth.token,
    adminAuth.projectId,
    await userTopicName(env, uid),
    registrationToken,
    false,
  );

  return json({ success: true });
}

async function syncGroupConversationPreview(
  env,
  adminToken,
  chatId,
  chat,
  message,
  messageId,
) {
  const members = Object.keys(chat.members || {});
  if (members.length < 2) return;

  const latestMessages = await adminDatabaseRequest(
    env,
    adminToken,
    "chats/" + chatId + "/messages",
    "GET",
    undefined,
    "orderBy=%22timestamp%22&limitToLast=1",
  );

  let latestId = "";
  let latestMessage = null;
  for (const entryId of Object.keys(latestMessages || {})) {
    const entry = latestMessages[entryId];
    if (
      entry &&
      typeof entry.timestamp === "number" &&
      (!latestMessage || entry.timestamp > latestMessage.timestamp)
    ) {
      latestId = entryId;
      latestMessage = entry;
    }
  }

  const updates = {};
  if (latestId === messageId && latestMessage) {
    const isSystemEvent =
      typeof latestMessage.systemEvent === "string" &&
      latestMessage.systemEvent.trim() !== "";
    const groupName = typeof chat.name === "string" ?
      chat.name.trim() :
      "";

    for (const memberId of members) {
      const base = "userChats/" + memberId + "/" + chatId + "/";
      updates[base + "chatId"] = chatId;
      updates[base + "chatType"] = "group";
      updates[base + "groupName"] = groupName;
      updates[base + "memberCount"] = members.length;
      updates[base + "otherUserId"] = "";
      updates[base + "otherUsername"] = "";
      updates[base + "lastMessage"] =
        typeof latestMessage.text === "string" ?
          latestMessage.text :
          "";
      updates[base + "lastTimestamp"] = latestMessage.timestamp;
      updates[base + "lastSenderId"] =
        isSystemEvent ? "" : (latestMessage.senderId || "");
      updates[base + "lastSenderNickname"] =
        isSystemEvent ? "" : (latestMessage.senderNickname || "");
    }
  }

  const isSystemEvent =
    typeof message.systemEvent === "string" &&
    message.systemEvent.trim() !== "";

  if (!isSystemEvent) {
    for (const memberId of members) {
      if (memberId === message.senderId) continue;
      updates[
        "userChats/" + memberId + "/" + chatId + "/unreadCount"
      ] = { ".sv": { increment: 1 } };
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminDatabaseRequest(
      env,
      adminToken,
      "",
      "PATCH",
      updates,
    );
  }
}


async function rebuildConversationPreview(
  env,
  adminToken,
  chatId,
  chat,
) {
  const messages = await adminDatabaseRequest(
    env,
    adminToken,
    "chats/" + chatId + "/messages",
    "GET",
    undefined,
    "orderBy=%22timestamp%22&limitToLast=1",
  );

  let latest = null;
  for (const messageId of Object.keys(messages || {})) {
    const message = messages[messageId];
    if (
      message &&
      typeof message.timestamp === "number" &&
      (!latest || message.timestamp > latest.timestamp)
    ) {
      latest = message;
    }
  }

  const updates = {};
  if (chat.type === "group") {
    const members = Object.keys(chat.members || {});
    const isSystemEvent = !!(
      latest &&
      typeof latest.systemEvent === "string" &&
      latest.systemEvent.trim() !== ""
    );

    for (const memberId of members) {
      const base = "userChats/" + memberId + "/" + chatId + "/";
      updates[base + "chatId"] = chatId;
      updates[base + "chatType"] = "group";
      updates[base + "groupName"] =
        typeof chat.name === "string" ? chat.name.trim() : "";
      updates[base + "memberCount"] = members.length;
      updates[base + "otherUserId"] = "";
      updates[base + "otherUsername"] = "";
      updates[base + "lastMessage"] =
        typeof latest?.text === "string" ? latest.text : "";
      updates[base + "lastTimestamp"] = latest?.timestamp || 0;
      updates[base + "lastSenderId"] =
        !isSystemEvent && latest?.senderId ? latest.senderId : "";
      updates[base + "lastSenderNickname"] =
        !isSystemEvent && latest?.senderNickname ?
          latest.senderNickname :
          "";
    }
  } else {
    const participantA = chat.participantA;
    const participantB = chat.participantB;
    if (
      typeof participantA !== "string" ||
      typeof participantB !== "string" ||
      participantA === "" ||
      participantB === "" ||
      participantA === participantB
    ) {
      throw new Error("Cannot rebuild preview for invalid direct chat.");
    }

    const lastMessage =
      typeof latest?.text === "string" && latest.text.trim() !== "" ?
        latest.text :
        "No messages yet";
    const lastTimestamp = latest?.timestamp || 0;
    const lastSenderId = latest?.senderId || participantA;

    for (const memberId of [participantA, participantB]) {
      const existing = await adminDatabaseRequest(
        env,
        adminToken,
        "userChats/" + memberId + "/" + chatId,
      );
      if (!existing) continue;

      const base = "userChats/" + memberId + "/" + chatId + "/";
      updates[base + "lastMessage"] = lastMessage;
      updates[base + "lastTimestamp"] = lastTimestamp;
      updates[base + "lastSenderId"] = lastSenderId;
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminDatabaseRequest(env, adminToken, "", "PATCH", updates);
  }
}


/*
 * Create/update both direct-chat inbox entries from the trusted Worker.
 * A client must never be allowed to write another user's inbox metadata.
 */
async function syncDirectInboxForNewMessage(
  env,
  adminToken,
  chatId,
  chat,
  message,
) {
  const participantA = chat.participantA;
  const participantB = chat.participantB;
  const senderId = message.senderId;

  if (
    typeof participantA !== "string" ||
    typeof participantB !== "string" ||
    participantA === "" ||
    participantB === "" ||
    participantA === participantB ||
    (senderId !== participantA && senderId !== participantB)
  ) {
    throw new Error("Cannot update inbox for an invalid direct chat.");
  }

  const recipientId = senderId === participantA ? participantB : participantA;
  const [
    senderProfile,
    recipientProfile,
    senderInbox,
    recipientInbox,
  ] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "users/" + senderId),
    adminDatabaseRequest(env, adminToken, "users/" + recipientId),
    adminDatabaseRequest(env, adminToken, "userChats/" + senderId + "/" + chatId),
    adminDatabaseRequest(env, adminToken, "userChats/" + recipientId + "/" + chatId),
  ]);

  const senderNickname =
    typeof senderProfile?.username === "string" &&
    senderProfile.username.trim() !== "" ?
      senderProfile.username.trim().slice(0, 20) :
      (typeof message.senderNickname === "string" ?
        message.senderNickname.trim().slice(0, 20) :
        "User");

  const recipientNickname =
    typeof recipientProfile?.username === "string" &&
    recipientProfile.username.trim() !== "" ?
      recipientProfile.username.trim().slice(0, 20) :
      "User";

  const lastMessage =
    typeof message.text === "string" && message.text.trim() !== "" ?
      message.text.slice(0, 5000) :
      "Sent an attachment";
  const lastTimestamp =
    typeof message.timestamp === "number" && Number.isFinite(message.timestamp) ?
      message.timestamp :
      Date.now();

  const updates = {};
  const senderBase = "userChats/" + senderId + "/" + chatId + "/";
  const recipientBase = "userChats/" + recipientId + "/" + chatId + "/";

  for (const [base, otherUserId, otherUsername] of [
    [senderBase, recipientId, recipientNickname],
    [recipientBase, senderId, senderNickname],
  ]) {
    updates[base + "chatId"] = chatId;
    updates[base + "otherUserId"] = otherUserId;
    updates[base + "otherUsername"] = otherUsername || "User";
    updates[base + "lastMessage"] = lastMessage;
    updates[base + "lastTimestamp"] = lastTimestamp;
    updates[base + "lastSenderId"] = senderId;
  }

  // Preserve an existing sender unread count; create new senders' entries at zero.
  if (!senderInbox || typeof senderInbox.unreadCount !== "number") {
    updates[senderBase + "unreadCount"] = 0;
  }

  // Increment the recipient exactly once for this claimed message event.
  if (recipientInbox && typeof recipientInbox.unreadCount === "number") {
    updates[recipientBase + "unreadCount"] = { ".sv": { increment: 1 } };
  } else {
    updates[recipientBase + "unreadCount"] = 1;
  }

  await adminDatabaseRequest(env, adminToken, "", "PATCH", updates);
}

async function processPreviewEvent(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const chatId = cleanSegment(body.chatId, "chatId");
  const chat = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "chats/" + chatId,
  );

  if (!chat) return json({ error: "Chat not found." }, 404);

  if (chat.type === "group") {
    if (!chat.members || !chat.members[uid]) {
      return json({ error: "You are not a group member." }, 403);
    }
  } else if (
    chat.participantA !== uid &&
    chat.participantB !== uid
  ) {
    return json({ error: "You are not a chat participant." }, 403);
  }

  await rebuildConversationPreview(
    env,
    adminAuth.token,
    chatId,
    chat,
  );

  return json({
    success: true,
    chatId: chatId,
  });
}

async function processMessageEvent(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const chatId = cleanSegment(body.chatId, "chatId");
  const messageId = cleanSegment(body.messageId, "messageId");

  const chat = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "chats/" + chatId,
  );
  const message = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "chats/" + chatId + "/messages/" + messageId,
  );

  if (!chat || !message || message.senderId !== uid) {
    return json({ error: "Message or chat not found for this sender." }, 403);
  }

  const isGroup = chat.type === "group";
  const systemEvent = typeof message.systemEvent === "string" ?
    message.systemEvent.trim() :
    "";

  if (isGroup) {
    const isMember = Boolean(chat.members && chat.members[uid]);
    if (!isMember && systemEvent !== "member_left") {
      return json({ error: "Sender is not a current group member." }, 403);
    }
    if (systemEvent && !isMember && systemEvent !== "member_left") {
      return json({ error: "Invalid group activity sender." }, 403);
    }
  } else {
    const participantA = chat.participantA;
    const participantB = chat.participantB;
    if (participantA !== uid && participantB !== uid) {
      return json({ error: "Sender is not a chat participant." }, 403);
    }
  }

  const claimed = await claimMessageEvent(
    env,
    adminAuth.token,
    chatId,
    messageId,
    uid,
  );

  if (!claimed) {
    return json({
      success: true,
      alreadyProcessed: true,
      notificationSent: false,
    });
  }

  if (isGroup) {
    try {
      await syncGroupConversationPreview(
        env,
        adminAuth.token,
        chatId,
        chat,
        message,
        messageId,
      );
    } catch (previewError) {
      console.error("Group preview sync failed", {
        chatId: chatId,
        messageId: messageId,
        error: String(previewError),
      });
    }

    if (systemEvent) {
      return json({
        success: true,
        notificationSent: false,
        reason: "Group activity messages are not push-notified.",
      });
    }
  } else {
    try {
      await syncDirectInboxForNewMessage(
        env,
        adminAuth.token,
        chatId,
        chat,
        message,
      );
    } catch (previewError) {
      console.error("Direct inbox preview sync failed", {
        chatId: chatId,
        messageId: messageId,
        error: String(previewError),
      });
    }
  }

  const messageText = notificationText(
    typeof message.text === "string" && message.text.trim() !== "" ?
      message.text :
      "Sent an attachment",
  );
  const senderNickname =
    typeof message.senderNickname === "string" &&
    message.senderNickname.trim() !== "" ?
      message.senderNickname.trim() :
      "InChat user";

  if (!isGroup) {
    const receiverId =
      chat.participantA === uid ?
        chat.participantB :
        chat.participantA;

    const senderBlockedReceiver = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "blockedUsers/" + uid + "/" + receiverId,
    );
    const receiverBlockedSender = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "blockedUsers/" + receiverId + "/" + uid,
    );

    if (senderBlockedReceiver || receiverBlockedSender) {
      return json({
        success: true,
        notificationSent: false,
        reason: "Notifications are disabled for blocked users.",
      });
    }

    const receiverToken = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "privateUsers/" + receiverId + "/fcmToken",
    );
    if (
      typeof receiverToken !== "string" ||
      receiverToken.trim() === ""
    ) {
      return json({
        success: true,
        notificationSent: false,
        reason: "The receiver has no registered FCM token.",
      });
    }

    const notification = {
      message: {
        token: receiverToken,
        data: {
          type: "chat_message",
          chatId: chatId,
          messageId: messageId,
          senderId: uid,
          senderName: senderNickname,
          message: messageText,
        },
        android: {
          priority: "high",
        },
      },
    };

    const result = await fcmRequest(
      adminAuth.token,
      "https://fcm.googleapis.com/v1/projects/" +
        encodeURIComponent(adminAuth.projectId) +
        "/messages:send",
      "POST",
      notification,
      [404],
    );

    if (
      result.status === 404 ||
      JSON.stringify(result.body).includes("UNREGISTERED")
    ) {
      await adminDatabaseRequest(
        env,
        adminAuth.token,
        "privateUsers/" + receiverId + "/fcmToken",
        "DELETE",
      );
    }

    return json({
      success: true,
      notificationSent: result.ok,
      chatId: chatId,
      messageId: messageId,
    });
  }

  const groupName =
    typeof chat.name === "string" && chat.name.trim() !== "" ?
      chat.name.trim() :
      "Group";

  const result = await fcmRequest(
    adminAuth.token,
    "https://fcm.googleapis.com/v1/projects/" +
      encodeURIComponent(adminAuth.projectId) +
      "/messages:send",
    "POST",
    {
      message: {
        condition:
          "'" + await groupTopicName(env, chatId) +
          "' in topics && !('" +
          await userTopicName(env, uid) + "' in topics)",
        data: {
          type: "group_message",
          chatId: chatId,
          messageId: messageId,
          senderId: uid,
          senderName: senderNickname,
          groupName: groupName,
          message: messageText,
        },
        android: {
          priority: "high",
        },
      },
    },
  );

  return json({
    success: true,
    notificationSent: result.ok,
    chatId: chatId,
    messageId: messageId,
  });
}


/*
 * =========================================================
 * PASSWORD RECOVERY (legacy InChat recovery-code flow)
 * =========================================================
 *
 * This endpoint intentionally does not require a signed-in Firebase user.
 * A one-time recovery code is consumed with an ETag-guarded whole-record PUT
 * so two concurrent requests cannot both use the same code.
 */
function normalizeRecoveryCode(code) {
  return code.trim().toUpperCase().replace(/-/g, "").replace(/^INCH/, "");
}

function hexToBytes(hex) {
  if (hex.length % 2 !== 0 || !/^[0-9a-fA-F]+$/.test(hex)) {
    throw new Error("Invalid recovery-code salt.");
  }

  const bytes = new Uint8Array(hex.length / 2);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function bytesToHex(bytes) {
  return Array.from(bytes)
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

async function hashRecoveryCode(saltHex, recoveryCode) {
  const salt = hexToBytes(saltHex);
  const codeBytes = new TextEncoder().encode(normalizeRecoveryCode(recoveryCode));
  const combined = new Uint8Array(salt.length + codeBytes.length);
  combined.set(salt, 0);
  combined.set(codeBytes, salt.length);

  const digest = await crypto.subtle.digest("SHA-256", combined);
  return bytesToHex(new Uint8Array(digest));
}

function constantTimeEquals(first, second) {
  if (typeof first !== "string" || typeof second !== "string") return false;
  if (first.length !== second.length) return false;

  let difference = 0;
  for (let index = 0; index < first.length; index += 1) {
    difference |=
      first.toLowerCase().charCodeAt(index) ^
      second.toLowerCase().charCodeAt(index);
  }
  return difference === 0;
}

async function consumeRecoveryCode(
  env,
  adminToken,
  uid,
  index,
  expectedHash,
) {
  const url =
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
    "/recoveryCodes/" +
    encodeURIComponent(uid) +
    ".json";

  const readResponse = await fetch(url, {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
    },
  });

  if (!readResponse.ok) {
    throw new Error("Could not read recovery-code state.");
  }

  const currentData = await readResponse.json();
  const currentHash = currentData?.codes?.[String(index)];
  if (!constantTimeEquals(currentHash, expectedHash)) return false;

  const etag = readResponse.headers.get("ETag");
  if (!etag) throw new Error("Firebase did not return a recovery-code ETag.");

  const nextData = {
    ...currentData,
    codes: {
      ...currentData.codes,
      [String(index)]: null,
    },
  };

  const writeResponse = await fetch(url, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify(nextData),
  });

  if (writeResponse.status === 412) return false;
  if (!writeResponse.ok) {
    throw new Error("Could not atomically consume the recovery code.");
  }

  return true;
}

async function updateFirebasePassword(
  projectId,
  uid,
  newPassword,
  adminToken,
) {
  const response = await fetch(
    "https://identitytoolkit.googleapis.com/v1/projects/" +
      encodeURIComponent(projectId) +
      "/accounts:update",
    {
      method: "POST",
      headers: {
        Authorization: "Bearer " + adminToken,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        localId: uid,
        password: newPassword,
        returnSecureToken: false,
      }),
    },
  );

  if (!response.ok) {
    const details = await response.text();
    console.error("Firebase password update failed", {
      status: response.status,
      response: details.slice(0, 300),
    });
    throw new Error("Firebase Authentication could not update the password.");
  }
}

async function recoverPassword(request, env, adminAuth) {
  try {
    let body;
    try {
      body = await request.json();
    } catch (_) {
      return json({ error: "Invalid JSON body." }, 400);
    }

    const username =
      typeof body.username === "string" ? body.username.trim() : "";
    const recoveryCode =
      typeof body.recoveryCode === "string" ? body.recoveryCode.trim() : "";
    const newPassword =
      typeof body.newPassword === "string" ? body.newPassword : "";

    if (!username || !recoveryCode || !newPassword) {
      return json({
        error: "Username, recovery code and new password are required.",
      }, 400);
    }

    if (!/^[a-zA-Z0-9_.]{3,20}$/.test(username)) {
      return json({ error: "Invalid username or recovery code." }, 401);
    }

    if (newPassword.length < 6 || newPassword.length > 4096) {
      return json({ error: "Password must be between 6 and 4096 characters." }, 400);
    }

    const uid = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "usernames/" + username.toLowerCase(),
    );

    // Keep the response identical for unknown usernames and invalid codes.
    if (typeof uid !== "string" || !uid.trim()) {
      return json({ error: "Invalid username or recovery code." }, 401);
    }

    const cleanUid = uid.trim();
    const recoveryData = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "recoveryCodes/" + cleanUid,
    );

    if (
      !recoveryData ||
      typeof recoveryData.salt !== "string" ||
      !/^[0-9a-fA-F]{32}$/.test(recoveryData.salt) ||
      !recoveryData.codes ||
      typeof recoveryData.codes !== "object"
    ) {
      return json({ error: "Invalid username or recovery code." }, 401);
    }

    const suppliedHash = await hashRecoveryCode(
      recoveryData.salt,
      recoveryCode,
    );

    let matchedIndex = -1;
    let matchedHash = "";
    for (let index = 0; index < 5; index += 1) {
      const storedHash = recoveryData.codes[String(index)];
      if (
        typeof storedHash === "string" &&
        constantTimeEquals(suppliedHash, storedHash)
      ) {
        matchedIndex = index;
        matchedHash = storedHash;
        break;
      }
    }

    if (matchedIndex < 0) {
      return json({ error: "Invalid username or recovery code." }, 401);
    }

    const consumed = await consumeRecoveryCode(
      env,
      adminAuth.token,
      cleanUid,
      matchedIndex,
      matchedHash,
    );

    if (!consumed) {
      return json({
        error: "This recovery code has already been used. Try another code.",
      }, 401);
    }

    try {
      await updateFirebasePassword(
        adminAuth.projectId,
        cleanUid,
        newPassword,
        adminAuth.token,
      );
    } catch (error) {
      // The one-time code stays consumed; do not silently make it reusable.
      console.error("Password recovery could not finish after code consumption", {
        uid: cleanUid,
        error: String(error),
      });
      return json({
        error:
          "The recovery code was consumed, but the password could not be changed. " +
          "Contact support before trying another code.",
      }, 500);
    }

    console.log("Password recovery completed", { uid: cleanUid });
    return json({
      success: true,
      message: "Password changed successfully.",
    });
  } catch (error) {
    console.error("Password recovery failed", String(error));
    return json({ error: "Could not complete password recovery." }, 500);
  }
}

function attachmentKey(chatId, messageId) {
  return "attachments/" + chatId + "/" + messageId;
}

function bytesToBase64(bytes) {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function upload(request, env, uid, chatId, messageId, message, adminToken) {
  const contentLength = Number(request.headers.get("Content-Length") || "0");
  const contentType =
    request.headers.get("Content-Type") || "application/octet-stream";
  const attachmentType =
    request.headers.get("X-InChat-Attachment-Type") || "file";
  const fileName = cleanFileName(
    request.headers.get("X-InChat-File-Name"),
  );

  if (attachmentType !== "voice" && attachmentType !== "view_once_photo") {
    return json({ error: "Unsupported attachment type." }, 400);
  }

  const key = attachmentKey(chatId, messageId);
  if (
    message.attachment?.storageKey !== key ||
    message.attachment?.type !== attachmentType
  ) {
    return json({ error: "Attachment metadata does not match this message." }, 409);
  }

  const maxBytes =
    attachmentType === "voice" ? MAX_VOICE_BYTES : MAX_PHOTO_BYTES;

  if (!Number.isFinite(contentLength) || contentLength <= 0) {
    return json({ error: "Content-Length is required." }, 400);
  }

  if (contentLength > maxBytes) {
    return json({ error: "Attachment is too large." }, 413);
  }

  if (attachmentType === "voice" && !contentType.startsWith("audio/")) {
    return json({ error: "Voice attachments must be audio files." }, 400);
  }

  if (attachmentType === "view_once_photo" && !contentType.startsWith("image/")) {
    return json({ error: "View-once attachments must be images." }, 400);
  }

  const attachmentPath = "attachments/" + chatId + "/" + messageId;
  const existing = await adminDatabaseRequest(
    env,
    adminToken,
    attachmentPath,
  );
  if (existing !== null) {
    return json({ error: "Attachment already exists." }, 409);
  }

  if (!request.body) {
    return json({ error: "Empty upload." }, 400);
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength !== contentLength) {
    return json({ error: "Upload size did not match Content-Length." }, 400);
  }

  await adminDatabaseRequest(
    env,
    adminToken,
    attachmentPath,
    "PUT",
    {
      data: bytesToBase64(bytes),
      contentType,
      fileName,
      attachmentType,
      senderId: uid,
      chatId,
      messageId,
      createdAt: { ".sv": "timestamp" },
    },
  );

  return json({
    success: true,
    key,
    size: bytes.byteLength,
    contentType,
    fileName,
    attachmentType,
  }, 201);
}

async function download(env, chatId, messageId, uid, adminToken) {
  const attachmentPath = "attachments/" + chatId + "/" + messageId;
  const url =
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
    "/" + attachmentPath.split("/").map(encodeURIComponent).join("/") +
    ".json";
  const response = await fetch(url, {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
    },
  });

  if (response.status === 404) {
    return new Response("Attachment not found", { status: 404 });
  }
  if (!response.ok) {
    throw new Error("Could not read attachment from Realtime Database.");
  }

  const record = await response.json();
  if (!record || typeof record.data !== "string") {
    return new Response("Attachment not found", { status: 404 });
  }

  if (record.attachmentType === "view_once_photo" && record.senderId === uid) {
    return new Response("Sender cannot consume a view-once photo", { status: 403 });
  }

  if (record.attachmentType === "view_once_photo") {
    const etag = response.headers.get("ETag");
    if (!etag) throw new Error("Firebase did not return an attachment ETag.");
    const consumeResponse = await fetch(url, {
      method: "DELETE",
      headers: {
        Authorization: "Bearer " + adminToken,
        "If-Match": etag,
      },
    });
    if (consumeResponse.status === 412) {
      return new Response("This view-once photo has already been opened.", {
        status: 410,
      });
    }
    if (!consumeResponse.ok) {
      throw new Error("Could not consume the view-once attachment.");
    }
  }

  const headers = new Headers();
  headers.set("Content-Type", record.contentType || "application/octet-stream");
  headers.set("Cache-Control", "private, no-store");
  headers.set("Content-Disposition", 'attachment; filename="' +
    cleanFileName(record.fileName) + '"');
  headers.set("X-InChat-File-Name", record.fileName || "attachment");
  headers.set("X-InChat-Attachment-Type", record.attachmentType || "voice");

  if (record.attachmentType === "view_once_photo") {
    headers.set("X-InChat-View-Once", "true");
  }

  return new Response(base64ToBytes(record.data), { status: 200, headers });
}

async function remove(env, chatId, messageId, uid, adminToken) {
  const attachmentPath = "attachments/" + chatId + "/" + messageId;
  const record = await adminDatabaseRequest(
    env,
    adminToken,
    attachmentPath,
  );

  if (!record) {
    return json({ success: true, deleted: false });
  }

  if (
    record.senderId !== uid ||
    record.chatId !== chatId ||
    record.messageId !== messageId
  ) {
    return new Response("Only the attachment sender can delete this file.", {
      status: 403,
    });
  }

  await adminDatabaseRequest(
    env,
    adminToken,
    attachmentPath,
    "DELETE",
  );
  return json({ success: true, deleted: true });
}
export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers":
              "Authorization, Content-Type, X-InChat-Attachment-Type, X-InChat-File-Name",
            "Access-Control-Allow-Methods": "GET, PUT, DELETE, POST, OPTIONS",
          },
        });
      }

      const url = new URL(request.url);

      if (
        request.method === "POST" &&
        (url.pathname === "/recover" || url.pathname === "/v1/recover")
      ) {
        // Recovery is intentionally unauthenticated. Apply both per-IP and
        // per-account throttles before performing privileged Firebase work.
        const recoveryBody = await request.clone().json().catch(() => ({}));
        const recoveryUsername =
          typeof recoveryBody.username === "string" ?
            recoveryBody.username.trim().toLowerCase() :
            "";
        const sourceIp = request.headers.get("CF-Connecting-IP") || "unknown";

        const [ipLimit, usernameLimit] = await Promise.all([
          env.RECOVERY_IP_LIMITER.limit({ key: sourceIp }),
          recoveryUsername ?
            env.RECOVERY_USERNAME_LIMITER.limit({ key: recoveryUsername }) :
            Promise.resolve({ success: true }),
        ]);

        if (!ipLimit.success || !usernameLimit.success) {
          return json({
            error: "Too many password recovery attempts. Please wait and try again.",
          }, 429);
        }

        const adminAuth = await getFirebaseAdminToken(env);
        return recoverPassword(request, env, adminAuth);
      }

      const user = await authenticate(getBearer(request), env);

      if (
        request.method === "POST" &&
        url.pathname === "/v1/events/message"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return processMessageEvent(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/groups/topic"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return updateGroupTopic(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/events/preview"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return processPreviewEvent(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/notifications/personal-topic"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return clearPersonalNotificationTopic(
          env,
          user.uid,
          adminAuth,
        );
      }

      const match = url.pathname.match(
        /^\/v1\/attachments\/([^/]+)\/([^/]+)$/,
      );

      if (!match) return json({ error: "Not found" }, 404);

      const chatId = cleanSegment(match[1], "chatId");
      const messageId = cleanSegment(match[2], "messageId");

      await authorizeChat(chatId, user.uid, user.token, env);

      if (request.method === "PUT") {
        const message = await authorizeMessageSender(
          chatId,
          messageId,
          user.uid,
          user.token,
          env,
        );
        const adminAuth = await getFirebaseAdminToken(env);
        return upload(
          request,
          env,
          user.uid,
          chatId,
          messageId,
          message,
          adminAuth.token,
        );
      }

      if (request.method === "GET") {
        const message = await loadMessage(
          chatId,
          messageId,
          user.token,
          env,
        );

        if (
          !message ||
          message.attachment?.storageKey !== attachmentKey(chatId, messageId)
        ) {
          return new Response("Attachment is not linked to an existing message.", {
            status: 404,
          });
        }

        const adminAuth = await getFirebaseAdminToken(env);
        return download(env, chatId, messageId, user.uid, adminAuth.token);
      }

      if (request.method === "DELETE") {
        const adminAuth = await getFirebaseAdminToken(env);
        return remove(env, chatId, messageId, user.uid, adminAuth.token);
      }

      return json({ error: "Method not allowed" }, 405);
    } catch (error) {
      if (error instanceof Response) return error;
      console.error("InChat attachment worker error", error);
      return json({ error: "Attachment service error." }, 500);
    }
  },
};
