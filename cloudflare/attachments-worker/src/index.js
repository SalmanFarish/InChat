import { hasReadThrough, shouldIncrementGroupUnread, membersReadThroughLatestMessage } from "./group-unread.js";
import { loadChatMetadata } from "./chat-metadata.js";
import {
  currentGroupCryptoMembershipEpoch,
  mutateGroupMembersWithEtag,
  ensureGroupCryptoMembershipSnapshotWithEtag,
} from "./group-crypto-membership.js";
import { processEncryptedOlmPush } from "./olm-push.js";
import { processOlmMailboxRequest, OlmDeviceMailboxDurableObject } from "./olm-mailbox.js";
export { OlmDeviceMailboxDurableObject };
import { syncEncryptedDirectPreview } from "./encrypted-direct-preview.js";
import { syncEncryptedGroupPreview } from "./encrypted-group-preview.js";
import { isCurrentGroupTopicSubscription } from "./group-topic-subscription.js";
import { previousGroupNotificationToken } from "./group-topic-subscription.js";
import { cleanupGroupNotificationTokens } from "./group-topic-subscription.js";
import { submitUserReport } from "./user-reports.js";
import {
  PayloadTooLargeError,
  readRequestTextLimited,
} from "./bounded-request-body.js";

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

/**
 * Authenticated Firebase metadata GET helper. Callers must request a single
 * metadata child, never the whole chats/{chatId} node or message history.
 */
export async function firebaseUserDatabaseGet(path, token, env, fetchImpl = fetch) {
  if (
    typeof path !== "string" || path.trim() === "" ||
    typeof token !== "string" || token.trim() === ""
  ) {
    throw new TypeError("A Firebase database path and ID token are required.");
  }
  const segments = path.split("/").filter(Boolean);
  if (
    segments[0] === "chats" &&
    (segments.length === 2 || segments[2] === "messages")
  ) {
    throw new TypeError(
      "Firebase chat roots and message history are not readable; request a metadata child only.",
    );
  }
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  const encodedPath = segments.map(encodeURIComponent).join("/");
  return fetchImpl(base + "/" + encodedPath + ".json", {
    headers: {
      Accept: "application/json",
      Authorization: "Bearer " + token,
    },
  });
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
async function mapWithConcurrencyLimit(items, concurrency, mapper) {
  const results = new Array(items.length);
  let nextIndex = 0;
  const workerCount = Math.min(
    items.length,
    Math.max(1, Math.floor(concurrency)),
  );

  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (true) {
        const index = nextIndex;
        nextIndex += 1;
        if (index >= items.length) return;
        results[index] = await mapper(items[index], index);
      }
    }),
  );

  return results;
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

    const [existingType, existingParticipantA, existingParticipantB] = await Promise.all([
      adminDatabaseRequest(env, adminAuth.token, "chats/" + chatId + "/type"),
      adminDatabaseRequest(env, adminAuth.token, "chats/" + chatId + "/participantA"),
      adminDatabaseRequest(env, adminAuth.token, "chats/" + chatId + "/participantB"),
    ]);
    if (existingType || existingParticipantA || existingParticipantB) {
      return json({ error: "This conversation ID is already in use." }, 409);
    }
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
        cryptoMembership: {
          epoch: 1,
          members: members,
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
      messageId: "",
      memberIds: memberIds,
      cryptoMembershipEpoch: 1,
    });
  }

  const chat = await loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId);
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
  let removedUidForNotificationCleanup = "";
  let previewMembers = chat.members || {};
  let cryptoMembershipEpoch = currentGroupCryptoMembershipEpoch(
    chat.cryptoMembership?.epoch,
  );

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
        return {
          members: nextMembers,
          cryptoMembershipChanged: true,
          result: { newMemberIds: newMemberIds },
        };
      },
    );

    const newMemberIds = mutation.result.newMemberIds;
    previewMembers = mutation.members;
    cryptoMembershipEpoch = mutation.cryptoMembershipEpoch;
    for (const memberId of requestedIds) {
      updates["groupMemberships/" + memberId + "/" + chatId] = true;
    }
    for (const memberId of newMemberIds) {
      updates["chats/" + chatId + "/groupReadCursors/" + memberId] = null;
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
          return {
            members: nextMembers,
            cryptoMembershipChanged: true,
            result: { removed: true },
          };
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
          return {
            members: nextMembers,
            cryptoMembershipChanged: false,
            result: { changed: true },
          };
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
        return {
          members: nextMembers,
          cryptoMembershipChanged: true,
          result: { removed: true },
        };
      },
    );
    previewMembers = mutation.members;
    cryptoMembershipEpoch = mutation.cryptoMembershipEpoch;

    if (action === "remove" || action === "leave") {
      const removedUid = action === "leave" ? uid : targetUid;
      removedUidForNotificationCleanup = removedUid;
      updates["groupMemberships/" + removedUid + "/" + chatId] = null;
      updates["groupReads/" + removedUid + "/" + chatId] = null;
      updates["chats/" + chatId + "/groupReadCursors/" + removedUid] = null;
      updates["userChats/" + removedUid + "/" + chatId] = null;

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
      {
        ...chat,
        members: previewMembers,
        cryptoMembership: {
          epoch: cryptoMembershipEpoch,
          members: previewMembers,
        },
      },
    );
  } catch (previewError) {
    console.warn("Could not rebuild group inbox previews after membership change", previewError);
  }

  let notificationCleanupPending = false;
  if (removedUidForNotificationCleanup) {
    const registrationPath =
      "groupNotificationDevices/" + chatId + "/" + removedUidForNotificationCleanup;
    const [currentTokenValue, previousTokenValue] = await Promise.all([
      adminDatabaseRequest(
        env,
        adminAuth.token,
        "privateUsers/" + removedUidForNotificationCleanup + "/fcmToken",
      ),
      adminDatabaseRequest(env, adminAuth.token, registrationPath),
    ]);
    const groupTopic = await groupTopicName(env, chatId);
    const cleanup = await cleanupGroupNotificationTokens({
      previousToken: previousTokenValue,
      currentToken: currentTokenValue,
      unsubscribe: (token) => unsubscribeTopicForAccountCleanup(
        adminAuth.token,
        adminAuth.projectId,
        groupTopic,
        token,
      ),
    });

    if (cleanup.complete) {
      await adminDatabaseRequest(
        env,
        adminAuth.token,
        registrationPath,
        "DELETE",
      );
    } else {
      // Preserve the mirrored token so the existing Android follow-up request
      // or a retry of this membership operation still has what it needs.
      notificationCleanupPending = true;
      console.warn("Group membership changed but FCM topic cleanup is pending.", {
        chatId,
        removedUid: removedUidForNotificationCleanup,
        failedTokenCount: cleanup.failedTokens.length,
      });
    }
  }

  return json({
    success: true,
    action: action,
    chatId: chatId,
    messageId: "",
    cryptoMembershipEpoch: cryptoMembershipEpoch,
    ...(notificationCleanupPending ? { notificationCleanupPending: true } : {}),
  });
}


