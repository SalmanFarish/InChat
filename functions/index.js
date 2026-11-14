const {setGlobalOptions} = require("firebase-functions/v2");
const {
  onValueCreated,
  onValueUpdated,
  onValueDeleted,
  onValueWritten,
} = require("firebase-functions/v2/database");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

admin.initializeApp();

const database = admin.database();

setGlobalOptions({
  maxInstances: 10,
});

/*
 * New-message push notifications are delivered exclusively by the
 * Cloudflare Worker at /v1/events/message. Do not reintroduce a Firebase
 * onValueCreated notification trigger here: it would send duplicate FCM
 * notifications whenever both backends are active.
 */

/**
 * Keeps both Home conversation previews synchronized when the
 * latest message is edited or deleted.
 *
 * @param {string} chatId Firebase chat ID.
 */
async function syncConversationPreview(chatId) {
  const chatSnapshot = await database.ref("chats").child(chatId).once("value");

  if (!chatSnapshot.exists()) {
    return;
  }

  const chat = chatSnapshot.val();

  if (
    chat &&
    chat.type === "group"
  ) {
    await syncGroupConversationPreview(
        chatId,
        chat,
    );
    return;
  }

  const participantA = chat.participantA;
  const participantB = chat.participantB;

  if (
    typeof participantA !== "string" ||
    typeof participantB !== "string" ||
    participantA === "" ||
    participantB === "" ||
    participantA === participantB
  ) {
    logger.warn("Cannot sync preview: invalid participants", {chatId});
    return;
  }

  const messagesSnapshot = await database
      .ref("chats")
      .child(chatId)
      .child("messages")
      .orderByChild("timestamp")
      .limitToLast(1)
      .once("value");

  let latest = null;
  messagesSnapshot.forEach((child) => {
    latest = child;
  });

  const lastMessage = latest && typeof latest.child("text").val() === "string" ?
    latest.child("text").val() :
    "";

  const lastSenderId = latest &&
    typeof latest.child("senderId").val() === "string" ?
    latest.child("senderId").val() :
    "";

  const lastTimestamp = latest &&
    typeof latest.child("timestamp").val() === "number" ?
    latest.child("timestamp").val() :
    0;

  const updates = {};

  for (const uid of [participantA, participantB]) {
    updates[`userChats/${uid}/${chatId}/lastMessage`] = lastMessage;
    updates[`userChats/${uid}/${chatId}/lastSenderId`] = lastSenderId;
    updates[`userChats/${uid}/${chatId}/lastTimestamp`] = lastTimestamp;
  }

  await database.ref().update(updates);
}

/**
 * Synchronizes a group's Home conversation preview for every member.
 *
 * @param {string} chatId Firebase chat ID.
 * @param {Object} chat Group chat data.
 */
async function syncGroupConversationPreview(chatId, chat) {
  const members = Object.keys(chat.members || {});

  if (members.length < 2) {
    logger.warn(
        "Cannot sync group preview: invalid members",
        {chatId},
    );
    return;
  }

  const messagesSnapshot = await database
      .ref("chats")
      .child(chatId)
      .child("messages")
      .orderByChild("timestamp")
      .limitToLast(1)
      .once("value");

  let latest = null;
  messagesSnapshot.forEach((child) => {
    latest = child;
  });

  const isSystemEvent = !!(
    latest &&
    typeof latest.child("systemEvent").val() === "string" &&
    latest.child("systemEvent").val().trim() !== ""
  );

  const lastMessage = latest &&
    typeof latest.child("text").val() === "string" ?
    latest.child("text").val() :
    "";

  const lastSenderId = !isSystemEvent && latest &&
    typeof latest.child("senderId").val() === "string" ?
    latest.child("senderId").val() :
    "";

  const lastSenderNickname = !isSystemEvent && latest &&
    typeof latest.child("senderNickname").val() === "string" ?
    latest.child("senderNickname").val() :
    "";

  const lastTimestamp = latest &&
    typeof latest.child("timestamp").val() === "number" ?
    latest.child("timestamp").val() :
    0;

  const groupName =
    typeof chat.name === "string" ?
    chat.name.trim() :
    "";

  const updates = {};
  const previousEntries = await Promise.all(
      members.map((uid) =>
        database.ref("userChats").child(uid).child(chatId).once("value"),
      ),
  );

  members.forEach((uid, index) => {
    const previous = previousEntries[index].val() || {};
    const previousTimestamp =
      typeof previous.lastTimestamp === "number" ?
      previous.lastTimestamp :
      0;
    const previousUnread =
      typeof previous.unreadCount === "number" && previous.unreadCount >= 0 ?
      previous.unreadCount :
      0;

    const isNewIncomingMessage =
      !isSystemEvent &&
      lastMessage !== "" &&
      lastSenderId !== "" &&
      lastSenderId !== uid &&
      lastTimestamp > previousTimestamp;

    updates[`userChats/${uid}/${chatId}`] = {
      ...previous,
      chatId: chatId,
      chatType: "group",
      groupName: groupName,
      groupPhotoUrl: "",
      memberCount: members.length,
      otherUserId: "",
      otherUsername: "",
      lastMessage: lastMessage,
      lastTimestamp: lastTimestamp,
      lastSenderId: lastSenderId,
      lastSenderNickname: lastSenderNickname,
      unreadCount: previousUnread + (isNewIncomingMessage ? 1 : 0),
    };
  });

  await database.ref().update(updates);
}

exports.syncCreatedGroupPreview = onValueCreated(
    {
      ref: "chats/{chatId}",
      region: "us-central1",
    },
    async (event) => {
      const chat = event.data.val();

      if (
        chat &&
        chat.type === "group"
      ) {
        await syncGroupConversationPreview(
            event.params.chatId,
            chat,
        );
      }
    },
);


/*
 * A text edit can change the Home preview only if the edited
 * message is still the latest message.
 */
exports.syncEditedMessagePreview = onValueUpdated(
    {
      ref: "chats/{chatId}/messages/{messageId}",
      region: "us-central1",
    },
    async (event) => {
      const before = event.data.before.val();
      const after = event.data.after.val();

      if (
        !before ||
        !after ||
        before.text === after.text
      ) {
        return;
      }

      const chatId = event.params.chatId;
      const latestSnapshot = await database
          .ref("chats")
          .child(chatId)
          .child("messages")
          .orderByChild("timestamp")
          .limitToLast(1)
          .once("value");

      let latest = null;
      latestSnapshot.forEach((child) => {
        latest = child;
      });

      if (
        latest &&
        latest.key === event.params.messageId
      ) {
        await syncConversationPreview(chatId);
      }
    },
);

/*
 * After deleting the latest message, rebuild both Home previews
 * from the new latest message.
 */
exports.syncDeletedMessagePreview = onValueDeleted(
    {
      ref: "chats/{chatId}/messages/{messageId}",
      region: "us-central1",
    },
    async (event) => {
      await syncConversationPreview(event.params.chatId);
    },
);


/*
 * Derive account-wide presence from connection nodes.
 * A disconnecting device can only remove its own connection;
 * this function is the single writer of shared online state.
 */
exports.syncPresence = onValueWritten(
    {
      ref: "presence/{uid}/connections",
      region: "us-central1",
    },
    async (event) => {
      const uid = event.params.uid;
      const connections = event.data.after.val() || {};

      const online = Object.keys(connections).length > 0;

      const updates = {
        [`presence/${uid}/online`]: online,
      };

      if (!online) {
        updates[`presence/${uid}/lastSeen`] =
          admin.database.ServerValue.TIMESTAMP;
      }

      await database.ref().update(updates);
    },
);
