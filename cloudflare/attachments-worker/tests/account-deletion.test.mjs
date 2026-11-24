import test from "node:test";
import assert from "node:assert/strict";
import { cleanupAccountGroupsForDeletion } from "../src/index.js";

function jsonResponse(value, headers = {}) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      ...headers,
    },
  });
}

function assertNoOverlappingPatchPaths(patch) {
  const paths = Object.keys(patch).sort();
  for (let index = 0; index < paths.length; index += 1) {
    for (let childIndex = index + 1; childIndex < paths.length; childIndex += 1) {
      assert.equal(
        paths[childIndex].startsWith(paths[index] + "/"),
        false,
        "Multi-location PATCH contains overlapping paths: " +
          paths[index] + " and " + paths[childIndex],
      );
    }
  }
}

test("account deletion removes owned E2EE data and rotates retained group membership atomically", async () => {
  const previousFetch = globalThis.fetch;
  const group = {
    chatId: "group_1",
    type: "group",
    name: "Engineering",
    createdBy: "alice",
    members: {
      alice: "admin",
      bob: "admin",
      carol: "member",
    },
    cryptoMembership: {
      epoch: 4,
      members: {
        alice: "admin",
        bob: "admin",
        carol: "member",
      },
    },
  };
  const rootPatches = [];
  const groupPatches = [];
  let membershipIndex = { group_1: true };
  let inboxIndex = { group_1: { chatType: "group" } };
  let groupVersion = 1;

  globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(
      url.pathname.replace(/\.json$/, "").replace(/^\/+/, ""),
    );
    const method = options.method || "GET";

    if (url.hostname === "fcm.googleapis.com") {
      return jsonResponse({});
    }

    if (method === "GET") {
      if (path === "deviceKeyClaimsByClaimant/alice") {
        return jsonResponse(null);
      }
      if (path === "groupMemberships/alice") {
        return jsonResponse(membershipIndex);
      }
      if (path === "groupReads/alice" || path === "privateUsers/alice/fcmToken") {
        return jsonResponse(null);
      }
      if (path === "userChats/alice") {
        return jsonResponse(inboxIndex);
      }
      if (path === "groupNotificationDevices/group_1/alice") {
        return jsonResponse(null);
      }
      if (path === "chats/group_1") {
        const headers = options.headers || {};
        if (headers["X-Firebase-ETag"] === "true") {
          return jsonResponse(group, { ETag: '"group-v' + groupVersion + '"' });
        }
        return jsonResponse(group);
      }
      if (path === "chats/group_1/messages") {
        return jsonResponse({
          message_1: {
            timestamp: 100,
            senderId: "bob",
            senderNickname: "Bob",
            systemEvent: "member_removed",
          },
        });
      }
      throw new Error("Unexpected GET request: " + url.toString());
    }

    if (method === "PATCH" && path === "chats/group_1") {
      assert.equal(
        options.headers?.["If-Match"],
        '"group-v' + groupVersion + '"',
      );
      const patch = JSON.parse(options.body);
      groupPatches.push(patch);
      Object.assign(group, patch);
      groupVersion += 1;
      return jsonResponse(null);
    }

    if (method === "PATCH" && path === "") {
      const patch = JSON.parse(options.body);
      assertNoOverlappingPatchPaths(patch);
      rootPatches.push(patch);
      if (Object.prototype.hasOwnProperty.call(patch, "groupMemberships/alice/group_1")) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "userChats/alice/group_1")) {
        inboxIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "groupMemberships/alice")) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "userChats/alice")) {
        inboxIndex = null;
      }
      return jsonResponse(null);
    }

    throw new Error("Unexpected request: " + method + " " + url.toString());
  };

  try {
    const response = await cleanupAccountGroupsForDeletion(
      {
        FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com",
        FCM_TOPIC_SECRET: "test-only-topic-secret-at-least-32-bytes",
      },
      "alice",
      { token: "admin-token", projectId: "demo-inchat" },
    );

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      success: true,
      complete: true,
      processedGroups: 1,
      updatedGroups: 1,
      dissolvedGroups: 0,
    });

    assert.deepEqual(group.members, {
      bob: "admin",
      carol: "member",
    });
    assert.deepEqual(group.cryptoMembership, {
      epoch: 5,
      members: {
        bob: "admin",
        carol: "member",
      },
    });
    assert.equal(group.createdBy, "bob");
    assert.deepEqual(groupPatches, [{
      members: {
        bob: "admin",
        carol: "member",
      },
      cryptoMembership: {
        epoch: 5,
        members: {
          bob: "admin",
          carol: "member",
        },
      },
      createdBy: "bob",
    }]);

    assert.equal(rootPatches.length, 3);
    const previewPatch = rootPatches[0];
    assert.equal(previewPatch["userChats/bob/group_1/memberCount"], 2);
    assert.equal(previewPatch["userChats/carol/group_1/memberCount"], 2);
    assert.equal(
      Object.keys(previewPatch).some((path) => path.startsWith("userChats/alice/")),
      false,
    );

    const cleanupPatch = rootPatches[1];
    for (const path of [
      "groupMemberships/alice/group_1",
      "groupReads/alice/group_1",
      "userChats/alice/group_1",
      "chatReadReceipts/group_1/alice",
      "groupNotificationDevices/group_1/alice",
    ]) {
      assert.equal(cleanupPatch[path], null, "Expected deletion of " + path);
    }
    const finalCleanupPatch = rootPatches[2];
    for (const path of [
      "deviceKeys/alice",
      "deviceKeyClaims/alice",
      "olmInbox/alice",
      "olmDeliveryAcks/alice",
      "olmPushDispatch/alice",
      "sasVerificationInbox/alice",
      "deviceTransferInbox/alice",
      "groupMemberships/alice",
      "groupReads/alice",
      "userChats/alice",
    ]) {
      assert.equal(finalCleanupPatch[path], null, "Expected deletion of " + path);
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
});