async function initializeGroupCryptoMembership(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }
  if (
    !body ||
    typeof body !== "object" ||
    Array.isArray(body) ||
    Object.keys(body).some((key) => key !== "chatId")
  ) {
    return json({ error: "Invalid group crypto membership request." }, 400);
  }

  let chatId;
  try {
    chatId = cleanSegment(body.chatId, "chatId");
  } catch (error) {
    if (error instanceof Response) return error;
    throw error;
  }

  const snapshot = await ensureGroupCryptoMembershipSnapshotWithEtag(
    env,
    adminAuth.token,
    chatId,
    uid,
  );
  return json({
    success: true,
    chatId: snapshot.groupId,
    epoch: snapshot.epoch,
    members: snapshot.members,
    initialized: snapshot.changed,
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

  const chat = await loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId);

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

  const registrationPath =
    "groupNotificationDevices/" + chatId + "/" + targetUid;
  const [registrationTokenValue, previousRegistrationValue] = await Promise.all([
    adminDatabaseRequest(
      env,
      adminAuth.token,
      "privateUsers/" + targetUid + "/fcmToken",
    ),
    adminDatabaseRequest(
      env,
      adminAuth.token,
      registrationPath,
    ),
  ]);
  const registrationToken =
    typeof registrationTokenValue === "string" && registrationTokenValue.trim() ?
      registrationTokenValue.trim() :
      "";
  const previousRegistrationToken =
    typeof previousRegistrationValue === "string" && previousRegistrationValue.trim() ?
      previousRegistrationValue.trim() :
      "";
  const staleToken = previousGroupNotificationToken(
    previousRegistrationToken,
    registrationToken,
  );
  const groupTopic = await groupTopicName(env, chatId);
  const personalTopic = await userTopicName(env, targetUid);

  async function cleanupToken(token, includePersonalTopic) {
    const operations = [
      unsubscribeTopicForAccountCleanup(
        adminAuth.token,
        adminAuth.projectId,
        groupTopic,
        token,
      ),
    ];
    if (includePersonalTopic) {
      operations.push(
        unsubscribeTopicForAccountCleanup(
          adminAuth.token,
          adminAuth.projectId,
          personalTopic,
          token,
        ),
      );
    }
    const results = await Promise.allSettled(operations);
    return results.every((result) => result.status === "fulfilled");
  }

  if (!subscribe) {
    // The per-group mirror is the only durable reference to an older token.
    // Clean both the current and previously registered tokens before deleting it.
    const tokensToRemove = [...new Set(
      [registrationToken, previousRegistrationToken].filter(Boolean),
    )];
    const cleanupResults = await Promise.all(
      tokensToRemove.map((token) => cleanupToken(token, false)),
    );
    if (cleanupResults.some((completed) => !completed)) {
      return json({
        success: false,
        retryable: true,
        error: "Group topic cleanup failed; the previous token reference was retained for retry.",
      }, 503);
    }

    await adminDatabaseRequest(
      env,
      adminAuth.token,
      registrationPath,
      "DELETE",
    );

    return json({
      success: true,
      chatId: chatId,
      targetUid: targetUid,
      unsubscribed: true,
      ...(tokensToRemove.length === 0 ? {
        skipped: true,
        reason: "No FCM token is registered for this member.",
      } : {}),
    });
  }

  // Never overwrite the last known token until its group and personal topic
  // subscriptions have been removed. If cleanup fails, a retry still has the
  // token needed to finish the operation.
  if (staleToken && !(await cleanupToken(staleToken, true))) {
    return json({
      success: false,
      retryable: true,
      error: "Previous FCM token topic cleanup failed; registration was not replaced.",
    }, 503);
  }

  if (!registrationToken) {
    await adminDatabaseRequest(
      env,
      adminAuth.token,
      registrationPath,
      "PUT",
      "",
    );
    return json({
      success: true,
      skipped: true,
      reason: "No FCM token is registered for this member.",
    });
  }

  // Empty records distinguish a member with no token from an unsynchronized
  // member. Keep the mirror update after stale-token cleanup has succeeded.
  await adminDatabaseRequest(
    env,
    adminAuth.token,
    registrationPath,
    "PUT",
    registrationToken,
  );

  await changeTopicSubscription(
    adminAuth.token,
    adminAuth.projectId,
    personalTopic,
    registrationToken,
    true,
  );

  const result = await changeTopicSubscription(
    adminAuth.token,
    adminAuth.projectId,
    groupTopic,
    registrationToken,
    true,
  );

  // Topic membership is token-scoped, while group membership can change
  // during the asynchronous FCM operations above. Re-read authoritative state
  // before confirming registration so a removed member cannot stay subscribed.
  const [latestChat, latestToken] = await Promise.all([
    loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId),
    adminDatabaseRequest(env, adminAuth.token, "privateUsers/" + targetUid + "/fcmToken"),
  ]);
  if (!isCurrentGroupTopicSubscription({
    chat: latestChat,
    uid: targetUid,
    requestedToken: registrationToken,
    currentToken: latestToken,
  })) {
    const cleaned = await cleanupToken(registrationToken, true);
    if (!cleaned) {
      return json({
        success: false,
        retryable: true,
        error: "Group membership changed during notification registration; cleanup must be retried.",
      }, 503);
    }

    const currentRole = latestChat?.members && latestChat.members[targetUid];
    if (!currentRole && latestChat?.type === "group") {
      await adminDatabaseRequest(
        env,
        adminAuth.token,
        registrationPath,
        "DELETE",
      );
    } else {
      // Keep the member marked as synchronized but with no active token. A
      // subsequent token registration will repopulate this record.
      await adminDatabaseRequest(
        env,
        adminAuth.token,
        registrationPath,
        "PUT",
        "",
      );
    }

    return json({
      success: true,
      skipped: true,
      reason: "Group membership or FCM token changed during notification registration.",
    });
  }

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


