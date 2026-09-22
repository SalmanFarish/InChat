const {onValueCreated} = require("firebase-functions/v2/database");
const {setGlobalOptions} = require("firebase-functions/v2");
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
                .ref("users")
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
