const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const USERNAME_PATTERN = /^[A-Za-z0-9_.]{3,20}$/;
const REPORT_COOLDOWN_MILLIS = 60_000;

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function outcome(status, payload) {
  return { status, payload };
}

function reportRateLimitUrl(env, uid) {
  const base = env.FIREBASE_DATABASE_URL.replace(/\/$/, "");
  return base + "/reportRateLimits/" + encodeURIComponent(uid) + ".json";
}

/**
 * Claims a globally serialized per-account report cooldown in Realtime
 * Database. ETag compare-and-swap makes this limit shared across Worker
 * locations; a racing request receives 412 and is rejected.
 */
export async function claimUserReportCooldown(
  env,
  adminToken,
  uid,
  fetchImpl = fetch,
  now = Date.now(),
) {
  const url = reportRateLimitUrl(env, uid);
  const read = await fetchImpl(url, {
    headers: {
      Authorization: "Bearer " + adminToken,
      "X-Firebase-ETag": "true",
      Accept: "application/json",
    },
  });
  if (!read.ok) {
    throw new Error("Could not check the report submission cooldown.");
  }

  const current = await read.json();
  const etag = read.headers.get("ETag");
  if (!etag) {
    throw new Error("Firebase did not provide a report cooldown ETag.");
  }

  if (current !== null) {
    if (
      !isRecord(current) ||
      !Number.isSafeInteger(current.lastReportAt) ||
      current.lastReportAt < 0
    ) {
      throw new Error("The stored report cooldown state is invalid.");
    }
    if (now - current.lastReportAt < REPORT_COOLDOWN_MILLIS) {
      return false;
    }
  }

  const write = await fetchImpl(url, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify({ lastReportAt: now }),
  });

  if (write.status === 412) return false;
  if (!write.ok) {
    throw new Error("Could not claim the report submission cooldown.");
  }
  return true;
}

/**
 * Validates a report against authoritative account data and persists it using
 * Firebase Admin. The reporter identity and timestamp are never accepted
 * from the Android request body.
 */
export async function submitUserReport({
  uid,
  body,
  env,
  adminToken,
  adminDatabaseRequest,
  fetchImpl = fetch,
  now = Date.now(),
}) {
  if (!UID_PATTERN.test(uid || "")) {
    return outcome(401, { error: "Unauthorized." });
  }
  if (!isRecord(body)) {
    return outcome(400, { error: "Invalid report request." });
  }

  const fields = Object.keys(body).sort();
  const expectedFields = ["reason", "reportedUserId", "reportedUsername"];
  if (
    fields.length !== expectedFields.length ||
    fields.some((field, index) => field !== expectedFields[index])
  ) {
    return outcome(400, { error: "Report request contains missing or unknown fields." });
  }

  const reportedUserId = body.reportedUserId;
  const suppliedUsername = typeof body.reportedUsername === "string" ?
    body.reportedUsername.trim() :
    "";
  const normalizedUsername = suppliedUsername.toLowerCase();
  const reason = typeof body.reason === "string" ? body.reason.trim() : "";

  if (
    typeof reportedUserId !== "string" ||
    !UID_PATTERN.test(reportedUserId) ||
    !USERNAME_PATTERN.test(suppliedUsername) ||
    reason.length < 1 ||
    reason.length > 500
  ) {
    return outcome(400, { error: "Report details are invalid." });
  }
  if (reportedUserId === uid) {
    return outcome(400, { error: "You cannot report yourself." });
  }

  const targetProfile = await adminDatabaseRequest(
    env,
    adminToken,
    "users/" + reportedUserId,
  );
  if (
    !isRecord(targetProfile) ||
    targetProfile.uid !== reportedUserId ||
    typeof targetProfile.username !== "string" ||
    targetProfile.username.trim().toLowerCase() !== normalizedUsername
  ) {
    return outcome(404, { error: "The reported account could not be verified." });
  }

  const usernameOwner = await adminDatabaseRequest(
    env,
    adminToken,
    "usernames/" + normalizedUsername,
  );
  if (usernameOwner !== reportedUserId) {
    return outcome(404, { error: "The reported account could not be verified." });
  }

  const cooldownClaimed = await claimUserReportCooldown(
    env,
    adminToken,
    uid,
    fetchImpl,
    now,
  );
  if (!cooldownClaimed) {
    return outcome(429, {
      error: "A report was recently submitted from this account. Please try again later.",
      retryable: true,
    });
  }

  const saved = await adminDatabaseRequest(
    env,
    adminToken,
    "reports",
    "POST",
    {
      reporterId: uid,
      reportedUserId: reportedUserId,
      reportedUsername: targetProfile.username.trim(),
      reason: reason,
      timestamp: { ".sv": "timestamp" },
    },
  );
  if (!isRecord(saved) || typeof saved.name !== "string" || !saved.name) {
    throw new Error("Firebase did not confirm the report record.");
  }

  return outcome(201, { success: true, reportId: saved.name });
}