async function cleanupOneClaimantPreKeyIndex(env, uid, adminToken) {
  const indexPath = "deviceKeyClaimsByClaimant/" + uid;
  const firstChild = (path) => adminDatabaseRequest(
    env,
    adminToken,
    path,
    "GET",
    undefined,
    "orderBy=%22$key%22&limitToFirst=1",
  );

  // Query one child at each level rather than downloading the entire reverse
  // index. This keeps both response size and work bounded for long-lived users.
  const targetIndex = await firstChild(indexPath);
  const targetUid = Object.keys(targetIndex || {}).sort()[0];
  if (targetUid === undefined) return { complete: true };
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(targetUid)) {
    throw new Error("Invalid target UID in claimant prekey index.");
  }

  const deviceIndex = await firstChild(indexPath + "/" + targetUid);
  const deviceId = Object.keys(deviceIndex || {}).sort()[0];
  if (deviceId === undefined) {
    await adminDatabaseRequest(env, adminToken, indexPath + "/" + targetUid, "DELETE");
    return { complete: false };
  }
  const safeDeviceId = cleanSegment(deviceId, "deviceId");

  const keyIndex = await firstChild(indexPath + "/" + targetUid + "/" + safeDeviceId);
  const keyId = Object.keys(keyIndex || {}).sort()[0];
  if (keyId === undefined) {
    await adminDatabaseRequest(
      env, adminToken, indexPath + "/" + targetUid + "/" + safeDeviceId, "DELETE",
    );
    return { complete: false };
  }
  const safeKeyId = cleanSegment(keyId, "keyId");

  const claimPath = "deviceKeyClaims/" + targetUid + "/" + safeDeviceId + "/" + safeKeyId;
  const claimUrl = new URL(
    env.FIREBASE_DATABASE_URL.replace(/\/$/, "") +
      "/" + claimPath.split("/").map(encodeURIComponent).join("/") + ".json",
  );
  const read = await fetch(claimUrl.toString(), {
    headers: { Authorization: "Bearer " + adminToken, "X-Firebase-ETag": "true" },
  });
  if (!read.ok) {
    throw new Error("Could not read indexed one-time prekey claim (" + read.status + ").");
  }
  const claim = await read.json();
  const etag = read.headers.get("ETag") || "null_etag";

  if (claim && claim.claimantUid === uid) {
    const removeClaim = await fetch(claimUrl.toString(), {
      method: "DELETE",
      headers: { Authorization: "Bearer " + adminToken, "If-Match": etag },
    });
    if (removeClaim.status === 412) return { complete: false, conflict: true };
    if (!removeClaim.ok) {
      throw new Error("Could not release indexed one-time prekey claim (" + removeClaim.status + ").");
    }
  }

  // Remove the index only after the matching claim is absent or conditionally
  // deleted. A concurrent replacement is protected by the claim ETag above.
  await adminDatabaseRequest(
    env,
    adminToken,
    indexPath + "/" + targetUid + "/" + safeDeviceId + "/" + safeKeyId,
    "DELETE",
  );
  return { complete: false, processed: true };
}

