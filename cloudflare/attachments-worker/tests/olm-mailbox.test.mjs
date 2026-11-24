import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { OlmDeviceMailboxDurableObject, processOlmMailboxRequest } from "../src/olm-mailbox.js";

const RETENTION = 60 * 60 * 1000;
const envelope = JSON.stringify({
  encryptionVersion: 1,
  algorithm: "olm",
  senderDeviceId: "alice-device",
  recipientDeviceId: "bob-device",
  sessionId: "session-1",
  ciphertext: "AQID",
});
const deliveryId = "a".repeat(64);

class TestStorage {
  constructor() {
    this.database = new DatabaseSync(":memory:");
    this.alarm = null;
    this.sql = {
      exec: (query, ...bindings) => {
        const normalized = query.trim();
        if (/^(CREATE|DROP|ALTER|PRAGMA)\\b/i.test(normalized)) {
          this.database.exec(query);
          return { toArray: () => [] };
        }
        const statement = this.database.prepare(query);
        if (/^(SELECT|WITH)\\b/i.test(normalized)) {
          return { toArray: () => statement.all(...bindings) };
        }
        statement.run(...bindings);
        return { toArray: () => [] };
      },
    };
  }

  transactionSync(callback) {
    this.database.exec("BEGIN IMMEDIATE");
    try {
      const result = callback();
      this.database.exec("COMMIT");
      return result;
    } catch (error) {
      this.database.exec("ROLLBACK");
      throw error;
    }
  }

  async getAlarm() { return this.alarm; }
  async setAlarm(value) { this.alarm = value instanceof Date ? value.getTime() : value; }
  async deleteAlarm() { this.alarm = null; }
}

class TestMailboxNamespace {
  constructor(env) {
    this.env = env;
    this.objects = new Map();
  }
  idFromName(name) { return { name }; }
  get(id) {
    let object = this.objects.get(id.name);
    if (!object) {
      object = new OlmDeviceMailboxDurableObject(
        { id, storage: new TestStorage() },
        this.env,
      );
      this.objects.set(id.name, object);
    }
    return { fetch: (request) => object.fetch(request), object };
  }
}

function directDelivery(overrides = {}) {
  return {
    acceptedAt: Date.now(),
    retentionMillis: RETENTION,
    deliveryId,
    messageId: "message-1",
    senderUid: "alice",
    senderDeviceId: "alice-device",
    recipientUid: "bob",
    recipientDeviceId: "bob-device",
    envelope,
    conversationType: "direct",
    conversationId: "alice_bob",
    conversationPeerUserId: "bob",
    ...overrides,
  };
}

