import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { cert, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getDatabase } from "firebase-admin/database";
import { getStorage } from "firebase-admin/storage";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const execute = process.argv.includes("--execute");
const ROOT_DATA_PATHS = [
  "users",
  "usernames",
  "privateUsers",
  "publicUsernames",
  "chats",
  "groupMemberships",
  "groupReads",
  "userChats",
  "presence",
  "typing",
  "blockedUsers",
  "reports",
];

async function readJson(filePath, required = false) {
  try {
    return JSON.parse(await readFile(filePath, "utf8"));
  } catch (error) {
    if (!required && error?.code === "ENOENT") return null;
    throw error;
  }
}

async function listAllUsers(auth) {
  const users = [];
  let pageToken;

  do {
    const page = await auth.listUsers(1000, pageToken);
    users.push(...page.users);
    pageToken = page.pageToken;
  } while (pageToken);

  return users;
}

let firebaseApp;

async function main() {
  const firebaserc = await readJson(resolve(repositoryRoot, ".firebaserc"), true);
  const projectId = firebaserc?.projects?.default;
  if (!projectId) {
    throw new Error("Could not read the default Firebase project from .firebaserc.");
  }

  const googleServices = await readJson(
    resolve(repositoryRoot, "app/google-services.json"),
  );
  const configuredProjectId = googleServices?.project_info?.project_id;
  if (configuredProjectId && configuredProjectId !== projectId) {
    throw new Error(
      "Project mismatch: .firebaserc is " + projectId + ", but google-services.json is " + configuredProjectId + ".",
    );
  }

  const serviceAccountPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!serviceAccountPath) {
    throw new Error(
      "Set GOOGLE_APPLICATION_CREDENTIALS to the path of a Firebase service-account JSON file stored outside this repository.",
    );
  }

  const serviceAccount = await readJson(resolve(serviceAccountPath), true);
  if (serviceAccount?.project_id !== projectId) {
    throw new Error(
      "Service-account project mismatch. Expected " + projectId + "; received " + (serviceAccount?.project_id ?? "no project_id") + ".",
    );
  }

  const databaseURL = (
    process.env.FIREBASE_DATABASE_URL ||
    googleServices?.project_info?.firebase_url ||
    ""
  ).trim();
  if (!databaseURL) {
    throw new Error(
      "Set FIREBASE_DATABASE_URL to the Realtime Database URL from Firebase Console, or add app/google-services.json locally.",
    );
  }

  let databaseHost;
  try {
    databaseHost = new URL(databaseURL).hostname.toLowerCase();
  } catch {
    throw new Error("FIREBASE_DATABASE_URL is not a valid URL.");
  }
  if (!databaseURL.startsWith("https://") || !databaseHost.includes(projectId.toLowerCase())) {
    throw new Error(
      "Database URL host must be HTTPS and include the repository project ID (" + projectId + "). Received host: " + databaseHost + ".",
    );
  }

  const storageBucket = (
    process.env.FIREBASE_STORAGE_BUCKET ||
    googleServices?.project_info?.storage_bucket ||
    ""
  ).trim();
  if (storageBucket && !storageBucket.toLowerCase().includes(projectId.toLowerCase())) {
    throw new Error(
      "Storage bucket name must include the repository project ID (" + projectId + "). Received: " + storageBucket + ".",
    );
  }

  const confirmationFlag = "--confirm-project=" + projectId;
  if (execute && !process.argv.includes(confirmationFlag)) {
    throw new Error(
      "Destructive mode requires the exact confirmation flag: " + confirmationFlag,
    );
  }
  if (!execute && process.argv.some((arg) => arg.startsWith("--confirm-project="))) {
    throw new Error("The project confirmation flag is only accepted together with --execute.");
  }

  const app = firebaseApp = initializeApp(
    {
      credential: cert(serviceAccount),
      projectId,
      databaseURL,
      ...(storageBucket ? { storageBucket } : {}),
    },
    "inchat-test-data-reset",
  );

  const auth = getAuth(app);
  const database = getDatabase(app);
  const bucket = storageBucket ? getStorage(app).bucket(storageBucket) : null;

  console.log("\nInChat clean-slate reset");
  console.log("Firebase project: " + projectId);
  console.log("Realtime Database: " + databaseURL);
  console.log("Mode: " + (execute ? "EXECUTE - DESTRUCTIVE" : "DRY RUN - NO CHANGES"));

  const [users, ...snapshots] = await Promise.all([
    listAllUsers(auth),
    ...ROOT_DATA_PATHS.map((path) => database.ref(path).get()),
  ]);

  console.log("\nFirebase Authentication accounts found: " + users.length);
  console.log("Realtime Database branches that will be cleared:");
  snapshots.forEach((snapshot, index) => {
    console.log("  " + ROOT_DATA_PATHS[index] + ": " + snapshot.numChildren() + " top-level records");
  });
  if (bucket) {
    console.log("Firebase Storage cleanup: enabled for profilePhotos/ in " + storageBucket);
  } else {
    console.log("Firebase Storage cleanup: skipped (no bucket configured)");
  }
  console.log("Cloudflare R2 attachments are not touched by this script.");

  if (!execute) {
    console.log("\nNothing was deleted. Review the project and counts above first.");
    console.log(
      "To perform the reset, run again with --execute " + confirmationFlag,
    );
    await app.delete();
    return;
  }

  console.log("\nDeleting Firebase Authentication accounts...");
  for (let offset = 0; offset < users.length; offset += 1000) {
    const batch = users.slice(offset, offset + 1000);
    const result = await auth.deleteUsers(batch.map((user) => user.uid));
    console.log(
      "  Deleted " + result.successCount + "/" + batch.length + " accounts in this batch.",
    );
    if (result.failureCount > 0) {
      const failureCodes = result.errors.map((entry) => entry.error?.code ?? "unknown");
      throw new Error(
        "Firebase Auth deletion had " + result.failureCount + " failure(s) (" + failureCodes.join(", ") + "). Database data was left untouched. Fix the permission/error and rerun the script.",
      );
    }
    // Firebase Auth bulk deletion is rate-limited; space out large-project batches.
    if (offset + 1000 < users.length) await delay(1100);
  }

  const remainingUsers = await listAllUsers(auth);
  if (remainingUsers.length > 0) {
    throw new Error(
      remainingUsers.length + " Firebase Authentication account(s) remain. Database data was left untouched.",
    );
  }

  console.log("Clearing InChat Realtime Database data...");
  // Clear normal data branches in one atomic multi-location update.
  // Presence is handled separately because the deployed syncPresence
  // Cloud Function can recreate online/lastSeen fields after connections
  // are deleted.
  const regularPaths = ROOT_DATA_PATHS.filter((path) => path !== "presence");
  const deletions = Object.fromEntries(regularPaths.map((path) => [path, null]));
  await database.ref().update(deletions);

  await database.ref("presence").remove();
  await delay(2500);
  await database.ref("presence").remove();

  const remainingSnapshots = await Promise.all(
    ROOT_DATA_PATHS.map((path) => database.ref(path).get()),
  );
  const remainingPaths = remainingSnapshots
    .map((snapshot, index) => (snapshot.exists() ? ROOT_DATA_PATHS[index] : null))
    .filter(Boolean);
  if (remainingPaths.length > 0) {
    throw new Error(
      "Some Realtime Database branches remain: " + remainingPaths.join(", ") + ". Rerun after checking database permissions.",
    );
  }

  if (bucket) {
    console.log("Removing legacy Firebase Storage profile photos...");
    await bucket.deleteFiles({ prefix: "profilePhotos/", force: true });
  }

  console.log("\nReset completed.");
  console.log("  Firebase Authentication accounts remaining: 0");
  console.log("  InChat Realtime Database branches remaining: 0");
  if (bucket) console.log("  Legacy profilePhotos/ objects: delete request completed");
  console.log("You can now create the two new test accounts.");
  console.log(
    "Note: deleteUsers() is a bulk Auth operation and does not fire individual Auth onDelete Cloud Function handlers.",
  );
  await app.delete();
}

main().catch(async (error) => {
  console.error("\nReset stopped: " + (error?.message ?? error));
  if (firebaseApp) {
    try {
      await firebaseApp.delete();
    } catch (cleanupError) {
      console.error("Firebase cleanup warning: " + (cleanupError?.message ?? cleanupError));
    }
  }
  process.exitCode = 1;
});