export async function cleanupAccountGroupsForDeletion(env, uid, adminAuth) {
  // Persist a server-owned tombstone before inspecting cleanup indexes. Rules
  // reject both new reverse-index entries and new claims after this point,
  // closing the race between the final empty-index read and Auth deletion.
  await adminDatabaseRequest(
    env,
    adminAuth.token,
    "accountDeletionLocks/" + uid,
    "PUT",
    true,
  );
  const [membershipIndex, readIndex, inbox, registrationTokenValue] =
    await Promise.all([
      adminDatabaseRequest(env, adminAuth.token, "groupMemberships/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "groupReads/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "userChats/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "privateUsers/" + uid + "/fcmToken"),
    ]);

  const registrationToken =
    typeof registrationTokenValue === "string" ?
      registrationTokenValue.trim() :
      "";

  const groupIds = new Set([
    ...Object.keys(membershipIndex || {}),
    ...Object.keys(readIndex || {}),
    ...Object.entries(inbox || {})
      .filter(([, entry]) => entry && entry.chatType === "group")
      .map(([chatId]) => chatId),
  ].filter((chatId) =>
    typeof chatId === "string" &&
    /^[A-Za-z0-9_-]{1,120}$/.test(chatId)
  ));

  const hasRegistrationToken =
    typeof registrationToken === "string" &&
    registrationToken.trim() !== "";

  // This operation is idempotent, so retrying it on later batches also
  // repairs a transient FCM unsubscribe failure without storing token state.
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
  let previewToRebuild = null;

  // Process one group per invocation. The per-group index entries are removed
  // only after all work for that group succeeds. The Android caller repeats
  // this endpoint until complete=true, keeping every invocation comfortably
  // below the Workers Free external-subrequest ceiling even for large groups.
  const rawChatId = [...groupIds].sort()[0];

  if (rawChatId !== undefined) {
    const chatId = cleanSegment(rawChatId, "chatId");
    const [chat, mirroredRegistrationToken] = await Promise.all([
      loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId),
      adminDatabaseRequest(
        env,
        adminAuth.token,
        "groupNotificationDevices/" + chatId + "/" + uid,
      ),
    ]);

    const groupTopic = await groupTopicName(env, chatId);
    const groupTokenCleanup = await cleanupGroupNotificationTokens({
      previousToken: mirroredRegistrationToken,
      currentToken: registrationToken,
      unsubscribe: (token) => unsubscribeTopicForAccountCleanup(
        adminAuth.token,
        adminAuth.projectId,
        groupTopic,
        token,
      ),
    });
    if (!groupTokenCleanup.complete) {
      throw new Error(
        "Could not remove all registered group notification tokens for account deletion.",
      );
    }

    for (const token of groupTokenCleanup.tokens) {
      if (token !== registrationToken) {
        await unsubscribeTopicForAccountCleanup(
          adminAuth.token,
          adminAuth.projectId,
          await userTopicName(env, uid),
          token,
        );
      }
    }

    const updates = {
      ["groupMemberships/" + uid + "/" + chatId]: null,
      ["groupReads/" + uid + "/" + chatId]: null,
      ["userChats/" + uid + "/" + chatId]: null,
      ["chatReadReceipts/" + chatId + "/" + uid]: null,
      ["typing/" + chatId + "/" + uid]: null,
      ["groupNotificationDevices/" + chatId + "/" + uid]: null,
    };

    if (!chat || chat.type !== "group") {
      // Stale membership/read/inbox indexes are still removed, but a direct
      // conversation itself remains available to the other participant.
    } else {
      const memberEntries = Object.entries(chat.members || {})
        .filter(([memberId, role]) =>
          typeof memberId === "string" &&
          memberId.length > 0 &&
          typeof role === "string" &&
          role.length > 0
        );
      const wasMember = memberEntries.some(([memberId]) => memberId === uid);
      const remainingMembers = memberEntries
        .filter(([memberId]) => memberId !== uid)
        .sort(([left], [right]) => left.localeCompare(right));

      if (wasMember && remainingMembers.length < 2) {
        // A group that would have fewer than two members is dissolved. It is
        // deleted as one root path, so remove the account-specific child paths
        // already staged above before adding whole-tree deletions.
        delete updates["typing/" + chatId + "/" + uid];
        delete updates["groupNotificationDevices/" + chatId + "/" + uid];
        updates["chats/" + chatId] = null;
        updates["attachments/" + chatId] = null;
        updates["typing/" + chatId] = null;
        updates["groupNotificationDevices/" + chatId] = null;

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
              groupTopic,
              memberToken,
            );
          }
        }
        dissolvedGroups += 1;
      } else if (wasMember) {
        // The ETag-protected helper updates members and the crypto snapshot
        // together. Creator transfer follows the remaining admin roster.
        const mutation = await mutateGroupMembersWithEtag(
          env,
          adminAuth.token,
          chatId,
          (currentMembers, currentChat) => {
            const currentEntries = Object.entries(currentMembers || {})
              .filter(([memberId, role]) =>
                typeof memberId === "string" &&
                memberId.length > 0 &&
                typeof role === "string" &&
                role.length > 0
              );
            if (!currentEntries.some(([memberId]) => memberId === uid)) {
              return {
                noChange: true,
                result: { wasMember: false },
              };
            }

            const nextEntries = currentEntries
              .filter(([memberId]) => memberId !== uid)
              .sort(([left], [right]) => left.localeCompare(right));
            if (nextEntries.length < 2) {
              return {
                noChange: true,
                result: { wasMember: true, requiresDissolution: true },
              };
            }

            const nextMembers = Object.fromEntries(nextEntries);
            const remainingAdmins = nextEntries.filter(([, role]) => role === "admin");
            let nextCreator = currentChat.createdBy;
            if (nextCreator === uid || remainingAdmins.length === 0) {
              nextCreator = remainingAdmins.length > 0 ?
                remainingAdmins[0][0] :
                nextEntries[0][0];
            }

            return {
              members: nextMembers,
              cryptoMembershipChanged: true,
              ...(nextCreator && nextCreator !== currentChat.createdBy ?
                { createdBy: nextCreator } :
                {}),
              result: {
                wasMember: true,
                remainingMembers: nextEntries,
              },
            };
          },
        );

        if (mutation.result?.requiresDissolution) {
          throw new Error(
            "Group membership changed during account cleanup. Retry account deletion.",
          );
        }

        if (mutation.result?.wasMember) {
          updates["chats/" + chatId + "/groupReadCursors/" + uid] = null;
          previewToRebuild = {
            chatId: chatId,
            chat: {
              ...chat,
              members: Object.fromEntries(mutation.result.remainingMembers),
            },
          };
          updatedGroups += 1;
        } else {
          // A previous attempt may have committed the ETag membership update
          // but failed before rebuilding the inbox preview. Re-read the
          // authoritative group so this retry can finish that pending work.
          const latestChat = await loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId);
          if (
            latestChat &&
            latestChat.type === "group" &&
            Object.keys(latestChat.members || {}).length >= 2
          ) {
            previewToRebuild = {
              chatId: chatId,
              chat: latestChat,
            };
          }
        }
      } else {
        updates["chats/" + chatId + "/groupReadCursors/" + uid] = null;
        // The member may already have been removed by an earlier interrupted
        // attempt. Finish rebuilding the current roster's preview before
        // deleting the stale account-owned indexes.
        if (remainingMembers.length >= 2) {
          previewToRebuild = {
            chatId: chatId,
            chat: chat,
          };
        }
      }
    }

    // Rebuild before deleting this group's indexes. If preview synchronization
    // fails, the index remains so the next invocation retries this group.
    if (previewToRebuild) {
      await rebuildConversationPreview(
        env,
        adminAuth.token,
        previewToRebuild.chatId,
        previewToRebuild.chat,
      );
    }

    // Commit this group's cleanup before returning its batch result. If the
    // request is interrupted earlier, its indexes remain and the next call
    // retries the same group; every preceding side effect is idempotent.
    await adminDatabaseRequest(env, adminAuth.token, "", "PATCH", updates);

    if (groupIds.size > 1) {
      return json({
        success: true,
        complete: false,
        processedGroups: 1,
        updatedGroups: updatedGroups,
        dissolvedGroups: dissolvedGroups,
      });
    }
  }

  // A final fresh index read closes the race where another group was added
  // while the last known group was being processed.
  const [remainingMemberships, remainingReads, remainingInbox] =
    await Promise.all([
      adminDatabaseRequest(env, adminAuth.token, "groupMemberships/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "groupReads/" + uid),
      adminDatabaseRequest(env, adminAuth.token, "userChats/" + uid),
    ]);
  const remainingGroupIds = new Set([
    ...Object.keys(remainingMemberships || {}),
    ...Object.keys(remainingReads || {}),
    ...Object.entries(remainingInbox || {})
      .filter(([, entry]) => entry && entry.chatType === "group")
      .map(([chatId]) => chatId),
  ].filter((chatId) =>
    typeof chatId === "string" &&
    /^[A-Za-z0-9_-]{1,120}$/.test(chatId)
  ));

  if (remainingGroupIds.size > 0) {
    return json({
      success: true,
      complete: false,
      processedGroups: rawChatId === undefined ? 0 : 1,
      updatedGroups: updatedGroups,
      dissolvedGroups: dissolvedGroups,
    });
  }

  // Release one claimant-owned prekey per invocation to keep cleanup bounded.
  const preKeyCleanup = await cleanupOneClaimantPreKeyIndex(env, uid, adminAuth.token);
  if (!preKeyCleanup.complete) {
    return json({
      success: true,
      complete: false,
      processedGroups: rawChatId === undefined ? 0 : 1,
      processedPreKeyClaims: preKeyCleanup.processed ? 1 : 0,
      updatedGroups: updatedGroups,
      dissolvedGroups: dissolvedGroups,
    });
  }

  const finalUpdates = {
    ["deviceKeyClaimsByClaimant/" + uid]: null,
    ["groupMemberships/" + uid]: null,
    ["groupReads/" + uid]: null,
    ["chatReadState/" + uid]: null,
    ["userChats/" + uid]: null,
    ["userMessageVisibility/" + uid]: null,
    ["conversationPreferences/" + uid]: null,

    // Revoke the deleted account's published E2EE identities and clear its
    // account-owned inbound relay/control queues.
    ["deviceKeys/" + uid]: null,
    ["deviceKeyClaims/" + uid]: null,
    ["olmInbox/" + uid]: null,
    ["olmDeliveryAcks/" + uid]: null,
    ["olmPushDispatch/" + uid]: null,
    ["sasVerificationInbox/" + uid]: null,
    ["deviceTransferInbox/" + uid]: null,
  };

  for (const chatId of Object.keys(remainingInbox || {})) {
    try {
      const validChatId = cleanSegment(chatId, "chatId");
      finalUpdates["chatReadReceipts/" + validChatId + "/" + uid] = null;
      finalUpdates["typing/" + validChatId + "/" + uid] = null;
    } catch (_) {
      // Ignore malformed legacy inbox keys rather than constructing a path.
    }
  }

  await adminDatabaseRequest(env, adminAuth.token, "", "PATCH", finalUpdates);

  return json({
    success: true,
    complete: true,
    processedGroups: rawChatId === undefined ? 0 : 1,
    updatedGroups: updatedGroups,
    dissolvedGroups: dissolvedGroups,
  });
}

