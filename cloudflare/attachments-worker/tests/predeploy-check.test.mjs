import test from "node:test";
import assert from "node:assert/strict";
import { validateWorkerConfig } from "../scripts/predeploy-check.mjs";

const validConfig = () => ({
  name: "icy-base-2bc4",
  main: "src/index.js",
  vars: { OLM_MAILBOX_RETENTION_MS: String(7 * 24 * 60 * 60 * 1000) },
  durable_objects: {
    bindings: [{ name: "OLM_MAILBOX", class_name: "OlmDeviceMailboxDurableObject" }],
  },
  migrations: [{
    tag: "v1",
    new_sqlite_classes: ["OlmDeviceMailboxDurableObject"],
  }],
});

test("accepts the SQLite mailbox with an explicit seven-day retention policy", () => {
  const result = validateWorkerConfig(validConfig());
  assert.equal(result.mailboxStorage, "SQLite Durable Objects");
  assert.equal(result.retentionDays, 7);
});

test("rejects missing or malformed retention instead of deploying a disabled mailbox", () => {
  const config = validConfig();
  delete config.vars.OLM_MAILBOX_RETENTION_MS;
  assert.throws(() => validateWorkerConfig(config), /explicit integer/);
});

test("rejects retention outside the documented one-hour to thirty-day bounds", () => {
  const config = validConfig();
  config.vars.OLM_MAILBOX_RETENTION_MS = String(31 * 24 * 60 * 60 * 1000);
  assert.throws(() => validateWorkerConfig(config), /between 1 hour and 30 days/);
});

test("rejects a missing SQLite class migration", () => {
  const config = validConfig();
  config.migrations = [{ tag: "v1", new_classes: ["OlmDeviceMailboxDurableObject"] }];
  assert.throws(() => validateWorkerConfig(config), /SQLite class migration is missing/);
});

test("rejects alternate storage bindings that could create unplanned costs", () => {
  const config = validConfig();
  config.r2_buckets = [{ binding: "BUCKET", bucket_name: "unexpected" }];
  assert.throws(() => validateWorkerConfig(config), /unexpected paid-capable binding r2_buckets/);
});