test("account deletion dissolves a two-member group without overlapping group-root paths", async () => {
  const previousFetch = globalThis.fetch;
  const group = {
    chatId: "group_2",
    type: "group",
    name: "Small group",
    createdBy: "alice",
    members: {
      alice: "admin",
      bob: "member",
    },
    cryptoMembership: {
      epoch: 2,
      members: {
        alice: "admin",
        bob: "member",
      },
    },
  };
  const rootPatches = [];
  let membershipIndex = { group_2: true };
  let inboxIndex = { group_2: { chatType: "group" } };

  globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(
      url.pathname.replace(/\.json$/, "").replace(/^\/+/, ""),
    );
    const method = options.method || "GET";

    if (method === "GET") {
      if (path === "deviceKeyClaimsByClaimant/alice") {
        return jsonResponse(null);
      }
      if (path === "groupMemberships/alice") {
        return jsonResponse(membershipIndex);
      }
      if (
        path === "groupReads/alice" ||
        path === "privateUsers/alice/fcmToken" ||
        path === "groupNotificationDevices/group_2/alice" ||
        path === "privateUsers/bob/fcmToken"
      ) {
        return jsonResponse(null);
      }
      if (path === "userChats/alice") {
        return jsonResponse(inboxIndex);
      }
      if (path === "chats/group_2") return jsonResponse(group);
      throw new Error("Unexpected GET request: " + url.toString());
    }

    if (method === "PATCH" && path === "") {
      const patch = JSON.parse(options.body);
      assertNoOverlappingPatchPaths(patch);
      rootPatches.push(patch);
      if (Object.prototype.hasOwnProperty.call(patch, "groupMemberships/alice/group_2")) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "userChats/alice/group_2")) {
        inboxIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "groupMemberships/alice")) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "userChats/alice")) {
        inboxIndex = null;
      }
      return jsonResponse(null);
    }

    throw new Error("Unexpected request: " + method + " " + url.toString());
  };

  try {
    const response = await cleanupAccountGroupsForDeletion(
      {
        FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com",
        FCM_TOPIC_SECRET: "test-only-topic-secret-at-least-32-bytes",
      },
      "alice",
      { token: "admin-token", projectId: "demo-inchat" },
    );

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      success: true,
      complete: true,
      processedGroups: 1,
      updatedGroups: 0,
      dissolvedGroups: 1,
    });
    assert.equal(rootPatches.length, 2);
    const dissolvePatch = rootPatches[0];
    assert.equal(dissolvePatch["chats/group_2"], null);
    assert.equal(dissolvePatch["attachments/group_2"], null);
    assert.equal(dissolvePatch["groupMemberships/bob/group_2"], null);
    assert.equal(dissolvePatch["groupReads/bob/group_2"], null);
    assert.equal(dissolvePatch["userChats/bob/group_2"], null);
    assert.equal(
      Object.prototype.hasOwnProperty.call(
        dissolvePatch,
        "chats/group_2/groupReadCursors/alice",
      ),
      false,
    );
    assert.equal(rootPatches[1]["deviceKeys/alice"], null);
  } finally {
    globalThis.fetch = previousFetch;
  }
});