async function rebuildConversationPreview(
  env,
  adminToken,
  chatId,
  chat,
) {
  if (!chat || typeof chat !== "object") {
    throw new Error("Conversation metadata is unavailable.");
  }

  const updates = {};
  if (chat.type === "group") {
    const members = Object.keys(chat.members || {});
    if (members.length < 2 || members.length > 50) {
      throw new Error("Group membership metadata is invalid.");
    }
    const entries = await Promise.all(members.map(async (memberId) => ({
      memberId,
      inbox: await adminDatabaseRequest(env, adminToken, "userChats/" + memberId + "/" + chatId),
    })));
    for (const { memberId, inbox } of entries) {
      const base = "userChats/" + memberId + "/" + chatId + "/";
      updates[base + "chatId"] = chatId;
      updates[base + "chatType"] = "group";
      updates[base + "groupName"] = typeof chat.name === "string" ? chat.name.trim() : "";
      updates[base + "memberCount"] = members.length;
      updates[base + "otherUserId"] = "";
      updates[base + "otherUsername"] = "";
      if (!inbox || typeof inbox.lastMessage !== "string") {
        updates[base + "lastMessage"] = "No messages yet";
      }
      if (!inbox || typeof inbox.lastTimestamp !== "number") {
        updates[base + "lastTimestamp"] = 0;
      }
      if (!inbox || typeof inbox.unreadCount !== "number") {
        updates[base + "unreadCount"] = 0;
      }
      if (!inbox || typeof inbox.lastSenderId !== "string") {
        updates[base + "lastSenderId"] = "";
      }
      if (!inbox || typeof inbox.lastSenderNickname !== "string") {
        updates[base + "lastSenderNickname"] = "";
      }
    }
  } else {
    // Direct previews are synchronized only after a verified encrypted
    // mailbox delivery. A generic metadata refresh must not infer message
    // history by reading chats/{id}/messages.
    const participants = [chat.participantA, chat.participantB];
    if (participants.some((value) => typeof value !== "string" || !value) ||
        participants[0] === participants[1]) {
      throw new Error("Cannot rebuild preview for invalid direct chat.");
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
async function processPreviewEvent(request, env, uid, adminAuth) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const chatId = cleanSegment(body.chatId, "chatId");
  const chat = await loadChatMetadata(adminDatabaseRequest, env, adminAuth.token, chatId);

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

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function download(env, chatId, messageId, uid, adminToken, message) {
  const expectedType = message?.attachment?.type;
  if (
    message?.attachment?.storageKey !== attachmentKey(chatId, messageId) ||
    (expectedType !== "voice" && expectedType !== "view_once_photo") ||
    typeof message.senderId !== "string" ||
    !message.senderId
  ) {
    return new Response("Attachment is not linked to a valid message.", {
      status: 404,
    });
  }

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
  if (
    !record ||
    typeof record.data !== "string" ||
    record.chatId !== chatId ||
    record.messageId !== messageId ||
    record.senderId !== message.senderId ||
    record.attachmentType !== expectedType
  ) {
    return new Response("Attachment metadata does not match its message.", {
      status: 404,
    });
  }

  const maxBytes =
    expectedType === "voice" ? MAX_VOICE_BYTES : MAX_PHOTO_BYTES;
  const expectedMimePrefix =
    expectedType === "voice" ? "audio/" : "image/";
  if (
    typeof record.contentType !== "string" ||
    !record.contentType.toLowerCase().startsWith(expectedMimePrefix) ||
    record.data.length === 0 ||
    record.data.length > 4 * Math.ceil(maxBytes / 3)
  ) {
    return new Response("Invalid attachment data.", { status: 404 });
  }

  // Decode and validate before consuming a view-once record, so corrupt data
  // cannot burn the one allowed open.
  let bytes;
  try {
    bytes = base64ToBytes(record.data);
  } catch (_) {
    return new Response("Invalid attachment data.", { status: 404 });
  }

  if (
    bytes.byteLength === 0 ||
    bytes.byteLength > maxBytes ||
    (record.sizeBytes !== undefined &&
      (!Number.isSafeInteger(record.sizeBytes) ||
        record.sizeBytes !== bytes.byteLength))
  ) {
    return new Response("Invalid attachment size.", { status: 404 });
  }

  if (expectedType === "view_once_photo" && record.senderId === uid) {
    return new Response("Sender cannot consume a view-once photo", { status: 403 });
  }

  if (expectedType === "view_once_photo") {
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

  const safeFileName = cleanFileName(record.fileName);
  const headers = new Headers();
  headers.set("Content-Type", record.contentType);
  headers.set("Cache-Control", "private, no-store");
  headers.set("Content-Disposition", 'attachment; filename="' +
    safeFileName + '"');
  headers.set("X-InChat-File-Name", safeFileName);
  headers.set("X-InChat-Attachment-Type", expectedType);

  if (expectedType === "view_once_photo") {
    headers.set("X-InChat-View-Once", "true");
  }

  return new Response(bytes, { status: 200, headers });
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
async function processEncryptedOlmPushRequest(request, env, uid, adminAuth) {
  let raw;
  try {
    raw = await readRequestTextLimited(request, 4096);
  } catch (error) {
    if (error instanceof PayloadTooLargeError) {
      return json({ error: "Encrypted push request is too large." }, 413);
    }
    return json({ error: "Invalid JSON body." }, 400);
  }

  let body;
  try {
    body = JSON.parse(raw);
  } catch (_) {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const outcome = await processEncryptedOlmPush({
    env,
    uid,
    body,
    adminToken: adminAuth,
    adminDatabaseRequest,
    fcmRequest,
  });
  return json(outcome.payload, outcome.status);
}

async function processUserReportRequest(request, env, uid, adminAuth) {
  let raw;
  try {
    raw = await readRequestTextLimited(request, 8192);
  } catch (error) {
    if (error instanceof PayloadTooLargeError) {
      return json({ error: "Report request is too large." }, 413);
    }
    return json({ error: "Invalid report request JSON." }, 400);
  }

  let body;
  try {
    body = JSON.parse(raw);
  } catch (_) {
    return json({ error: "Invalid report request JSON." }, 400);
  }

  const result = await submitUserReport({
    uid,
    body,
    env,
    adminToken: adminAuth.token,
    adminDatabaseRequest,
  });
  return json(result.payload, result.status);
}

async function enforceUserReportIpRateLimit(request, env) {
  const limiter = env.REPORT_IP_LIMITER;
  if (!limiter) {
    return json({
      error: "Report rate limiting is not configured on this Worker.",
    }, 503);
  }
  const sourceIp = request.headers.get("CF-Connecting-IP") || "unknown";
  const limit = await limiter.limit({ key: sourceIp });
  if (!limit.success) {
    return json({ error: "Too many report submissions. Please try again later." }, 429);
  }
  return null;
}

async function enforceEncryptedOlmPushIpRateLimit(request, env) {
  const ipLimiter = env.OLM_PUSH_IP_LIMITER;
  if (!ipLimiter) {
    return json({
      error: "Encrypted push IP rate limiting is not configured on this Worker.",
    }, 503);
  }

  const sourceIp = request.headers.get("CF-Connecting-IP") || "unknown";
  const limit = await ipLimiter.limit({ key: sourceIp });
  if (!limit.success) {
    return json({
      error: "Too many encrypted push requests. Please retry later.",
      retryable: true,
    }, 429);
  }
  return null;
}

async function enforceEncryptedOlmPushAccountRateLimit(env, uid) {
  const accountLimiter = env.OLM_PUSH_UID_LIMITER;
  if (!accountLimiter) {
    return json({
      error: "Encrypted push account rate limiting is not configured on this Worker.",
    }, 503);
  }

  const limit = await accountLimiter.limit({ key: uid });
  if (!limit.success) {
    return json({
      error: "Too many encrypted push requests. Please retry later.",
      retryable: true,
    }, 429);
  }
  return null;
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

      // Public, non-sensitive deployment probe. It reports readiness only;
      // it never exposes bindings, secrets, account data, or quota details.
      if (request.method === "GET" && url.pathname === "/health") {
        const retention = typeof env.OLM_MAILBOX_RETENTION_MS === "string" &&
          /^\d+$/.test(env.OLM_MAILBOX_RETENTION_MS) ?
          Number(env.OLM_MAILBOX_RETENTION_MS) : NaN;
        const mailboxReady = Boolean(env.OLM_MAILBOX) &&
          Number.isSafeInteger(retention) &&
          retention >= 60 * 60 * 1000 &&
          retention <= 30 * 24 * 60 * 60 * 1000;
        return json({
          status: mailboxReady ? "ok" : "not_ready",
          service: "inchat-worker",
          encryptedMailbox: mailboxReady ? "ready" : "not_ready",
        }, mailboxReady ? 200 : 503);
      }

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

      if (
        request.method === "POST" &&
        url.pathname === "/v1/olm/notify-group"
      ) {
        const ipLimitResponse = await enforceEncryptedOlmPushIpRateLimit(request, env);
        if (ipLimitResponse) return ipLimitResponse;
        const user = await authenticate(getBearer(request), env);
        const accountLimitResponse = await enforceEncryptedOlmPushAccountRateLimit(env, user.uid);
        if (accountLimitResponse) return accountLimitResponse;
        const adminAuth = await getFirebaseAdminToken(env);
        return processEncryptedOlmPushRequest(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/olm/notify"
      ) {
        // Apply the IP limit before calling Firebase Identity Toolkit so
        // invalid-token floods cannot turn this endpoint into an auth proxy.
        const ipLimitResponse = await enforceEncryptedOlmPushIpRateLimit(request, env);
        if (ipLimitResponse) return ipLimitResponse;

        const user = await authenticate(getBearer(request), env);
        const accountLimitResponse = await enforceEncryptedOlmPushAccountRateLimit(
          env,
          user.uid,
        );
        if (accountLimitResponse) return accountLimitResponse;

        const adminAuth = await getFirebaseAdminToken(env);
        return processEncryptedOlmPushRequest(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/reports"
      ) {
        // Throttle before Firebase token verification to limit auth-proxy abuse.
        const ipLimitResponse = await enforceUserReportIpRateLimit(request, env);
        if (ipLimitResponse) return ipLimitResponse;

        const user = await authenticate(getBearer(request), env);
        const adminAuth = await getFirebaseAdminToken(env);
        return processUserReportRequest(request, env, user.uid, adminAuth);
      }

      const isOlmMailboxRequest = url.pathname.startsWith("/v1/olm/mailbox/");
      if (isOlmMailboxRequest) {
        const ipLimitResponse = await enforceEncryptedOlmPushIpRateLimit(request, env);
        if (ipLimitResponse) return ipLimitResponse;
      }

      const user = await authenticate(getBearer(request), env);

      if (isOlmMailboxRequest) {
        const accountLimitResponse = await enforceEncryptedOlmPushAccountRateLimit(env, user.uid);
        if (accountLimitResponse) return accountLimitResponse;
        const adminAuth = await getFirebaseAdminToken(env);
        return processOlmMailboxRequest({
          request,
          env,
          uid: user.uid,
          adminToken: adminAuth,
          adminDatabaseRequest,
        });
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/events/message"
      ) {
        return json({
          error: "Legacy Firebase message events are disabled; encrypted delivery notifications use the mailbox.",
        }, 410);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/events/encrypted-direct-preview"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        let body;
        try {
          body = await request.json();
        } catch (_) {
          return json({ error: "Invalid encrypted direct preview request." }, 400);
        }
        try {
          const response = await syncEncryptedDirectPreview({
            env,
            uid: user.uid,
            body,
            adminToken: adminAuth.token,
            adminDatabaseRequest,
          });
          return json(response.payload, response.status);
        } catch (error) {
          console.error("Encrypted direct preview synchronization failed.", error);
          return json({
            error: "Encrypted direct preview synchronization failed.",
            retryable: true,
          }, 503);
        }
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/events/encrypted-group-preview"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        let body;
        try {
          body = await request.json();
        } catch (_) {
          return json({ error: "Invalid encrypted group preview request." }, 400);
        }
        try {
          const response = await syncEncryptedGroupPreview({
            env,
            uid: user.uid,
            body,
            adminToken: adminAuth.token,
            adminDatabaseRequest,
          });
          return json(response.payload, response.status);
        } catch (error) {
          console.error("Encrypted group preview synchronization failed.", error);
          return json({
            error: "Encrypted group preview synchronization failed.",
            retryable: true,
          }, 503);
        }
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/groups/manage-members"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return manageGroupMembership(request, env, user.uid, adminAuth);
      }

      if (
        request.method === "POST" &&
        url.pathname === "/v1/groups/crypto-membership"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return initializeGroupCryptoMembership(request, env, user.uid, adminAuth);
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
        url.pathname === "/v1/account/group-cleanup"
      ) {
        const adminAuth = await getFirebaseAdminToken(env);
        return cleanupAccountGroupsForDeletion(env, user.uid, adminAuth);
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


      if (request.method === "PUT") {
        // Do not accept source media bytes until the app has an authenticated
        // Olm/Megolm attachment envelope and a ciphertext-only storage flow.
        return json({
          error: "End-to-end encrypted attachment uploads are not available yet.",
        }, 501);
      }

      if (request.method === "GET" || request.method === "DELETE") {
        return json({
          error: "Legacy message-linked attachments are unavailable in the encrypted message path.",
        }, 410);
      }

      return json({ error: "Method not allowed" }, 405);
    } catch (error) {
      if (error instanceof Response) return error;
      console.error("InChat attachment worker error", error);
      return json({ error: "Attachment service error." }, 500);
    }
  },
};
