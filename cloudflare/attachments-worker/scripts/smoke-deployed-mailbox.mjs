import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const DEFAULT_WORKER_URL = "https://icy-base-2bc4.s91670002.workers.dev";
const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const DELIVERY_PATTERN = /^[a-f0-9]{64}$/;
const REQUEST_TIMEOUT_MS = 15_000;

function requireValue(value, name) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(name + " is required.");
  }
  return value.trim();
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function parseJson(text, context) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (_) {
    throw new Error(context + " returned invalid JSON.");
  }
}

async function request(fetchImpl, url, options, context) {
  let response;
  try {
    response = await fetchImpl(url, {
      redirect: "error",
      ...options,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    throw new Error(context + " request failed: " + error.message);
  }

  const text = await response.text();
  const payload = parseJson(text, context);
  if (!response.ok) {
    const detail = typeof payload?.error === "string" ? payload.error :
      (typeof payload?.message === "string" ? payload.message : "");
    throw new Error(context + " failed with HTTP " + response.status +
      (detail ? ": " + detail : "."));
  }
  return { response, payload };
}

async function authenticateAccount(account, role, apiKey, fetchImpl) {
  let result;
  if (account.idToken) {
    result = await request(
      fetchImpl,
      "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" +
        encodeURIComponent(apiKey),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ idToken: account.idToken }),
      },
      "Firebase " + role + " token validation",
    );
  } else {
    result = await request(
      fetchImpl,
      "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" +
        encodeURIComponent(apiKey),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: account.email,
          password: account.password,
          returnSecureToken: true,
        }),
      },
      "Firebase " + role + " test-account sign-in",
    );
  }

  const user = account.idToken ? result.payload?.users?.[0] : result.payload;
  const uid = user?.localId;
  const idToken = account.idToken || user?.idToken;
  assert(typeof uid === "string" && UID_PATTERN.test(uid),
    "Firebase did not return a valid " + role + " UID.");
  assert(typeof idToken === "string" && idToken.length > 20,
    "Firebase did not return a valid " + role + " ID token.");
  if (account.expectedUid) {
    assert(uid === account.expectedUid,
      "The signed-in " + role + " account UID does not match the configured UID.");
  }
  return { uid, idToken };
}

async function readPublishedDevices(databaseUrl, account, role, fetchImpl) {
  const url = databaseUrl.replace(/\/+$/, "") + "/deviceKeys/" +
    encodeURIComponent(account.uid) + ".json";
  const result = await request(
    fetchImpl,
    url,
    { method: "GET", headers: { Authorization: "Bearer " + account.idToken } },
    "Firebase " + role + " published-device lookup",
  );
  const entries = result.payload && typeof result.payload === "object" ?
    Object.entries(result.payload) : [];
  const ids = entries
    .filter(([id, value]) =>
      DEVICE_PATTERN.test(id) &&
      value?.schemaVersion === 1 &&
      value?.deviceId === id &&
      typeof value?.ed25519IdentityKey === "string" &&
      value.ed25519IdentityKey.length > 0)
    .map(([id]) => id);
  return ids;
}

function chooseDeviceId(candidates, configuredId, role, requiredExplicitly) {
  if (configuredId) {
    assert(DEVICE_PATTERN.test(configuredId),
      "Configured " + role + " device ID has an invalid format.");
    assert(candidates.includes(configuredId),
      "Configured " + role + " device ID is not currently published for that account.");
    return configuredId;
  }
  assert(!requiredExplicitly,
    "Set INCHAT_MAILBOX_TEST_RECIPIENT_DEVICE_ID to a published, inactive test device.");
  assert(candidates.length === 1,
    "Set the " + role + " device ID explicitly; published devices found: " +
      (candidates.length ? candidates.join(", ") : "none") + ".");
  return candidates[0];
}

