const {onValueCreated, onValueUpdated, onValueDeleted, onValueWritten} = require("firebase-functions/v2/database");
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
const messaging = admin.messaging();

setGlobalOptions({
  maxInstances: 10,
});

/*
 * Runs whenever a new message is created at:
 *
 * chats/{chatId}/messages/{messageId}
 */
exports.sendChatNotification = onValueCreated(
    {
      ref: "chats/{chatId}/messages/{messageId}",
      region: "us-central1",
    },
    async (event) => {
      const chatId = event.params.chatId;
      const messageId = event.params.messageId;

      try {
        const messageSnapshot = event.data;

        if (!messageSnapshot.exists()) {
          logger.warn("Message does not exist", {
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        const message = messageSnapshot.val();

        /*
       * Validate message data.
       */
        if (
          !message ||
        typeof message.senderId !== "string" ||
        typeof message.text !== "string"
        ) {
          logger.warn("Invalid message data", {
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        const senderId = message.senderId.trim();
        const messageText = message.text.trim();

        if (senderId === "" || messageText === "") {
          logger.warn("Empty sender or message text", {
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        /*
       * Load chat information.
       */
        const chatSnapshot = await database
            .ref("chats")
            .child(chatId)
            .once("value");

        if (!chatSnapshot.exists()) {
          logger.warn("Chat not found", {
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        const chat = chatSnapshot.val();

        if (
          chat &&
          chat.type === "group"
        ) {
          await sendGroupChatNotifications(
              chatId,
              messageId,
              message,
              chat,
          );
          return;
        }

        const participantA = chat.participantA;
        const participantB = chat.participantB;

        if (
          typeof participantA !== "string" ||
        typeof participantB !== "string" ||
        participantA.trim() === "" ||
        participantB.trim() === "" ||
        participantA === participantB
        ) {
          logger.warn("Invalid chat participants", {
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        /*
       * Determine the receiver.
       */
        let receiverId = "";

        if (senderId === participantA) {
          receiverId = participantB;
        } else if (senderId === participantB) {
          receiverId = participantA;
        } else {
          logger.warn("Sender is not a chat participant", {
            chatId: chatId,
            messageId: messageId,
            senderId: senderId,
          });
          return;
        }

        /*
       * Never notify the sender.
       */
        if (receiverId === "" || receiverId === senderId) {
          return;
        }

        /*
       * Check whether the sender blocked the receiver.
       */
        const senderBlockedReceiverSnapshot = await database
            .ref("blockedUsers")
            .child(senderId)
            .child(receiverId)
            .once("value");

        /*
       * Check whether the receiver blocked the sender.
       */
        const receiverBlockedSenderSnapshot = await database
            .ref("blockedUsers")
            .child(receiverId)
            .child(senderId)
            .once("value");

        if (
          senderBlockedReceiverSnapshot.exists() ||
        receiverBlockedSenderSnapshot.exists()
        ) {
          logger.info(
              "Notification skipped because users are blocked",
              {
                chatId: chatId,
                messageId: messageId,
                senderId: senderId,
                receiverId: receiverId,
              },
          );
          return;
        }

        /*
       * Load receiver profile.
       */
        const receiverSnapshot = await database
            .ref("privateUsers")
            .child(receiverId)
            .once("value");

        if (!receiverSnapshot.exists()) {
          logger.warn("Receiver profile does not exist", {
            receiverId: receiverId,
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        const receiver = receiverSnapshot.val();
        const fcmToken = receiver.fcmToken;

        /*
       * No token means there is currently no registered
       * notification destination.
       */
        if (
          typeof fcmToken !== "string" ||
        fcmToken.trim() === ""
        ) {
          logger.info("Receiver has no FCM token", {
            receiverId: receiverId,
            chatId: chatId,
            messageId: messageId,
          });
          return;
        }

        /*
       * Get sender display name.
       */
        let senderNickname = "InChat user";

        if (
          typeof message.senderNickname === "string" &&
        message.senderNickname.trim() !== ""
        ) {
          senderNickname = message.senderNickname.trim();
        }

        const notificationTitle =
        "@" + senderNickname;

        /*
       * Build FCM message.
       */
        const fcmMessage = {
          token: fcmToken,

          notification: {
            title: notificationTitle,
            body: messageText,
          },

          data: {
            type: "chat_message",
            chatId: String(chatId),
            messageId: String(messageId),
            senderId: String(senderId),
            senderName: String(senderNickname),
          },

          android: {
            priority: "high",

            notification: {
              channelId: "inchat_messages",
              icon: "ic_stat_inchat",
            },
          },
        };

        /*
       * Send notification.
       */
        try {
          const response = await messaging.send(
              fcmMessage,
          );

          logger.info("InChat notification sent", {
            response: response,
            chatId: chatId,
            messageId: messageId,
            senderId: senderId,
            receiverId: receiverId,
          });
        } catch (sendError) {
          let errorCode = "";

          if (
            sendError &&
          sendError.errorInfo &&
          sendError.errorInfo.code
          ) {
            errorCode = sendError.errorInfo.code;
          }

          logger.error("FCM notification failed", {
            code: errorCode,
            message:
            sendError && sendError.message ?
              sendError.message :
              String(sendError),
            receiverId: receiverId,
            chatId: chatId,
            messageId: messageId,
          });

          /*
         * Remove stale FCM tokens.
         */
          if (
            errorCode ===
            "messaging/registration-token-not-registered" ||
          errorCode ===
            "messaging/invalid-registration-token"
          ) {
            await database
                .ref("privateUsers")
                .child(receiverId)
                .child("fcmToken")
                .removeValue();

            logger.info("Removed invalid FCM token", {
              receiverId: receiverId,
            });
          }

          return;
        }
      } catch (error) {
        logger.error(
            "Unexpected notification function error",
            {
              error:
            error && error.message ?
              error.message :
              String(error),
              chatId: chatId,
              messageId: messageId,
            },
        );

        throw error;
      }
    },
);


/*
 * Keeps both Home conversation previews synchronized when the
 * latest message is edited or deleted.
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

  const lastMessage = latest &&
    typeof latest.child("text").val() === "string" ?
    latest.child("text").val() :
    "";

  const lastSenderId = latest &&
    typeof latest.child("senderId").val() === "string" ?
    latest.child("senderId").val() :
    "";

  const lastSenderNickname = latest &&
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

  const groupPhotoUrl =
    typeof chat.photoUrl === "string" ?
    chat.photoUrl :
    "";

  const updates = {};

  for (const uid of members) {
    updates[`userChats/${uid}/${chatId}`] = {
      chatId: chatId,
      chatType: "group",
      groupName: groupName,
      groupPhotoUrl: groupPhotoUrl,
      memberCount: members.length,
      otherUserId: "",
      otherUsername: "",
      lastMessage: lastMessage,
      lastTimestamp: lastTimestamp,
      lastSenderId: lastSenderId,
      lastSenderNickname: lastSenderNickname,
      unreadCount: 0,
    };
  }

  await database.ref().update(updates);
}

async function sendGroupChatNotifications(
    chatId,
    messageId,
    message,
    chat,
) {
  const senderId =
    typeof message.senderId === "string" ?
    message.senderId.trim() :
    "";

  const messageText =
    typeof message.text === "string" ?
    message.text.trim() :
    "";

  const senderNickname =
    typeof message.senderNickname === "string" &&
    message.senderNickname.trim() !== "" ?
    message.senderNickname.trim() :
    "InChat user";

  const groupName =
    typeof chat.name === "string" &&
    chat.name.trim() !== "" ?
    chat.name.trim() :
    "Group";

  const memberIds =
    Object.keys(chat.members || {})
        .filter((uid) => uid && uid !== senderId);

  if (
    senderId === "" ||
    messageText === "" ||
    memberIds.length === 0
  ) {
    return;
  }

  const notificationMessages = [];

  for (const receiverId of memberIds) {
    const receiverSnapshot = await database
        .ref("privateUsers")
        .child(receiverId)
        .once("value");

    if (!receiverSnapshot.exists()) {
      continue;
    }

    const receiver = receiverSnapshot.val();
    const fcmToken = receiver.fcmToken;

    if (
      typeof fcmToken !== "string" ||
      fcmToken.trim() === ""
    ) {
      continue;
    }

    notificationMessages.push({
      receiverId: receiverId,
      fcmToken: fcmToken,
      message: {
        token: fcmToken,
        notification: {
          title:
            groupName + " • " + senderNickname,
          body: messageText,
        },
        data: {
          type: "group_message",
          chatId: String(chatId),
          messageId: String(messageId),
          senderId: String(senderId),
          senderName: String(senderNickname),
          groupName: String(groupName),
        },
        android: {
          priority: "high",
          notification: {
            channelId: "inchat_messages",
            icon: "ic_stat_inchat",
          },
        },
      },
    });
  }

  await Promise.all(
      notificationMessages.map(async (entry) => {
        try {
          await messaging.send(entry.message);

          logger.info(
              "InChat group notification sent",
              {
                chatId: chatId,
                messageId: messageId,
                receiverId: entry.receiverId,
              },
          );
        } catch (sendError) {
          let errorCode = "";

          if (
            sendError &&
            sendError.errorInfo &&
            sendError.errorInfo.code
          ) {
            errorCode = sendError.errorInfo.code;
          }

          logger.error(
              "InChat group notification failed",
              {
                code: errorCode,
                message:
                  sendError && sendError.message ?
                    sendError.message :
                    String(sendError),
                receiverId: entry.receiverId,
                chatId: chatId,
                messageId: messageId,
              },
          );

          if (
            errorCode ===
              "messaging/registration-token-not-registered" ||
            errorCode ===
              "messaging/invalid-registration-token"
          ) {
            await database
                .ref("privateUsers")
                .child(entry.receiverId)
                .child("fcmToken")
                .removeValue();
          }
        }
      }),
  );
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
