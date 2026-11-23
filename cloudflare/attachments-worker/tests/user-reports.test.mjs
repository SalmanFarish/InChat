import test from "node:test";
import assert from "node:assert/strict";
import {
  claimUserReportCooldown,
  submitUserReport,
} from "../src/user-reports.js";

const env = { FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com" };
const baseBody = {
  reportedUserId: "bob",
  reason: "Abusive messages",
};

function cooldownStore(initial = null) {
  let value = initial;
  let version = 1;
  return {
    fetch: async (_url, options = {}) => {
      if (!options.method || options.method === "GET") {
        return new Response(JSON.stringify(value), {
          status: 200,
          headers: { ETag: '"v' + version + '"' },
        });
      }
      if (options.headers?.["If-Match"] !== '"v' + version + '"') {
        return new Response("precondition failed", { status: 412 });
      }
      value = JSON.parse(options.body);
      version += 1;
      return new Response("null", { status: 200 });
    },
    get value() {
      return value;
    },
  };
}

function databaseFixture({
  target = { uid: "bob", username: "Bob" },
  usernameOwner = "bob",
} = {}) {
  const writes = [];
  const adminDatabaseRequest = async (_env, token, path, method = "GET", body) => {
    assert.equal(token, "admin-token");
    if (method === "GET" && path === "users/bob") return target;
    if (method === "GET" && path === "usernames/bob") return usernameOwner;
    if (method === "POST" && path === "reports") {
      writes.push(structuredClone(body));
      return { name: "-report-key" };
    }
    throw new Error("Unexpected database request: " + method + " " + path);
  };
  return { writes, adminDatabaseRequest };
}

test("submits a report only after verifying target UID and canonical username", async () => {
  const database = databaseFixture();
  const cooldown = cooldownStore();
  const result = await submitUserReport({
    uid: "alice",
    body: baseBody,
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldown.fetch,
    now: 1_000_000,
  });

  assert.equal(result.status, 201);
  assert.equal(result.payload.success, true);
  assert.equal(result.payload.reportId, "-report-key");
  assert.equal(database.writes.length, 1);
  assert.deepEqual(database.writes[0], {
    reporterId: "alice",
    reportedUserId: "bob",
    reportedUsername: "Bob",
    reason: "Abusive messages",
    timestamp: { ".sv": "timestamp" },
  });
});

test("does not accept a client-supplied reporter ID, timestamp, username, or unknown fields", async () => {
  const database = databaseFixture();
  const result = await submitUserReport({
    uid: "alice",
    body: { ...baseBody, reporterId: "mallory", timestamp: 1 },
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldownStore().fetch,
    now: 1_000_000,
  });

  assert.equal(result.status, 400);
  assert.equal(database.writes.length, 0);
});

test("does not accept a client-selected target username", async () => {
  const database = databaseFixture();
  const result = await submitUserReport({
    uid: "alice",
    body: { ...baseBody, reportedUsername: "Mallory" },
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldownStore().fetch,
    now: 1_000_000,
  });

  assert.equal(result.status, 400);
  assert.equal(database.writes.length, 0);
});

test("rejects self-reports before reading or writing report data", async () => {
  const database = databaseFixture();
  const result = await submitUserReport({
    uid: "bob",
    body: baseBody,
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldownStore().fetch,
    now: 1_000_000,
  });

  assert.equal(result.status, 400);
  assert.equal(database.writes.length, 0);
});

test("rejects nonexistent or mismatched target profiles", async () => {
  for (const target of [null, { uid: "mallory", username: "Bob" }, { uid: "bob", username: "Carol" }]) {
    const database = databaseFixture({ target });
    const result = await submitUserReport({
      uid: "alice",
      body: baseBody,
      env,
      adminToken: "admin-token",
      adminDatabaseRequest: database.adminDatabaseRequest,
      fetchImpl: cooldownStore().fetch,
      now: 1_000_000,
    });
    assert.equal(result.status, 404);
    assert.equal(database.writes.length, 0);
  }
});

test("rejects username indexes that do not point to the reported account", async () => {
  const database = databaseFixture({ usernameOwner: "mallory" });
  const result = await submitUserReport({
    uid: "alice",
    body: baseBody,
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldownStore().fetch,
    now: 1_000_000,
  });

  assert.equal(result.status, 404);
  assert.equal(database.writes.length, 0);
});

test("rejects empty, oversized and malformed report details", async () => {
  const database = databaseFixture();
  for (const body of [
    { ...baseBody, reason: "   " },
    { ...baseBody, reason: "x".repeat(501) },
    { ...baseBody, reportedUsername: "bad name" },
    { ...baseBody, reportedUserId: "../bob" },
  ]) {
    const result = await submitUserReport({
      uid: "alice",
      body,
      env,
      adminToken: "admin-token",
      adminDatabaseRequest: database.adminDatabaseRequest,
      fetchImpl: cooldownStore().fetch,
      now: 1_000_000,
    });
    assert.equal(result.status, 400);
  }
  assert.equal(database.writes.length, 0);
});

test("permits one report per account per cooldown window", async () => {
  const database = databaseFixture();
  const cooldown = cooldownStore();
  const first = await submitUserReport({
    uid: "alice",
    body: baseBody,
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldown.fetch,
    now: 1_000_000,
  });
  const second = await submitUserReport({
    uid: "alice",
    body: baseBody,
    env,
    adminToken: "admin-token",
    adminDatabaseRequest: database.adminDatabaseRequest,
    fetchImpl: cooldown.fetch,
    now: 1_000_001,
  });

  assert.equal(first.status, 201);
  assert.equal(second.status, 429);
  assert.equal(database.writes.length, 1);
});

test("report cooldown is enforced with a compare-and-swap write", async () => {
  const cooldown = cooldownStore();
  const first = await claimUserReportCooldown(
    env,
    "admin-token",
    "alice",
    cooldown.fetch,
    1_000_000,
  );
  const blocked = await claimUserReportCooldown(
    env,
    "admin-token",
    "alice",
    cooldown.fetch,
    1_000_001,
  );

  assert.equal(first, true);
  assert.equal(blocked, false);
  assert.deepEqual(cooldown.value, { lastReportAt: 1_000_000 });
});

test("report cooldown rejects malformed server-owned state", async () => {
  const cooldown = cooldownStore({ lastReportAt: "not-a-timestamp" });
  await assert.rejects(
    claimUserReportCooldown(
      env,
      "admin-token",
      "alice",
      cooldown.fetch,
      1_000_000,
    ),
    /cooldown state is invalid/,
  );
});