test("account deletion processes many groups one bounded batch at a time", async () => {
  const previousFetch = globalThis.fetch;
  const groups = {
    group_a: {
      chatId: "group_a",
      type: "group",
      name: "Alpha",
      createdBy: "alice",
      members: { alice: "admin", bob: "admin", carol: "member" },
      cryptoMembership: {
        epoch: 1,
        members: { alice: "admin", bob: "admin", carol: "member" },
      },
    },
    group_b: {
      chatId: "group_b",
      type: "group",
      name: "Beta",
      createdBy: "alice",
      members: { alice: "admin", dan: "admin", erin: "member" },
      cryptoMembership: {
        epoch: 7,
        members: { alice: "admin", dan: "admin", erin: "member" },
      },
    },
  };
  const membershipIndex = { group_a: true, group_b: true };
  const inboxIndex = {
    group_a: { chatType: "group" },
    group_b: { chatType: "group" },
  };
  const rootPatches = [];
  const groupPatches = [];
  const versions = { group_a: 1, group_b: 1 };

  globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(
      url.pathname.replace(/\.json$/, "").replace(/^\/+/, ""),
    );
    const method = options.method || "GET";

    if (method === "GET") {
      if (path === "deviceKeyClaimsByClaimant/alice") {
        return jsonResponse(null);
      }
      if (path === "groupMemberships/alice") {
        return jsonResponse(membershipIndex);
      }
      if (path === "groupReads/alice" || path === "privateUsers/alice/fcmToken") {
        return jsonResponse(null);
      }
      if (path === "userChats/alice") {
        return jsonResponse(inboxIndex);
      }
      const deviceMatch = path.match(/^groupNotificationDevices\/(group_[ab])\/alice$/);
      if (deviceMatch) return jsonResponse(null);

      const chatMatch = path.match(/^chats\/(group_[ab])$/);
      if (chatMatch) {
        const chatId = chatMatch[1];
        if (options.headers?.["X-Firebase-ETag"] === "true") {
          return jsonResponse(groups[chatId], {
            ETag: '"group-v' + versions[chatId] + '"',
          });
        }
        return jsonResponse(groups[chatId]);
      }
      if (/^chats\/group_[ab]\/messages$/.test(path)) {
        return jsonResponse({});
      }
      throw new Error("Unexpected GET request: " + url.toString());
    }

    if (method === "PATCH" && /^chats\/group_[ab]$/.test(path)) {
      const chatId = path.split("/")[1];
      assert.equal(options.headers?.["If-Match"], '"group-v' + versions[chatId] + '"');
      const patch = JSON.parse(options.body);
      groupPatches.push({ chatId, patch });
      Object.assign(groups[chatId], patch);
      versions[chatId] += 1;
      return jsonResponse(null);
    }

    if (method === "PATCH" && path === "") {
      const patch = JSON.parse(options.body);
      assertNoOverlappingPatchPaths(patch);
      rootPatches.push(patch);
      for (const chatId of ["group_a", "group_b"]) {
        if (Object.prototype.hasOwnProperty.call(
          patch,
          "groupMemberships/alice/" + chatId,
        )) {
          delete membershipIndex[chatId];
        }
        if (Object.prototype.hasOwnProperty.call(
          patch,
          "userChats/alice/" + chatId,
        )) {
          delete inboxIndex[chatId];
        }
      }
      return jsonResponse(null);
    }

    throw new Error("Unexpected request: " + method + " " + url.toString());
  };

  try {
    const env = {
      FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com",
      FCM_TOPIC_SECRET: "test-only-topic-secret-at-least-32-bytes",
    };
    const adminAuth = { token: "admin-token", projectId: "demo-inchat" };

    const first = await cleanupAccountGroupsForDeletion(env, "alice", adminAuth);
    assert.deepEqual(await first.json(), {
      success: true,
      complete: false,
      processedGroups: 1,
      updatedGroups: 1,
      dissolvedGroups: 0,
    });
    assert.deepEqual(Object.keys(membershipIndex), ["group_b"]);
    assert.equal(groupPatches.length, 1);

    const second = await cleanupAccountGroupsForDeletion(env, "alice", adminAuth);
    assert.deepEqual(await second.json(), {
      success: true,
      complete: true,
      processedGroups: 1,
      updatedGroups: 1,
      dissolvedGroups: 0,
    });
    assert.deepEqual(Object.keys(membershipIndex), []);
    assert.equal(groupPatches.length, 2);
    assert.deepEqual(groups.group_a.cryptoMembership.members, groups.group_a.members);
    assert.deepEqual(groups.group_b.cryptoMembership.members, groups.group_b.members);
    assert.equal(rootPatches.some((patch) =>
      Object.prototype.hasOwnProperty.call(patch, "deviceKeys/alice")
    ), true);
  } finally {
    globalThis.fetch = previousFetch;
  }
});


