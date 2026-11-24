import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const MIN_RETENTION_MS = 60 * 60 * 1000;
const MAX_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

export function validateWorkerConfig(config) {
  if (!config || typeof config !== "object") {
    throw new Error("wrangler.jsonc must contain a Worker configuration object.");
  }
  if (config.name !== "icy-base-2bc4") {
    throw new Error("Refusing deployment: unexpected Worker name.");
  }
  if (config.main !== "src/index.js") {
    throw new Error("Refusing deployment: unexpected Worker entry point.");
  }

  const bindings = config.durable_objects?.bindings;
  const mailbox = Array.isArray(bindings)
    ? bindings.find((binding) => binding?.name === "OLM_MAILBOX")
    : null;
  if (!mailbox || mailbox.class_name !== "OlmDeviceMailboxDurableObject") {
    throw new Error("Refusing deployment: SQLite mailbox Durable Object binding is missing.");
  }

  const migrations = Array.isArray(config.migrations) ? config.migrations : [];
  const hasSqliteMigration = migrations.some((migration) =>
    Array.isArray(migration.new_sqlite_classes) &&
    migration.new_sqlite_classes.includes("OlmDeviceMailboxDurableObject")
  );
  if (!hasSqliteMigration) {
    throw new Error("Refusing deployment: mailbox SQLite class migration is missing.");
  }

  const vars = config.vars || {};
  const retentionText = vars.OLM_MAILBOX_RETENTION_MS;
  if (typeof retentionText !== "string" || !/^\d+$/.test(retentionText)) {
    throw new Error("Refusing deployment: OLM_MAILBOX_RETENTION_MS must be an explicit integer.");
  }
  const retention = Number(retentionText);
  if (!Number.isSafeInteger(retention) ||
      retention < MIN_RETENTION_MS ||
      retention > MAX_RETENTION_MS) {
    throw new Error("Refusing deployment: mailbox retention must be between 1 hour and 30 days.");
  }

  for (const forbidden of ["kv_namespaces", "r2_buckets", "d1_databases", "queues", "containers"]) {
    if (Array.isArray(config[forbidden]) && config[forbidden].length > 0) {
      throw new Error("Refusing deployment: unexpected paid-capable binding " + forbidden + ".");
    }
  }

  return {
    worker: config.name,
    mailboxStorage: "SQLite Durable Objects",
    retentionMillis: retention,
    retentionDays: retention / (24 * 60 * 60 * 1000),
  };
}

export function readAndValidateWorkerConfig(configPath = resolve(ROOT, "wrangler.jsonc")) {
  const config = JSON.parse(readFileSync(configPath, "utf8"));
  return validateWorkerConfig(config);
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    const result = readAndValidateWorkerConfig();
    console.log("Worker deployment preflight passed.");
    console.log("Worker: " + result.worker);
    console.log("Mailbox storage: " + result.mailboxStorage);
    console.log("Ciphertext retention: " + result.retentionDays + " days");
    console.log("Plan guard: SQLite-only; no KV, R2, D1, Queues, or Containers bindings.");
    console.log("This check does not deploy or verify the Cloudflare account billing plan.");
  } catch (error) {
    console.error("Worker deployment preflight failed: " + error.message);
    process.exitCode = 1;
  }
}
