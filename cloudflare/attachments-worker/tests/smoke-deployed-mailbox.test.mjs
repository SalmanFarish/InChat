import test from "node:test";
import assert from "node:assert/strict";
import { runMailboxRoundTrip } from "../scripts/smoke-deployed-mailbox.mjs";

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function makeFixture() {
  const accounts = {
    "sender@test.invalid": {
      uid: "sender_uid",
      token: "sender-id-token-for-tests",
      deviceId: "sender-device",
    },
    "recipient@test.invalid": {
      uid: "recipient_uid",
      token: "recipient-id-token-for-tests",
      deviceId: "recipient-device",
    },
  };
  const byToken = new Map(Object.values(accounts).map((value) => [value.token, value]));
  const deliveryState = { value: null };
  const acknowledgements = new Map();
  const calls = [];

  const fetchImpl = async (input, options = {}) => {
    const url = new URL(String(input));
    const method = options.method || "GET";
    const body = options.body ? JSON.parse(options.body) : null;

    if (url.hostname === "identitytoolkit.googleapis.com") {
      const account = accounts[body.email];
      assert.ok(account, "unexpected Firebase test account");
      return jsonResponse({
        localId: account.uid,
        idToken: account.token,
        refreshToken: "not-used",
      });
    }

    if (url.hostname === "database.test") {
      const uid = url.pathname.split("/")[2].replace(/\.json$/, "");
      const account = byToken.get(options.headers?.Authorization?.slice(7));
      assert.equal(account?.uid, uid, "Firebase device lookup used the wrong account token");
      return jsonResponse({
        [account.deviceId]: {
          schemaVersion: 1,
          deviceId: account.deviceId,
          ed25519IdentityKey: "published-public-key",
        },
      });
    }

    assert.equal(url.hostname, "worker.test");
    const account = byToken.get(options.headers?.Authorization?.slice(7));
    assert.ok(account, "Worker request did not use an authenticated Firebase ID token");
    calls.push({ method, path: url.pathname, uid: account.uid });

    if (url.pathname === "/v1/olm/mailbox/enqueue" && method === "POST") {
      assert.equal(account.uid, "sender_uid");
      assert.equal(body.senderDeviceId, "sender-device");
      assert.equal(body.recipientUid, "recipient_uid");
      assert.equal(body.recipientDeviceId, "recipient-device");
      if (!deliveryState.value) {
        deliveryState.value = {
          ...body,
          status: "pending",
          expiresAt: Date.now() + 60 * 60 * 1000,
        };
        return jsonResponse({
          accepted: true,
          duplicate: false,
          replayedAfterAck: false,
          expiresAt: deliveryState.value.expiresAt,
        });
      }

      assert.equal(body.deliveryId, deliveryState.value.deliveryId);
      if (deliveryState.value.status === "acked") {
        deliveryState.value.status = "pending";
        deliveryState.value.expiresAt = Date.now() + 60 * 60 * 1000;
        return jsonResponse({
          accepted: true,
          duplicate: true,
          replayedAfterAck: true,
          expiresAt: deliveryState.value.expiresAt,
        });
      }
      return jsonResponse({
        accepted: true,
        duplicate: true,
        replayedAfterAck: false,
        expiresAt: deliveryState.value.expiresAt,
      });
    }

    if (url.pathname === "/v1/olm/mailbox/inbox" && method === "GET") {
      assert.equal(account.uid, "recipient_uid");
      assert.equal(url.searchParams.get("deviceId"), "recipient-device");
      const delivery = deliveryState.value;
      const items = delivery && ["pending", "acknowledged"].includes(delivery.status) ?
        [{
          deliveryId: delivery.deliveryId,
          senderUid: "sender_uid",
          senderDeviceId: "sender-device",
          messageId: delivery.messageId,
          envelope: delivery.envelope,
          conversationType: "direct",
          conversationId: "recipient_uid_sender_uid",
          conversationPeerUserId: "recipient_uid",
        }] : [];
      return jsonResponse({ items, nextCursor: null });
    }

    const inboxDelete = url.pathname.match(/^\/v1\/olm\/mailbox\/inbox\/([a-f0-9]{64})$/);
    if (inboxDelete && method === "DELETE") {
      assert.equal(account.uid, "recipient_uid");
      assert.equal(inboxDelete[1], deliveryState.value.deliveryId);
      if (deliveryState.value.status === "acknowledged") {
        deliveryState.value.status = "acked";
      }
      return new Response(null, { status: 204 });
    }

    if (url.pathname === "/v1/olm/mailbox/acknowledge" && method === "POST") {
      assert.equal(account.uid, "recipient_uid");
      if (deliveryState.value.status === "acked") {
        return jsonResponse({ error: "Mailbox delivery is not acknowledgeable." }, 409);
      }
      deliveryState.value.status = "acknowledged";
      acknowledgements.set(deliveryState.value.deliveryId, {
        deliveryId: deliveryState.value.deliveryId,
        recipientUid: "recipient_uid",
        recipientDeviceId: "recipient-device",
        messageId: deliveryState.value.messageId,
      });
      return jsonResponse({ acknowledged: true, duplicate: false });
    }

    if (url.pathname === "/v1/olm/mailbox/acks" && method === "GET") {
      assert.equal(account.uid, "sender_uid");
      assert.equal(url.searchParams.get("deviceId"), "sender-device");
      return jsonResponse({ items: [...acknowledgements.values()], nextCursor: null });
    }

    const ackDelete = url.pathname.match(/^\/v1\/olm\/mailbox\/acks\/([a-f0-9]{64})$/);
    if (ackDelete && method === "DELETE") {
      assert.equal(account.uid, "sender_uid");
      acknowledgements.delete(ackDelete[1]);
      return new Response(null, { status: 204 });
    }

    return jsonResponse({ error: "Unexpected test route " + method + " " + url.pathname }, 404);
  };

  const config = {
    workerUrl: "https://worker.test",
    databaseUrl: "https://database.test",
    firebaseApiKey: "test-web-api-key",
    confirmRecipientDeviceOffline: true,
    sender: {
      email: "sender@test.invalid",
      password: "test-password",
      deviceId: "sender-device",
    },
    recipient: {
      email: "recipient@test.invalid",
      password: "test-password",
      deviceId: "recipient-device",
    },
  };

  return { config, fetchImpl, calls, deliveryState, acknowledgements };
}

