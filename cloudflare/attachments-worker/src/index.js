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
    "/" + path.split("/").map(encodeURIComponent).join("/") + ".json" :
    "/.json";
  const queryString = query ? "?" + query : "";
  const requestUrl = new URL(
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
      cleanPath +
      queryString,
  );

  const response = await fetch(requestUrl.toString(), {
    method: method,
    headers: {
      Authorization: "Bearer " + accessToken,
      ...(body === undefined ?
        {} :
        { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  if (!response.ok) {
    const details = await response.text();

    console.error("Firebase RTDB diagnostic", {
      hostname: requestUrl.hostname,
      path: requestUrl.pathname,
      status: response.status,
      responseUrl: response.url,
      redirected: response.redirected,
      server: response.headers.get("server"),
      cfRay: response.headers.get("cf-ray"),
      contentType: response.headers.get("content-type"),
      details: details.slice(0, 300),
    });

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
  const etag = readResponse.headers.get("ETag") || "null_etag";
  const now = Date.now();

  // Old receipts were written before retryable states existed. Treat them as
  // completed to avoid re-sending notifications for historical messages.
  if (existing !== null && typeof existing === "object") {
    if (!existing.status || existing.status === "sent") return null;
    if (
      existing.status === "processing" &&
      Number(existing.leaseUntil || 0) > now
    ) {
      return { busy: true };
    }

    if (existing.status !== "failed" && existing.status !== "processing") {
      return null;
    }
  } else if (existing !== null) {
    return null;
  }

  const claimId = crypto.randomUUID();
  const attempt = Math.max(0, Number(existing?.attempt || 0)) + 1;
  const writeResponse = await fetch(receiptUrl, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify({
      senderId: senderId,
      status: "processing",
      claimId: claimId,
      attempt: attempt,
      leaseUntil: now + 30000,
      updatedAt: { ".sv": "timestamp" },
    }),
  });

  if (writeResponse.status === 412) return null;
  if (!writeResponse.ok) {
    throw new Error(
      "Could not claim notification event (" +
      writeResponse.status +
      ").",
    );
  }

  return claimId;
}

async function updateMessageEventReceipt(
  env,
  adminToken,
  chatId,
  messageId,
  claimId,
  status,
  errorMessage = "",
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
      "Could not read notification receipt state (" +
      readResponse.status +
      ").",
    );
  }

  const current = await readResponse.json();
  if (!current || current.claimId !== claimId) return false;

  const etag = readResponse.headers.get("ETag");
  if (!etag) throw new Error("Firebase did not return a receipt ETag.");

  const nextState = {
    ...current,
    status: status,
    leaseUntil: 0,
    updatedAt: { ".sv": "timestamp" },
  };

  if (status === "failed") {
    nextState.lastError = notificationText(errorMessage).slice(0, 300);
  } else {
    delete nextState.lastError;
    nextState.processedAt = { ".sv": "timestamp" };
  }

  const writeResponse = await fetch(receiptUrl, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify(nextState),
  });

  if (writeResponse.status === 412) return false;
  if (!writeResponse.ok) {
    throw new Error(
      "Could not update notification receipt (" +
      writeResponse.status +
      ").",
    );
  }

  return true;
}

async function completeClaimedMessageEvent(
  env,
  adminToken,
  chatId,
  messageId,
  claimId,
  responseBody,
) {
  const completed = await updateMessageEventReceipt(
    env,
    adminToken,
    chatId,
    messageId,
    claimId,
    "sent",
  );

  if (!completed) {
    throw new Error("Notification receipt claim expired before completion.");
  }

  return json(responseBody);
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
    const error = new Error("FCM request failed (" + response.status + ").");
    error.status = response.status;
    error.response = responseBody;
    throw error;
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

async function unsubscribeTopicForAccountCleanup(
  accessToken,
  projectId,
  topic,
  registrationToken,
) {
  try {
    return await changeTopicSubscription(
      accessToken,
      projectId,
      topic,
      registrationToken,
      false,
    );
  } catch (error) {
    // FCM marks an expired/invalid registration token as INVALID_ARGUMENT.
    // There is no live token left to unsubscribe, so it must not block deletion.
    if (
      error &&
      error.status === 400 &&
      error.response &&
      error.response.error &&
      error.response.error.status === "INVALID_ARGUMENT"
    ) {
      console.warn("Skipping topic cleanup for an invalid FCM registration token.");
      return { unsubscribed: false, invalidToken: true };
    }
    throw error;
  }
}


async function mutateGroupMembersWithEtag(env, adminToken, chatId, mutate) {
  const path = "chats/" + chatId + "/members";
  const url = env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
    "/" + path.split("/").map(encodeURIComponent).join("/") + ".json";

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const readResponse = await fetch(url, {
      headers: {
        Authorization: "Bearer " + adminToken,
        "X-Firebase-ETag": "true",
      },
    });

    if (!readResponse.ok) {
      throw new Error(
        "Could not read group members (" + readResponse.status + ").",
      );
    }

    const currentMembers = await readResponse.json() || {};
    const etag = readResponse.headers.get("ETag");
    if (!etag) throw new Error("Realtime Database did not return a membership ETag.");

    const decision = mutate(currentMembers);
    if (!decision || decision.noChange) {
      return {
        members: currentMembers,
        changed: false,
        result: decision ? decision.result : undefined,
      };
    }

    const writeResponse = await fetch(url, {
      method: "PUT",
      headers: {
        Authorization: "Bearer " + adminToken,
        "Content-Type": "application/json",
        "If-Match": etag,
      },
      body: JSON.stringify(decision.members),
    });

    if (writeResponse.status === 412) continue;
    if (!writeResponse.ok) {
      const details = await writeResponse.text();
      throw new Error(
        "Could not update group membership (" +
        writeResponse.status + "): " + details.slice(0, 240),
      );
    }

    return {
      members: decision.members,
      changed: true,
      result: decision.result,
    };
  }

  throw new Response(
    "Group membership changed at the same time. Please try again.",
    { status: 409 },
  );
}

async function groupUsername(env, adminToken, uid) {
  const user = await adminDatabaseRequest(
    env,
    adminToken,
    "users/" + uid,
  );
  return typeof user?.username === "string" && user.username.trim() ?
    user.username.trim() :
    "member";
}

function groupActivityMessage(uid, eventType, text, messageId) {
  return {
    id: messageId,
    senderId: uid,
    senderNickname: "SYSTEM",
    text: text,
    timestamp: { ".sv": "timestamp" },
    systemEvent: eventType,
  };
}

async function manageGroupMembership(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const action = typeof body.action === "string" ? body.action.trim() : "";
  if (!["create", "add", "set_role", "remove", "leave"].includes(action)) {
    return json({ error: "Unsupported group membership action." }, 400);
  }

  const chatId = cleanSegment(body.chatId, "chatId");

  if (action === "create") {
    const groupName = typeof body.groupName === "string" ?
      body.groupName.trim() :
      "";
    if (!groupName || groupName.length > 50) {
      return json({ error: "Group name must be between 1 and 50 characters." }, 400);
    }

    if (!Array.isArray(body.memberIds) || body.memberIds.length > 50) {
      return json({ error: "Select between 1 and 50 group members." }, 400);
    }

    let requestedIds;
    try {
      requestedIds = [...new Set(body.memberIds.map((value) =>
        cleanSegment(value, "memberUid"),
      ))];
    } catch (error) {
      if (error instanceof Response) return error;
      throw error;
    }

    const memberIds = [...new Set([uid, ...requestedIds])];
    if (memberIds.length < 2) {
      return json({ error: "Select at least one other person." }, 400);
    }
    if (memberIds.length > 50) {
      return json({ error: "A group can contain at most 50 members." }, 409);
    }

    const profiles = await Promise.all(memberIds.map((memberId) =>
      adminDatabaseRequest(env, adminAuth.token, "users/" + memberId),
    ));
    if (profiles.some((profile) => !profile || typeof profile.username !== "string")) {
      return json({ error: "One or more selected accounts could not be found." }, 404);
    }

    const existing = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "chats/" + chatId,
    );
    if (existing) {
      return json({ error: "This group ID is already in use." }, 409);
    }

    const messageId = crypto.randomUUID().replace(/-/g, "");
    const creatorName = await groupUsername(env, adminAuth.token, uid);
    const members = {};
    for (const memberId of memberIds) {
      members[memberId] = memberId === uid ? "admin" : "member";
    }

    const updates = {
      ["chats/" + chatId]: {
        chatId: chatId,
        type: "group",
        name: groupName,
        createdBy: uid,
        createdAt: { ".sv": "timestamp" },
        members: members,
        messages: {
          [messageId]: groupActivityMessage(
            uid,
            "group_created",
            "@" + creatorName + " created the group",
            messageId,
          ),
        },
      },
    };
    for (const memberId of memberIds) {
      updates["groupMemberships/" + memberId + "/" + chatId] = true;
    }

    await adminDatabaseRequest(env, adminAuth.token, "", "PATCH", updates);
    try {
      await rebuildConversationPreview(
        env,
        adminAuth.token,
        chatId,
        updates["chats/" + chatId],
      );
    } catch (previewError) {
      console.warn("Could not initialize new group inbox previews", previewError);
    }
    return json({
      success: true,
      action: action,
      chatId: chatId,
      messageId: messageId,
      memberIds: memberIds,
    });
  }

  const chat = await adminDatabaseRequest(
    env,
    adminAuth.token,
    "chats/" + chatId,
  );
  if (!chat || chat.type !== "group") {
    return json({ error: "Group not found." }, 404);
  }

  // Reject non-admin management attempts before performing per-target profile
  // reads. The ETag-guarded mutation below checks the role again after races.
  if (
    action !== "leave" &&
    (!chat.members || chat.members[uid] !== "admin")
  ) {
    return json({ error: "Only group admins can manage members." }, 403);
  }

  const updates = {};
  let activityMessageId = "";
  let previewMembers = chat.members || {};

  if (action === "add") {
    if (!Array.isArray(body.memberIds) || body.memberIds.length < 1 ||
        body.memberIds.length > 50) {
      return json({ error: "Select between 1 and 50 members to add." }, 400);
    }

    let requestedIds;
    try {
      requestedIds = [...new Set(body.memberIds.map((value) =>
        cleanSegment(value, "memberUid"),
      ))].filter((memberId) => memberId !== uid);
    } catch (error) {
      if (error instanceof Response) return error;
      throw error;
    }

    if (requestedIds.length === 0) {
      return json({ error: "Select at least one new member." }, 400);
    }

    const currentMembers = chat.members || {};
    const preliminaryNewIds = requestedIds.filter((memberId) =>
      !Object.prototype.hasOwnProperty.call(currentMembers, memberId),
    );
    if (Object.keys(currentMembers).length + preliminaryNewIds.length > 50) {
      return json({ error: "A group can contain at most 50 members." }, 409);
    }

    const profiles = await Promise.all(requestedIds.map((memberId) =>
      adminDatabaseRequest(env, adminAuth.token, "users/" + memberId),
    ));
    if (profiles.some((profile) => !profile || typeof profile.username !== "string")) {
      return json({ error: "One or more selected accounts could not be found." }, 404);
    }

    const actorName = await groupUsername(env, adminAuth.token, uid);
    const mutation = await mutateGroupMembersWithEtag(
      env,
      adminAuth.token,
      chatId,
      (currentMembers) => {
        if (currentMembers[uid] !== "admin") {
          throw new Response("Only group admins can add members.", { status: 403 });
        }

        const newMemberIds = requestedIds.filter((memberId) =>
          !Object.prototype.hasOwnProperty.call(currentMembers, memberId),
        );

        if (Object.keys(currentMembers).length + newMemberIds.length > 50) {
          throw new Response("A group can contain at most 50 members.", { status: 409 });
        }

        if (newMemberIds.length === 0) {
          return { noChange: true, result: { newMemberIds: [] } };
        }

        const nextMembers = { ...currentMembers };
        for (const memberId of newMemberIds) nextMembers[memberId] = "member";
        return { members: nextMembers, result: { newMemberIds: newMemberIds } };
      },
    );

    const newMemberIds = mutation.result.newMemberIds;
    previewMembers = mutation.members;
    for (const memberId of requestedIds) {
      updates["groupMemberships/" + memberId + "/" + chatId] = true;
    }

    if (newMemberIds.length > 0) {
      const memberNames = await Promise.all(newMemberIds.map((memberId) =>
        groupUsername(env, adminAuth.token, memberId),
      ));
      const messageId = crypto.randomUUID().replace(/-/g, "");
      const addedNames = memberNames.map((name) => "@" + name).join(", ");
      updates["chats/" + chatId + "/messages/" + messageId] =
        groupActivityMessage(
          uid,
          "member_added",
          "@" + actorName + " added " + addedNames + " to the group",
          messageId,
        );
      activityMessageId = messageId;
    }
  } else {
    let targetUid = uid;
    let desiredRole = "";
    if (action === "set_role") {
      try {
        targetUid = cleanSegment(body.targetUid, "targetUid");
      } catch (error) {
        if (error instanceof Response) return error;
        throw error;
      }
      desiredRole = body.role;
      if (desiredRole !== "admin" && desiredRole !== "member") {
        return json({ error: "Invalid group role." }, 400);
      }
    } else if (action === "remove") {
      try {
        targetUid = cleanSegment(body.targetUid, "targetUid");
      } catch (error) {
        if (error instanceof Response) return error;
        throw error;
      }
    }

    const actorName = await groupUsername(env, adminAuth.token, uid);
    const mutation = await mutateGroupMembersWithEtag(
      env,
      adminAuth.token,
      chatId,
      (currentMembers) => {
        const isCreator = targetUid === chat.createdBy;

        if (action === "leave") {
          if (isCreator) {
            throw new Response(
              "The group creator cannot leave. Transfer ownership is not available yet.",
              { status: 409 },
            );
          }
          if (!Object.prototype.hasOwnProperty.call(currentMembers, uid)) {
            return { noChange: true, result: { removed: false } };
          }
          if (Object.keys(currentMembers).length <= 2) {
            throw new Response("A group needs at least two members.", { status: 409 });
          }
          const nextMembers = { ...currentMembers };
          delete nextMembers[uid];
          return { members: nextMembers, result: { removed: true } };
        }

        if (currentMembers[uid] !== "admin") {
          throw new Response("Only group admins can manage members.", { status: 403 });
        }

        if (action === "set_role") {
          if (!Object.prototype.hasOwnProperty.call(currentMembers, targetUid)) {
            throw new Response("That user is not a group member.", { status: 404 });
          }
          if (targetUid === uid || isCreator) {
            throw new Response("The group creator remains an admin.", { status: 409 });
          }
          if (currentMembers[targetUid] === desiredRole) {
            return { noChange: true, result: { changed: false } };
          }
          const nextMembers = { ...currentMembers, [targetUid]: desiredRole };
          return { members: nextMembers, result: { changed: true } };
        }

        if (targetUid === chat.createdBy || targetUid === uid) {
          throw new Response("The group creator or current admin cannot be removed here.", {
            status: 409,
          });
        }
        if (!Object.prototype.hasOwnProperty.call(currentMembers, targetUid)) {
          return { noChange: true, result: { removed: false } };
        }
        if (Object.keys(currentMembers).length <= 2) {
          throw new Response("A group needs at least two members.", { status: 409 });
        }
        const nextMembers = { ...currentMembers };
        delete nextMembers[targetUid];
        return { members: nextMembers, result: { removed: true } };
      },
    );
    previewMembers = mutation.members;

    if (action === "set_role" && mutation.changed) {
      const targetName = await groupUsername(env, adminAuth.token, targetUid);
      const promoted = desiredRole === "admin";
      const messageId = crypto.randomUUID().replace(/-/g, "");
      updates["chats/" + chatId + "/messages/" + messageId] =
        groupActivityMessage(
          uid,
          promoted ? "admin_promoted" : "admin_demoted",
          promoted ?
            "@" + actorName + " promoted @" + targetName + " to admin" :
            "@" + actorName + " removed @" + targetName + " as admin",
          messageId,
        );
      activityMessageId = messageId;
    }

    if (action === "remove" || action === "leave") {
      const removedUid = action === "leave" ? uid : targetUid;
      updates["groupMemberships/" + removedUid + "/" + chatId] = null;
      updates["groupReads/" + removedUid + "/" + chatId] = null;
      updates["userChats/" + removedUid + "/" + chatId] = null;

      if (mutation.changed) {
        const targetName = action === "leave" ?
          actorName :
          await groupUsername(env, adminAuth.token, removedUid);
        const messageId = crypto.randomUUID().replace(/-/g, "");
        updates["chats/" + chatId + "/messages/" + messageId] =
          groupActivityMessage(
            uid,
            action === "leave" ? "member_left" : "member_removed",
            action === "leave" ?
              "@" + targetName + " left the group" :
              "@" + actorName + " removed @" + targetName + " from the group",
            messageId,
          );
        activityMessageId = messageId;
      }
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminDatabaseRequest(env, adminAuth.token, "", "PATCH", updates);
  }

  try {
    await rebuildConversationPreview(
      env,
      adminAuth.token,
      chatId,
      { ...chat, members: previewMembers },
    );
  } catch (previewError) {
    console.warn("Could not rebuild group inbox previews after membership change", previewError);
  }

  return json({
    success: true,
    action: action,
    chatId: chatId,
    messageId: activityMessageId,
  });
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


async function cleanupAccountGroupsForDeletion(env, uid, adminAuth) {
  const [membershipIndex, readIndex, inbox, registrationToken] =
    await Promise.all([
      adminDatabaseRequest(env, adminAuth.token, "groupMemberships/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "groupReads/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "userChats/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "privateUsers/" + uid + "/fcmToken"),
    ]);

  const groupIds = new Set([
    ...Object.keys(membershipIndex || {}),
    ...Object.keys(readIndex || {}),
    ...Object.entries(inbox || {})
      .filter(([, entry]) => entry && entry.chatType === "group")
      .map(([chatId]) => chatId),
  ]);

  const updates = {
    ["groupMemberships/" + uid]: null,
    ["groupReads/" + uid]: null,
    ["chatReadState/" + uid]: null,
    ["userChats/" + uid]: null,
    ["userMessageVisibility/" + uid]: null,
    ["conversationPreferences/" + uid]: null,
  };

  // Typing indicators are ephemeral, but remove any stale direct-chat value
  // owned by the account as part of deletion.
  for (const [chatId, entry] of Object.entries(inbox || {})) {
    if (entry && entry.chatType !== "group" && !groupIds.has(chatId)) {
      updates["typing/" + chatId + "/" + uid] = null;
    }
  }

  const hasRegistrationToken =
    typeof registrationToken === "string" &&
    registrationToken.trim() !== "";

  if (hasRegistrationToken) {
    await unsubscribeTopicForAccountCleanup(
      adminAuth.token,
      adminAuth.projectId,
      await userTopicName(env, uid),
      registrationToken,
    );
  }

  let updatedGroups = 0;
  let dissolvedGroups = 0;

  for (const rawChatId of groupIds) {
    let chatId;
    try {
      chatId = cleanSegment(rawChatId, "chatId");
    } catch (_) {
      continue;
    }

    const chat = await adminDatabaseRequest(
      env,
      adminAuth.token,
      "chats/" + chatId,
    );

    if (!chat || chat.type !== "group") {
      updates["typing/" + chatId + "/" + uid] = null;
      continue;
    }

    const memberEntries = Object.entries(chat.members || {})
      .filter(([memberId, role]) =>
        typeof memberId === "string" &&
        memberId.length > 0 &&
        typeof role === "string" &&
        role.length > 0
      );

    const wasMember = memberEntries.some(([memberId]) => memberId === uid);

    // Clear a possible stale topic as well as a current membership.
    if (hasRegistrationToken) {
      await unsubscribeTopicForAccountCleanup(
        adminAuth.token,
        adminAuth.projectId,
        await groupTopicName(env, chatId),
        registrationToken,
      );
    }

    if (!wasMember) {
      updates["typing/" + chatId + "/" + uid] = null;
      continue;
    }

    const remainingMembers = memberEntries
      .filter(([memberId]) => memberId !== uid)
      .sort(([left], [right]) => left.localeCompare(right));

    if (remainingMembers.length < 2) {
      // The app requires at least two group members. Dissolve a group that
      // would otherwise leave one person in an unusable group conversation.
      updates["chats/" + chatId] = null;
      updates["attachments/" + chatId] = null;
      updates["typing/" + chatId] = null;

      for (const [memberId] of remainingMembers) {
        updates["groupMemberships/" + memberId + "/" + chatId] = null;
        updates["groupReads/" + memberId + "/" + chatId] = null;
        updates["userChats/" + memberId + "/" + chatId] = null;
        updates["userMessageVisibility/" + memberId + "/" + chatId] = null;
        updates["conversationPreferences/" + memberId + "/" + chatId] = null;

        const memberToken = await adminDatabaseRequest(
          env,
          adminAuth.token,
          "privateUsers/" + memberId + "/fcmToken",
        );

        if (typeof memberToken === "string" && memberToken.trim() !== "") {
          await unsubscribeTopicForAccountCleanup(
            adminAuth.token,
            adminAuth.projectId,
            await groupTopicName(env, chatId),
            memberToken,
          );
        }
      }

      dissolvedGroups += 1;
      continue;
    }

    updates["chats/" + chatId + "/members/" + uid] = null;
    updates["typing/" + chatId + "/" + uid] = null;

    const remainingAdmins = remainingMembers
      .filter(([, role]) => role === "admin");

    if (chat.createdBy === uid || remainingAdmins.length === 0) {
      const successor = remainingAdmins.length > 0 ?
        remainingAdmins[0][0] :
        remainingMembers[0][0];

      updates["chats/" + chatId + "/members/" + successor] = "admin";
      updates["chats/" + chatId + "/createdBy"] = successor;
    }

    // Update only inbox entries that already exist; never create partial rows.
    const remainingInboxEntries = await Promise.all(
      remainingMembers.map(async ([memberId]) => ({
        memberId: memberId,
        entry: await adminDatabaseRequest(
          env,
          adminAuth.token,
          "userChats/" + memberId + "/" + chatId,
        ),
      })),
    );

    for (const item of remainingInboxEntries) {
      if (item.entry && typeof item.entry === "object") {
        updates[
          "userChats/" + item.memberId + "/" + chatId + "/memberCount"
        ] = remainingMembers.length;
      }
    }

    updatedGroups += 1;
  }

  await adminDatabaseRequest(env, adminAuth.token, "", "PATCH", updates);

  return json({
    success: true,
    updatedGroups: updatedGroups,