async function workerRequest(baseUrl, account, method, route, body, fetchImpl) {
  const result = await request(
    fetchImpl,
    baseUrl + route,
    {
      method,
      headers: {
        Authorization: "Bearer " + account.idToken,
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    "Worker " + method + " " + route.split("?")[0],
  );
  return result.payload;
}

async function listMailboxItems(baseUrl, account, route, deviceId, fetchImpl) {
  const items = [];
  let cursor = "0";
  for (let page = 0; page < 12; page += 1) {
    const payload = await workerRequest(
      baseUrl,
      account,
      "GET",
      route + "?deviceId=" + encodeURIComponent(deviceId) +
        "&limit=50&cursor=" + cursor,
      undefined,
      fetchImpl,
    );
    assert(Array.isArray(payload?.items),
      "Worker mailbox page did not contain an items array.");
    items.push(...payload.items);
    if (payload.nextCursor === null || payload.nextCursor === undefined) return items;
    assert(typeof payload.nextCursor === "string" && /^\d{1,16}$/.test(payload.nextCursor),
      "Worker mailbox returned an invalid pagination cursor.");
    cursor = payload.nextCursor;
  }
  throw new Error("Mailbox pagination exceeded the safety limit of 12 pages.");
}

function expectContains(items, deliveryId, description) {
  const item = items.find((candidate) => candidate?.deliveryId === deliveryId);
  assert(item, description + " did not contain the test delivery.");
  return item;
}

async function bestEffortCleanup(baseUrl, sender, recipient, senderDeviceId,
  recipientDeviceId, deliveryId, wasEnqueued, fetchImpl) {
  if (!wasEnqueued) return;
  const attempts = [
    () => workerRequest(baseUrl, recipient, "POST",
      "/v1/olm/mailbox/acknowledge?deviceId=" + encodeURIComponent(recipientDeviceId),
      { deliveryId }, fetchImpl),
    () => workerRequest(baseUrl, recipient, "DELETE",
      "/v1/olm/mailbox/inbox/" + deliveryId +
        "?deviceId=" + encodeURIComponent(recipientDeviceId),
      undefined, fetchImpl),
    () => workerRequest(baseUrl, sender, "DELETE",
      "/v1/olm/mailbox/acks/" + deliveryId +
        "?deviceId=" + encodeURIComponent(senderDeviceId),
      undefined, fetchImpl),
  ];
  for (const attempt of attempts) {
    try { await attempt(); } catch (_) { /* Preserve the original test failure. */ }
  }
}

/**
 * Exercises the deployed authenticated mailbox API with a synthetic envelope.
 * This validates Worker transport/auth/ACK semantics, not Olm cryptography.
 */
export async function runMailboxRoundTrip(config, fetchImpl = fetch) {
  assert(config?.confirmRecipientDeviceOffline === true,
    "Refusing to send synthetic ciphertext unless the recipient test device is confirmed offline.");
  const baseUrl = requireValue(config.workerUrl, "Worker URL").replace(/\/+$/, "");
  const databaseUrl = requireValue(config.databaseUrl, "Firebase Realtime Database URL").replace(/\/+$/, "");
  const apiKey = requireValue(config.firebaseApiKey, "Firebase Web API key");
  assert(/^https:\/\//i.test(baseUrl), "Worker URL must use HTTPS.");
  assert(/^https:\/\//i.test(databaseUrl), "Firebase database URL must use HTTPS.");

  const sender = await authenticateAccount(config.sender, "sender", apiKey, fetchImpl);
  const recipient = await authenticateAccount(config.recipient, "recipient", apiKey, fetchImpl);
  assert(sender.uid !== recipient.uid,
    "Sender and recipient must be two different Firebase accounts for this direct-chat test.");

  const [senderDevices, recipientDevices] = await Promise.all([
    readPublishedDevices(databaseUrl, sender, "sender", fetchImpl),
    readPublishedDevices(databaseUrl, recipient, "recipient", fetchImpl),
  ]);
  const senderDeviceId = chooseDeviceId(
    senderDevices, config.sender.deviceId, "sender", false,
  );
  const recipientDeviceId = chooseDeviceId(
    recipientDevices, config.recipient.deviceId, "recipient", true,
  );

  const chatId = [sender.uid, recipient.uid].sort().join("_");
  const nonce = randomBytes(16).toString("hex");
  const deliveryId = randomBytes(32).toString("hex");
  const messageId = "mailbox-probe-" + nonce;
  const envelope = JSON.stringify({
    encryptionVersion: 1,
    algorithm: "olm",
    senderDeviceId,
    recipientDeviceId,
    sessionId: "transport-probe-" + nonce,
    ciphertext: Buffer.from("InChat mailbox transport probe " + nonce, "utf8").toString("base64url"),
  });
  const enqueueBody = {
    conversationId: chatId,
    conversationPeerUserId: recipient.uid,
    conversationType: "direct",
    deliveryId,
    envelope,
    messageId,
    recipientDeviceId,
    recipientUid: recipient.uid,
    senderDeviceId,
  };

  let wasEnqueued = false;
  let completed = false;
  try {
    const firstUpload = await workerRequest(
      baseUrl, sender, "POST", "/v1/olm/mailbox/enqueue", enqueueBody, fetchImpl,
    );
    wasEnqueued = true;
    assert(firstUpload?.accepted === true && firstUpload.duplicate === false,
      "Worker did not report the first enqueue as a new accepted delivery.");

    const duplicateUpload = await workerRequest(
      baseUrl, sender, "POST", "/v1/olm/mailbox/enqueue", enqueueBody, fetchImpl,
    );
    assert(duplicateUpload?.accepted === true && duplicateUpload.duplicate === true,
      "Exact enqueue retry was not recognized as a duplicate.");
    assert(duplicateUpload.expiresAt === firstUpload.expiresAt,
      "Duplicate enqueue changed the original server expiry.");

    const firstInbox = await listMailboxItems(
      baseUrl, recipient, "/v1/olm/mailbox/inbox", recipientDeviceId, fetchImpl,
    );
    const received = expectContains(firstInbox, deliveryId, "Recipient inbox");
    assert(received.envelope === envelope,
      "Recipient inbox did not return the exact uploaded envelope.");
    assert(received.senderUid === sender.uid &&
      received.senderDeviceId === senderDeviceId &&
      received.messageId === messageId &&
      received.conversationId === chatId &&
      received.conversationPeerUserId === recipient.uid,
      "Retrieved delivery metadata did not match the submitted sender/conversation.");

    const firstAck = await workerRequest(
      baseUrl,
      recipient,
      "POST",
      "/v1/olm/mailbox/acknowledge?deviceId=" + encodeURIComponent(recipientDeviceId),
      { deliveryId },
      fetchImpl,
    );
    assert(firstAck?.acknowledged === true,
      "Recipient acknowledgement was not accepted.");

    const stagedAcks = await listMailboxItems(
      baseUrl, sender, "/v1/olm/mailbox/acks", senderDeviceId, fetchImpl,
    );
    const stagedAck = expectContains(stagedAcks, deliveryId, "Sender acknowledgement mailbox");
    assert(stagedAck.recipientUid === recipient.uid &&
      stagedAck.recipientDeviceId === recipientDeviceId &&
      stagedAck.messageId === messageId,
      "Sender acknowledgement metadata did not match the recipient delivery.");

    await workerRequest(
      baseUrl,
      recipient,
      "DELETE",
      "/v1/olm/mailbox/inbox/" + deliveryId +
        "?deviceId=" + encodeURIComponent(recipientDeviceId),
      undefined,
      fetchImpl,
    );

    const ackAfterCiphertextRemoval = await listMailboxItems(
      baseUrl, sender, "/v1/olm/mailbox/acks", senderDeviceId, fetchImpl,
    );
    expectContains(ackAfterCiphertextRemoval, deliveryId,
      "Sender acknowledgement after recipient ciphertext removal");

    // Simulate the Android interruption window where the sender consumed its
    // ACK but did not yet commit the matching local durable receipt.
    await workerRequest(
      baseUrl,
      sender,
      "DELETE",
      "/v1/olm/mailbox/acks/" + deliveryId +
        "?deviceId=" + encodeURIComponent(senderDeviceId),
      undefined,
      fetchImpl,
    );

    const replay = await workerRequest(
      baseUrl, sender, "POST", "/v1/olm/mailbox/enqueue", enqueueBody, fetchImpl,
    );
    assert(replay?.accepted === true && replay.replayedAfterAck === true,
      "Retry after a consumed ACK did not re-stage the original delivery.");

    const replayedInbox = await listMailboxItems(
      baseUrl, recipient, "/v1/olm/mailbox/inbox", recipientDeviceId, fetchImpl,
    );
    const replayed = expectContains(replayedInbox, deliveryId, "Recovered recipient inbox");
    assert(replayed.envelope === envelope,
      "ACK recovery changed the original envelope.");

    const repeatedAck = await workerRequest(
      baseUrl,
      recipient,
      "POST",
      "/v1/olm/mailbox/acknowledge?deviceId=" + encodeURIComponent(recipientDeviceId),
      { deliveryId },
      fetchImpl,
    );
    assert(repeatedAck?.acknowledged === true,
      "Recipient could not acknowledge the recovered delivery.");

    await workerRequest(
      baseUrl,
      recipient,
      "DELETE",
      "/v1/olm/mailbox/inbox/" + deliveryId +
        "?deviceId=" + encodeURIComponent(recipientDeviceId),
      undefined,
      fetchImpl,
    );

    const finalAcks = await listMailboxItems(
      baseUrl, sender, "/v1/olm/mailbox/acks", senderDeviceId, fetchImpl,
    );
    expectContains(finalAcks, deliveryId, "Recovered sender acknowledgement");

    await workerRequest(
      baseUrl,
      sender,
      "DELETE",
      "/v1/olm/mailbox/acks/" + deliveryId +
        "?deviceId=" + encodeURIComponent(senderDeviceId),
      undefined,
      fetchImpl,
    );

    const [remainingInbox, remainingAcks] = await Promise.all([
      listMailboxItems(baseUrl, recipient, "/v1/olm/mailbox/inbox", recipientDeviceId, fetchImpl),
      listMailboxItems(baseUrl, sender, "/v1/olm/mailbox/acks", senderDeviceId, fetchImpl),
    ]);
    assert(!remainingInbox.some((item) => item?.deliveryId === deliveryId),
      "Test delivery remained in the recipient inbox after cleanup.");
    assert(!remainingAcks.some((item) => item?.deliveryId === deliveryId),
      "Test acknowledgement remained in the sender mailbox after cleanup.");

    completed = true;
    return {
      deliveryId,
      senderUid: sender.uid,
      recipientUid: recipient.uid,
      senderDeviceId,
      recipientDeviceId,
      checks: [
        "Firebase authentication and published-device authorization",
        "Authenticated ciphertext enqueue",
        "Exact duplicate retry and stable expiry",
        "Recipient inbox retrieval and envelope integrity",
        "Sender ACK persisted before recipient ciphertext deletion",
        "Lost-ACK recovery and ciphertext re-staging",
        "Final recipient and sender mailbox cleanup",
      ],
    };
  } finally {
    if (!completed) {
      await bestEffortCleanup(
        baseUrl, sender, recipient, senderDeviceId, recipientDeviceId,
        deliveryId, wasEnqueued, fetchImpl,
      );
    }
  }
}

async function loadCliConfig(env = process.env) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
  const googleServices = JSON.parse(
    await readFile(resolve(root, "app/google-services.json"), "utf8"),
  );
  const workerUrl = env.INCHAT_WORKER_URL || DEFAULT_WORKER_URL;
  const firebaseApiKey = env.INCHAT_FIREBASE_WEB_API_KEY ||
    googleServices.client?.[0]?.api_key?.[0]?.current_key;
  const databaseUrl = env.INCHAT_FIREBASE_DATABASE_URL ||
    googleServices.project_info?.firebase_url;
  const sender = {
    idToken: env.INCHAT_MAILBOX_TEST_SENDER_ID_TOKEN,
    email: env.INCHAT_MAILBOX_TEST_SENDER_EMAIL,
    password: env.INCHAT_MAILBOX_TEST_SENDER_PASSWORD,
    expectedUid: env.INCHAT_MAILBOX_TEST_SENDER_UID,
    deviceId: env.INCHAT_MAILBOX_TEST_SENDER_DEVICE_ID,
  };
  const recipient = {
    idToken: env.INCHAT_MAILBOX_TEST_RECIPIENT_ID_TOKEN,
    email: env.INCHAT_MAILBOX_TEST_RECIPIENT_EMAIL,
    password: env.INCHAT_MAILBOX_TEST_RECIPIENT_PASSWORD,
    expectedUid: env.INCHAT_MAILBOX_TEST_RECIPIENT_UID,
    deviceId: env.INCHAT_MAILBOX_TEST_RECIPIENT_DEVICE_ID,
  };
  for (const [role, account] of [["sender", sender], ["recipient", recipient]]) {
    assert(Boolean(account.idToken) || (Boolean(account.email) && Boolean(account.password)),
      "Provide either " + role + " ID token or both " + role + " email and password environment variables.");
  }
  assert(Boolean(recipient.deviceId),
    "INCHAT_MAILBOX_TEST_RECIPIENT_DEVICE_ID must select the inactive test device explicitly.");
  return {
    workerUrl,
    firebaseApiKey,
    databaseUrl,
    sender,
    recipient,
    confirmRecipientDeviceOffline:
      env.INCHAT_MAILBOX_TEST_CONFIRM_RECIPIENT_DEVICE_OFFLINE === "YES",
  };
}

async function main() {
  const result = await runMailboxRoundTrip(await loadCliConfig());
  console.log("Deployed authenticated mailbox round trip passed.");
  console.log("Sender UID/device: " + result.senderUid + " / " + result.senderDeviceId);
  console.log("Recipient UID/device: " + result.recipientUid + " / " + result.recipientDeviceId);
  console.log("Delivery ID: " + result.deliveryId);
  for (const check of result.checks) console.log("PASS  " + check);
  console.log("Synthetic transport probe only; this does not test Olm encryption or Android decryption.");
}

const isMain = process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isMain) {
  main().catch((error) => {
    console.error("Deployed mailbox test failed: " + error.message);
    process.exitCode = 1;
  });
}