test("deployed mailbox harness checks upload, retrieval, ACK ordering, retry recovery and cleanup", async () => {
  const fixture = makeFixture();
  const result = await runMailboxRoundTrip(fixture.config, fixture.fetchImpl);

  assert.match(result.deliveryId, /^[a-f0-9]{64}$/);
  assert.equal(result.senderUid, "sender_uid");
  assert.equal(result.recipientUid, "recipient_uid");
  assert.equal(result.senderDeviceId, "sender-device");
  assert.equal(result.recipientDeviceId, "recipient-device");
  assert.equal(result.checks.length, 7);
  assert.equal(fixture.deliveryState.value.status, "acked");
  assert.equal(fixture.acknowledgements.size, 0);

  const enqueueCalls = fixture.calls.filter((call) =>
    call.path === "/v1/olm/mailbox/enqueue" && call.method === "POST");
  assert.equal(enqueueCalls.length, 3, "expected initial upload, duplicate retry and lost-ACK replay");
});

test("harness refuses to send synthetic ciphertext to a recipient device not confirmed offline", async () => {
  const fixture = makeFixture();
  fixture.config.confirmRecipientDeviceOffline = false;
  await assert.rejects(
    runMailboxRoundTrip(fixture.config, fixture.fetchImpl),
    /confirmed offline/,
  );
  assert.equal(fixture.calls.length, 0);
});
