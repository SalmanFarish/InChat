import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

function request(path = "/health") {
  return new Request("https://worker.test" + path, { method: "GET" });
}

test("health endpoint reports readiness without exposing configuration", async () => {
  const response = await worker.fetch(request(), {
    OLM_MAILBOX: {},
    OLM_MAILBOX_RETENTION_MS: "604800000",
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    status: "ok",
    service: "inchat-worker",
    encryptedMailbox: "ready",
  });
});

test("health endpoint fails closed when the mailbox binding is absent", async () => {
  const response = await worker.fetch(request(), {
    OLM_MAILBOX_RETENTION_MS: "604800000",
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    status: "not_ready",
    service: "inchat-worker",
    encryptedMailbox: "not_ready",
  });
});

test("health endpoint fails closed for invalid mailbox retention", async () => {
  const response = await worker.fetch(request(), {
    OLM_MAILBOX: {},
    OLM_MAILBOX_RETENTION_MS: "0",
  });
  assert.equal(response.status, 503);
});

test("health endpoint does not answer on non-GET requests", async () => {
  const response = await worker.fetch(new Request("https://worker.test/health", {
    method: "POST",
  }), {
    OLM_MAILBOX: {},
    OLM_MAILBOX_RETENTION_MS: "604800000",
  });
  assert.equal(response.status, 401);
});
