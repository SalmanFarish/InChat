import assert from "node:assert/strict";
import { test } from "node:test";

import { firebaseUserDatabaseGet } from "../src/index.js";

test("Firebase RTDB metadata reads carry the ID token in Authorization, not the URL", async () => {
  const token = "short-lived-firebase-id-token";
  let capturedUrl;
  let capturedOptions;

  const response = await firebaseUserDatabaseGet(
    "chats/chat id/participantA",
    token,
    { FIREBASE_DATABASE_URL: "https://inchat.example.firebaseio.com/" },
    async (url, options) => {
      capturedUrl = url;
      capturedOptions = options;
      return new Response("{}", { status: 200 });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(
    capturedUrl,
    "https://inchat.example.firebaseio.com/chats/chat%20id/participantA.json",
  );
  assert.equal(capturedOptions.headers.Authorization, "Bearer " + token);
  assert.equal(capturedOptions.headers.Accept, "application/json");
  assert.equal(capturedUrl.includes(token), false);
  assert.equal(capturedUrl.includes("auth="), false);
});

test("Firebase RTDB user helper refuses whole-chat and message-history reads", async () => {
  const env = { FIREBASE_DATABASE_URL: "https://inchat.example.firebaseio.com" };
  await assert.rejects(
    firebaseUserDatabaseGet("chats/chat-id", "token", env),
    TypeError,
  );
  await assert.rejects(
    firebaseUserDatabaseGet("chats/chat-id/messages/message-id", "token", env),
    TypeError,
  );
});

test("Firebase RTDB user reads reject missing credentials", async () => {
  await assert.rejects(
    firebaseUserDatabaseGet("chats/chat", "", {
      FIREBASE_DATABASE_URL: "https://inchat.example.firebaseio.com",
    }),
    TypeError,
  );
});