test("account deletion retries a preview after membership was already committed", async () => {
  const previousFetch = globalThis.fetch;
  const group = {
    chatId: "group_retry",
    type: "group",
    name: "Retry group",
    createdBy: "alice",
    members: { alice: "admin", bob: "admin", carol: "member" },
    cryptoMembership: {
      epoch: 8,
      members: { alice: "admin", bob: "admin", carol: "member" },
    },
  };
  let membershipIndex = { group_retry: true };
  let inboxIndex = { group_retry: { chatType: "group" } };
  let groupVersion = 1;
  let failFirstPreviewRead = true;
  const rootPatches = [];

  globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(
      url.pathname.replace(/\.json$/, "").replace(/^\/+/, ""),
    );
    const method = options.method || "GET";

    if (method === "GET") {
      if (path === "deviceKeyClaimsByClaimant/alice") {
        return jsonResponse(null);
      }
      if (path === "groupMemberships/alice") return jsonResponse(membershipIndex);
      if (path === "groupReads/alice" || path === "privateUsers/alice/fcmToken") {
        return jsonResponse(null);
      }
      if (path === "userChats/alice") return jsonResponse(inboxIndex);
      if (path === "groupNotificationDevices/group_retry/alice") {
        return jsonResponse(null);
      }
      if (path === "chats/group_retry") {
        if (options.headers?.["X-Firebase-ETag"] === "true") {
          return jsonResponse(group, { ETag: '"group-v' + groupVersion + '"' });
        }
        return jsonResponse(group);
      }
      if (path === "chats/group_retry/messages") {
        if (failFirstPreviewRead) {
          failFirstPreviewRead = false;
          throw new Error("Simulated transient preview read failure.");
        }
        return jsonResponse({});
      }
      throw new Error("Unexpected GET request: " + url.toString());
    }

    if (method === "PATCH" && path === "chats/group_retry") {
      assert.equal(options.headers?.["If-Match"], '"group-v' + groupVersion + '"');
      const patch = JSON.parse(options.body);
      Object.assign(group, patch);
      groupVersion += 1;
      return jsonResponse(null);
    }

    if (method === "PATCH" && path === "") {
      const patch = JSON.parse(options.body);
      assertNoOverlappingPatchPaths(patch);
      rootPatches.push(patch);
      if (Object.prototype.hasOwnProperty.call(
        patch,
        "groupMemberships/alice/group_retry",
      )) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(
        patch,
        "userChats/alice/group_retry",
      )) {
        inboxIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "groupMemberships/alice")) {
        membershipIndex = null;
      }
      if (Object.prototype.hasOwnProperty.call(patch, "userChats/alice")) {
        inboxIndex = null;
      }
      return jsonResponse(null);
    }

    throw new Error("Unexpected request: " + method + " " + url.toString());
  };

  try {
    const env = {
      FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com",
      FCM_TOPIC_SECRET: "test-only-topic-secret-at-least-32-bytes",
    };
    const adminAuth = { token: "admin-token", projectId: "demo-inchat" };

    await assert.rejects(
      cleanupAccountGroupsForDeletion(env, "alice", adminAuth),
      /Simulated transient preview read failure/,
    );
    assert.deepEqual(group.members, { bob: "admin", carol: "member" });
    assert.deepEqual(membershipIndex, { group_retry: true });
    assert.equal(rootPatches.length, 0);

    const retry = await cleanupAccountGroupsForDeletion(env, "alice", adminAuth);
    assert.deepEqual(await retry.json(), {
      success: true,
      complete: true,
      processedGroups: 1,
      updatedGroups: 0,
      dissolvedGroups: 0,
    });
    assert.deepEqual(membershipIndex, null);
    assert.equal(rootPatches.some((patch) =>
      patch["userChats/bob/group_retry/memberCount"] === 2
    ), true);
    assert.equal(rootPatches.some((patch) =>
      Object.prototype.hasOwnProperty.call(patch, "deviceKeys/alice")
    ), true);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("account deletion releases claimant-owned prekey claims one ETag-protected batch at a time", async () => {
  const previousFetch = globalThis.fetch;
  const index = {
    target_a: { device_a: { key_a: true } },
    target_b: { device_b: { key_b: true } },
  };
  const claims = {
    "target_a/device_a/key_a": { claimantUid: "alice", conversationType: "direct" },
    "target_b/device_b/key_b": { claimantUid: "alice", conversationType: "group" },
  };
  const claimDeletes = [];
  const indexDeletes = [];
  const rootPatches = [];

  globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(url.pathname.replace(/\.json$/, "").replace(/^\/+/, ""));
    const method = options.method || "GET";

    if (method === "GET") {
      if (path === "groupMemberships/alice" || path === "groupReads/alice" ||
          path === "userChats/alice" || path === "privateUsers/alice/fcmToken") {
        return jsonResponse(null);
      }
      if (path === "deviceKeyClaimsByClaimant/alice") return jsonResponse(index);
      if (path.startsWith("deviceKeyClaims/")) {
        const key = path.slice("deviceKeyClaims/".length);
        return jsonResponse(claims[key], { ETag: '"claim-' + key + '"' });
      }
      throw new Error("Unexpected GET request: " + url.toString());
    }

    if (method === "DELETE" && path.startsWith("deviceKeyClaims/")) {
      const key = path.slice("deviceKeyClaims/".length);
      assert.equal(options.headers?.["If-Match"], '"claim-' + key + '"');
      claimDeletes.push(key);
      delete claims[key];
      return jsonResponse(null);
    }

    if (method === "DELETE" && path.startsWith("deviceKeyClaimsByClaimant/alice/")) {
      const parts = path.split("/");
      indexDeletes.push(parts.slice(1).join("/"));
      delete index[parts[1]]?.[parts[2]]?.[parts[3]];
      if (Object.keys(index[parts[1]]?.[parts[2]] || {}).length === 0) delete index[parts[1]][parts[2]];
      if (Object.keys(index[parts[1]] || {}).length === 0) delete index[parts[1]];
      return jsonResponse(null);
    }

    if (method === "PATCH" && path === "") {
      const patch = JSON.parse(options.body);
      rootPatches.push(patch);
      return jsonResponse(null);
    }

    throw new Error("Unexpected request: " + method + " " + url.toString());
  };

  try {
    const env = { FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com" };
    const auth = { token: "admin-token", projectId: "demo-inchat" };
    const first = await cleanupAccountGroupsForDeletion(env, "alice", auth);
    assert.equal(first.status, 200);
    assert.deepEqual(await first.json(), {
      success: true,
      complete: false,
      processedGroups: 0,
      processedPreKeyClaims: 1,
      updatedGroups: 0,
      dissolvedGroups: 0,
    });
    assert.deepEqual(claimDeletes, ["target_a/device_a/key_a"]);
    assert.deepEqual(indexDeletes, ["target_a/device_a/key_a"]);
    assert.equal(rootPatches.length, 0);

    const second = await cleanupAccountGroupsForDeletion(env, "alice", auth);
    assert.equal(second.status, 200);
    assert.deepEqual(await second.json(), {
      success: true,
      complete: true,
      processedGroups: 0,
      updatedGroups: 0,
      dissolvedGroups: 0,
    });
    assert.deepEqual(claimDeletes, [
      "target_a/device_a/key_a",
      "target_b/device_b/key_b",
    ]);
    assert.deepEqual(indexDeletes, [
      "target_a/device_a/key_a",
      "target_b/device_b/key_b",
    ]);
    assert.equal(rootPatches.length, 1);
    assert.equal(rootPatches[0]["deviceKeyClaimsByClaimant/alice"], null);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