function req(path, method = "GET", body = undefined) {
  return new Request("https://mailbox.test" + path, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function json(response) { return response.json(); }

function makeEnvironment() {
  const env = { OLM_MAILBOX_RETENTION_MS: String(RETENTION) };
  const namespace = new TestMailboxNamespace(env);
  env.OLM_MAILBOX = namespace;
  return { env, namespace };
}

function published(deviceId) {
  return { schemaVersion: 1, deviceId, ed25519IdentityKey: "public-identity-key" };
}

function workerFixture(overrides = {}) {
  const { env, namespace } = makeEnvironment();
  const database = new Map([
    ["chats/alice_bob", { type: "direct", participantA: "alice", participantB: "bob" }],
    ["deviceKeys/alice/alice-device", published("alice-device")],
    ["deviceKeys/bob/bob-device", published("bob-device")],
  ]);
  for (const [key, value] of Object.entries(overrides)) database.set(key, value);
  const adminDatabaseRequest = async (_env, token, path) => {
    assert.equal(token, "admin-token");
    return structuredClone(database.get(path) ?? null);
  };
  const dispatch = (path, method = "GET", body = undefined, uid = "alice", now = Date.now()) =>
    processOlmMailboxRequest({
      request: req(path, method, body),
      env,
      uid,
      adminToken: "admin-token",
      adminDatabaseRequest,
      now,
    });
  return { env, namespace, database, dispatch };
}

function apiEnqueueBody(overrides = {}) {
  return {
    deliveryId,
    messageId: "message-1",
    recipientUid: "bob",
    recipientDeviceId: "bob-device",
    senderDeviceId: "alice-device",
    envelope,
    conversationType: "direct",
    conversationId: "alice_bob",
    conversationPeerUserId: "bob",
    ...overrides,
  };
}

test("authenticated direct enqueue stores ciphertext in the recipient-device mailbox", async () => {
  const state = workerFixture();
  const response = await state.dispatch("/v1/olm/mailbox/enqueue", "POST", apiEnqueueBody());
  assert.equal(response.status, 200);
  assert.equal((await json(response)).accepted, true);

  const inbox = await state.dispatch("/v1/olm/mailbox/inbox?deviceId=bob-device", "GET", undefined, "bob");
  assert.equal(inbox.status, 200);
  const page = await json(inbox);
  assert.equal(page.items.length, 1);
  assert.equal(page.items[0].envelope, envelope);
  assert.equal(page.items[0].senderUid, "alice");
  assert.equal(page.items[0].conversationId, "alice_bob");
});

test("exact enqueue retries preserve the original server expiry", async () => {
  const state = workerFixture();
  const acceptedAt = Date.now();
  const body = apiEnqueueBody();
  const first = await state.dispatch("/v1/olm/mailbox/enqueue", "POST", body, "alice", acceptedAt);
  const second = await state.dispatch("/v1/olm/mailbox/enqueue", "POST", body, "alice", acceptedAt + 5000);
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  const firstPayload = await json(first);
  const secondPayload = await json(second);
  assert.equal(secondPayload.duplicate, true);
  assert.equal(secondPayload.expiresAt, firstPayload.expiresAt);
});

test("delivery ID reuse with changed ciphertext is rejected", async () => {
  const state = workerFixture();
  const body = apiEnqueueBody();
  assert.equal((await state.dispatch("/v1/olm/mailbox/enqueue", "POST", body)).status, 200);
  const changedEnvelope = JSON.stringify({ ...JSON.parse(envelope), sessionId: "changed" });
  const conflict = await state.dispatch("/v1/olm/mailbox/enqueue", "POST", {
    ...body,
    envelope: changedEnvelope,
  });
  assert.equal(conflict.status, 409);
});

test("mailbox rejects blocked conversations and unpublished recipient devices", async () => {
  const blocked = workerFixture({ ["blockedUsers/alice/bob"]: { uid: "bob" } });
  assert.equal(
    (await blocked.dispatch("/v1/olm/mailbox/enqueue", "POST", apiEnqueueBody())).status,
    403,
  );

  const revoked = workerFixture();
  revoked.database.delete("deviceKeys/bob/bob-device");
  assert.equal(
    (await revoked.dispatch("/v1/olm/mailbox/enqueue", "POST", apiEnqueueBody())).status,
    404,
  );
});

test("group enqueue requires an exact current membership snapshot and epoch", async () => {
  const groupId = "group-123";
  const chat = {
    type: "group",
    members: { alice: "admin", bob: "member" },
    cryptoMembership: { epoch: 4, members: { alice: "admin", bob: "member" } },
  };
  const state = workerFixture({ ["chats/" + groupId]: chat });
  const groupEnvelope = JSON.stringify({ ...JSON.parse(envelope) });
  const body = {
    deliveryId,
    messageId: "group-message-1",
    recipientUid: "bob",
    recipientDeviceId: "bob-device",
    senderDeviceId: "alice-device",
    envelope: groupEnvelope,
    conversationType: "group",
    conversationId: groupId,
    groupId,
    membershipEpoch: 4,
  };
  assert.equal((await state.dispatch("/v1/olm/mailbox/enqueue", "POST", body)).status, 200);
  assert.equal((await state.dispatch("/v1/olm/mailbox/enqueue", "POST", {
    ...body,
    deliveryId: "b".repeat(64),
    membershipEpoch: 3,
  })).status, 409);
});

test("ACK is stored in the sender mailbox before recipient ciphertext is removed", async () => {
  const state = workerFixture();
  assert.equal((await state.dispatch("/v1/olm/mailbox/enqueue", "POST", apiEnqueueBody())).status, 200);

  const acknowledge = await state.dispatch(
    "/v1/olm/mailbox/acknowledge?deviceId=bob-device",
    "POST",
    { deliveryId },
    "bob",
  );
  assert.equal(acknowledge.status, 200);
  assert.equal((await json(acknowledge)).acknowledged, true);

  const recipientMailbox = state.namespace.get(state.namespace.idFromName("bob:bob-device"));
  const staged = await json(await recipientMailbox.fetch(req("/inbox")));
  assert.equal(staged.items.length, 1);

  const senderAcks = await state.dispatch(
    "/v1/olm/mailbox/acks?deviceId=alice-device",
    "GET",
    undefined,
    "alice",
  );
  assert.equal(senderAcks.status, 200);
  assert.equal((await json(senderAcks)).items[0].deliveryId, deliveryId);

  const removed = await state.dispatch(
    "/v1/olm/mailbox/inbox/" + deliveryId + "?deviceId=bob-device",
    "DELETE",
    { acceptedAt: Date.now(), retentionMillis: RETENTION },
    "bob",
  );
  assert.equal(removed.status, 204);
  const inboxAfterRemoval = await json(await recipientMailbox.fetch(req("/inbox")));
  assert.equal(inboxAfterRemoval.items.length, 0);

  const removedAck = await state.dispatch(
    "/v1/olm/mailbox/acks/" + deliveryId + "?deviceId=alice-device",
    "DELETE",
    undefined,
    "alice",
  );
  assert.equal(removedAck.status, 204);
});

test("mailbox pagination uses bounded stable cursors", async () => {
  const { namespace } = makeEnvironment();
  const mailbox = namespace.get(namespace.idFromName("bob:bob-device"));
  for (let i = 1; i <= 3; i += 1) {
    const body = directDelivery({
      deliveryId: i.toString(16).padStart(64, "0"),
      messageId: "message-" + i,
    });
    assert.equal((await mailbox.fetch(req("/enqueue", "POST", body))).status, 200);
  }
  const first = await json(await mailbox.fetch(req("/inbox?limit=2")));
  assert.equal(first.items.length, 2);
  assert.ok(first.nextCursor);
  const second = await json(await mailbox.fetch(req("/inbox?limit=2&cursor=" + first.nextCursor)));
  assert.equal(second.items.length, 1);
  assert.equal(second.nextCursor, null);
});

test("per-device queue cap rejects new ciphertext without evicting existing deliveries", async () => {
  const { namespace } = makeEnvironment();
  const mailbox = namespace.get(namespace.idFromName("bob:bob-device"));
  for (let i = 1; i <= 200; i += 1) {
    const body = directDelivery({
      deliveryId: i.toString(16).padStart(64, "0"),
      messageId: "message-" + i,
    });
    assert.equal((await mailbox.fetch(req("/enqueue", "POST", body))).status, 200);
  }
  const overflow = directDelivery({
    deliveryId: "f".repeat(64),
    messageId: "overflow-message",
  });
  const rejected = await mailbox.fetch(req("/enqueue", "POST", overflow));
  assert.equal(rejected.status, 503);
  assert.equal((await json(rejected)).retryable, true);
  assert.equal((await json(await mailbox.fetch(req("/inbox?limit=50")))).items.length, 50);
});

test("expiry removes ciphertext, keeps a temporary idempotency tombstone, then cleans it", async () => {
  const originalNow = Date.now;
  let fakeNow = 1_000_000;
  Date.now = () => fakeNow;
  try {
    const { namespace } = makeEnvironment();
    const mailbox = namespace.get(namespace.idFromName("bob:bob-device"));
    assert.equal((await mailbox.fetch(req("/enqueue", "POST", directDelivery()))).status, 200);

    fakeNow += RETENTION + 1;
    await mailbox.object.alarm();
    const tombstone = mailbox.object.sql.exec(
      "SELECT status,envelope,byte_size FROM deliveries WHERE delivery_id = ?",
      deliveryId,
    ).toArray()[0];
    assert.equal(tombstone.status, "expired");
    assert.equal(tombstone.envelope, null);
    assert.equal(tombstone.byte_size, 0);

    fakeNow += RETENTION + 1;
    await mailbox.object.alarm();
    assert.equal(mailbox.object.sql.exec(
      "SELECT delivery_id FROM deliveries WHERE delivery_id = ?",
      deliveryId,
    ).toArray().length, 0);
  } finally {
    Date.now = originalNow;
  }
});

test("mailbox routes fail closed until an explicit retention policy is configured", async () => {
  const state = workerFixture();
  delete state.env.OLM_MAILBOX_RETENTION_MS;
  const response = await state.dispatch(
    "/v1/olm/mailbox/inbox?deviceId=bob-device",
    "GET",
    undefined,
    "bob",
  );
  assert.equal(response.status, 503);
});
