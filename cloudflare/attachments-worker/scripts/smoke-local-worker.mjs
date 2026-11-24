import assert from "node:assert/strict";
import worker from "../src/index.js";

const response = await worker.fetch(
  new Request("https://worker-smoke.test/health", { method: "GET" }),
  {
    OLM_MAILBOX: { idFromName: () => ({}) },
    OLM_MAILBOX_RETENTION_MS: "604800000",
  },
);
const body = await response.json();
assert.equal(response.status, 200, "Worker health route should be ready");
assert.deepEqual(body, {
  status: "ok",
  service: "inchat-worker",
  encryptedMailbox: "ready",
});
console.log("Local Worker smoke check passed (health route + mailbox configuration).");
