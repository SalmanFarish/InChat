const baseUrl = (process.env.INCHAT_WORKER_URL || "https://icy-base-2bc4.s91670002.workers.dev").replace(/\/+$/, "");
if (!/^https:\/\//i.test(baseUrl)) {
if (!/^https:\\/\\//i.test(baseUrl)) {
  throw new Error("INCHAT_WORKER_URL must be an HTTPS URL.");
}

const response = await fetch(baseUrl + "/health", {
  method: "GET",
  redirect: "error",
  headers: { Accept: "application/json" },
  signal: AbortSignal.timeout(10_000),
});
const body = await response.json().catch(() => null);
if (!response.ok || body?.status !== "ok" ||
    body?.service !== "inchat-worker" ||
    body?.encryptedMailbox !== "ready") {
  throw new Error(
    "Worker health check failed (" + response.status + "). " +
    "Confirm the deployment and SQLite mailbox migration before enabling Android routing.",
  );
}

console.log("Deployed Worker health check passed.");
console.log("Service: " + body.service);
console.log("Encrypted mailbox: " + body.encryptedMailbox);
console.log("Base URL: " + baseUrl);
