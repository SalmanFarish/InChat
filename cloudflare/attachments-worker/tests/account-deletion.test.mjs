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
      if (path === "groupMemberships/alice") {
        return jsonResponse({ group_1: true });
      }
      if (path === "groupReads/alice" || path === "privateUsers/alice/fcmToken") {
        return jsonResponse(null);
      }
      if (path === "userChats/alice") {
        return jsonResponse({ group_1: { chatType: "group" } });
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

    assert.equal(rootPatches.length, 2);
    const cleanupPatch = rootPatches[0];
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
      "chatReadReceipts/group_1/alice",
    ]) {
      assert.equal(cleanupPatch[path], null, "Expected deletion of " + path);
    }
    assert.equal(
      Object.prototype.hasOwnProperty.call(
        cleanupPatch,
        "groupMemberships/alice/group_1",
      ),
      false,
    );
    assert.equal(
      Object.prototype.hasOwnProperty.call(cleanupPatch, "userChats/alice/group_1"),
      false,
    );

    const previewPatch = rootPatches[1];
    assert.equal(previewPatch["userChats/bob/group_1/memberCount"], 2);
    assert.equal(previewPatch["userChats/carol/group_1/memberCount"], 2);
    assert.equal(
      Object.keys(previewPatch).some((path) => path.startsWith("userChats/alice/")),
      false,
    );
  } finally {
    globalThis.fetch = previousFetch;
  }
});
