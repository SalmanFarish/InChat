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

async function authorizeMessageSender(
  chatId,
  messageId,
  uid,
  token,
  env,
) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\\/$/, "");
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

  if (!message || message.senderId !== uid) {
    throw new Response("Only the message sender can upload this attachment.", {
      status: 403,
    });
  }

  return message;
}

function attachmentKey(chatId, messageId) {
  return "attachments/" + chatId + "/" + messageId;
}

async function upload(request, env, uid, chatId, messageId) {
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

  const key = attachmentKey(chatId, messageId);

  if (await env.ATTACHMENTS.head(key)) {
    return json({ error: "Attachment already exists." }, 409);
  }

  if (!request.body) {
    return json({ error: "Empty upload." }, 400);
  }

  const object = await env.ATTACHMENTS.put(key, request.body, {
    httpMetadata: {
      contentType,
      contentDisposition:
        'attachment; filename="' + fileName.replace(/"/g, "_") + '"',
      cacheControl: "private, no-store",
    },
    customMetadata: {
      senderId: uid,
      chatId,
      messageId,
      attachmentType,
      fileName,
    },
  });

  return json({
    success: true,
    key,
    size: object?.size || contentLength,
    contentType,
    fileName,
    attachmentType,
  }, 201);
}

async function download(env, chatId, messageId, uid) {
  const object = await env.ATTACHMENTS.get(
    attachmentKey(chatId, messageId),
  );

  if (!object) {
    return new Response("Attachment not found", { status: 404 });
  }

  if (object.customMetadata?.attachmentType === "view_once_photo" &&
      object.customMetadata?.senderId === uid) {
    return new Response("Sender cannot consume a view-once photo", { status: 403 });
  }

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("Cache-Control", "private, no-store");
  headers.set(
    "X-InChat-File-Name",
    object.customMetadata?.fileName || "attachment",
  );
  const attachmentType = object.customMetadata?.attachmentType || "voice";
  headers.set("X-InChat-Attachment-Type", attachmentType);

  if (attachmentType === "view_once_photo") {
    headers.set("X-InChat-View-Once", "true");
    await env.ATTACHMENTS.delete(attachmentKey(chatId, messageId));
  }

  return new Response(object.body, { status: 200, headers });
}

async function remove(env, chatId, messageId) {
  const key = attachmentKey(chatId, messageId);
  const object = await env.ATTACHMENTS.head(key);

  if (!object) {
    return json({ success: true, deleted: false });
  }

  await env.ATTACHMENTS.delete(key);
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
            "Access-Control-Allow-Methods": "GET, PUT, DELETE, OPTIONS",
          },
        });
      }

      const url = new URL(request.url);
      const match = url.pathname.match(
        /^\/v1\/attachments\/([^/]+)\/([^/]+)$/,
      );

      if (!match) return json({ error: "Not found" }, 404);

      const chatId = cleanSegment(match[1], "chatId");
      const messageId = cleanSegment(match[2], "messageId");

      const user = await authenticate(getBearer(request), env);
      await authorizeChat(chatId, user.uid, user.token, env);

      if (request.method === "PUT") {
        await authorizeMessageSender(
          chatId,
          messageId,
          user.uid,
          user.token,
          env,
        );
        return upload(request, env, user.uid, chatId, messageId);
      }

      if (request.method === "GET") {
        return download(env, chatId, messageId, user.uid);
      }

      if (request.method === "DELETE") {
        return remove(env, chatId, messageId);
      }

      return json({ error: "Method not allowed" }, 405);
    } catch (error) {
      if (error instanceof Response) return error;
      console.error("InChat attachment worker error", error);
      return json({ error: "Attachment service error." }, 500);
    }
  },
};
