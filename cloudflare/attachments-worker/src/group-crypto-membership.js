export const INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH = 1;

/**
 * Legacy groups without a crypto epoch are treated as epoch 1 (their initial
 * membership). A real add/remove/leave advances that baseline to epoch 2.
 */
export function currentGroupCryptoMembershipEpoch(value) {
  if (value === null || value === undefined) {
    return INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH;
  }
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new TypeError("Group crypto membership epoch is invalid.");
  }
  return value;
}

/** Advance only for a real change to the set of group members, never a role edit. */
export function nextGroupCryptoMembershipEpoch(currentValue) {
  const current = currentGroupCryptoMembershipEpoch(currentValue);
  if (current >= Number.MAX_SAFE_INTEGER) {
    throw new RangeError("Group crypto membership epoch is exhausted.");
  }
  return current + 1;
}

/**
 * Decide the epoch persisted alongside a conditional membership mutation.
 * Returns null for no-op and role-only changes.
 */
export function membershipEpochUpdate(currentValue, membershipChanged) {
  if (!membershipChanged) return null;
  return nextGroupCryptoMembershipEpoch(currentValue);
}

/**
 * ETag-conditional group-root mutation. The membership map and crypto epoch
 * share one PATCH guarded by the group-root ETag, so concurrent changes cannot
 * commit a new member set with a stale/reused epoch.
 */
export async function mutateGroupMembersWithEtag(
  env,
  adminToken,
  chatId,
  mutate,
  fetchImpl = fetch,
) {
  const path = "chats/" + chatId;
  const databaseUrl = env.FIREBASE_DATABASE_URL.endsWith("/") ?
    env.FIREBASE_DATABASE_URL.slice(0, -1) :
    env.FIREBASE_DATABASE_URL;
  const url = databaseUrl +
    "/" + path.split("/").map(encodeURIComponent).join("/") + ".json";

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const readResponse = await fetchImpl(url, {
      headers: {
        Authorization: "Bearer " + adminToken,
        "X-Firebase-ETag": "true",
      },
    });

    if (!readResponse.ok) {
      throw new Error(
        "Could not read group membership state (" + readResponse.status + ").",
      );
    }

    const currentChat = await readResponse.json();
    if (!currentChat || currentChat.type !== "group" ||
        !currentChat.members || typeof currentChat.members !== "object" ||
        Array.isArray(currentChat.members)) {
      throw new Error("Group membership state is invalid.");
    }

    const etag = readResponse.headers.get("ETag");
    if (!etag) throw new Error("Realtime Database did not return a group ETag.");

    const currentMembers = currentChat.members;
    const decision = mutate(currentMembers, currentChat);
    if (!decision || decision.noChange) {
      return {
        members: currentMembers,
        changed: false,
        cryptoMembershipEpoch: currentGroupCryptoMembershipEpoch(
          currentChat.cryptoMembership?.epoch ?? currentChat.cryptoMembershipEpoch,
        ),
        result: decision ? decision.result : undefined,
      };
    }

    const currentEpoch = currentGroupCryptoMembershipEpoch(
      currentChat.cryptoMembership?.epoch ?? currentChat.cryptoMembershipEpoch,
    );
    const nextEpoch = membershipEpochUpdate(
      currentEpoch,
      decision.cryptoMembershipChanged === true,
    );
    const resolvedEpoch = nextEpoch ?? currentEpoch;
    const patch = {
      members: decision.members,
      // A single listener can observe this immutable membership snapshot;
      // clients never need to combine two independently delivered child events.
      cryptoMembership: {
        epoch: resolvedEpoch,
        members: decision.members,
      },
      ...(typeof decision.createdBy === "string" ?
        { createdBy: decision.createdBy } :
        {}),
    };

    const writeResponse = await fetchImpl(url, {
      method: "PATCH",
      headers: {
        Authorization: "Bearer " + adminToken,
        "Content-Type": "application/json",
        "If-Match": etag,
      },
      body: JSON.stringify(patch),
    });

    if (writeResponse.status === 412) continue;
    if (!writeResponse.ok) {
      const details = await writeResponse.text();
      throw new Error(
        "Could not update group membership (" +
        writeResponse.status + "): " + details.slice(0, 240),
      );
    }

    return {
      members: decision.members,
      changed: true,
      cryptoMembershipEpoch: resolvedEpoch,
      result: decision.result,
    };
  }

  throw new Response(
    "Group membership changed at the same time. Please try again.",
    { status: 409 },
  );
}

function sameMemberMap(left, right) {
  if (!left || !right || typeof left !== "object" || typeof right !== "object" ||
      Array.isArray(left) || Array.isArray(right)) {
    return false;
  }
  const leftKeys = Object.keys(left).sort();
  const rightKeys = Object.keys(right).sort();
  return leftKeys.length === rightKeys.length &&
    leftKeys.every((key, index) =>
      key === rightKeys[index] && left[key] === right[key],
    );
}

/**
 * Lazily initializes the authoritative snapshot for pre-existing groups.
 * The authenticated account is checked against the member map on every ETag
 * retry, so a user removed during a concurrent change cannot initialize/read
 * the replacement snapshot.
 */
export async function ensureGroupCryptoMembershipSnapshotWithEtag(
  env,
  adminToken,
  chatId,
  authorizedUid,
  fetchImpl = fetch,
) {
  const path = "chats/" + chatId;
  const databaseUrl = env.FIREBASE_DATABASE_URL.endsWith("/") ?
    env.FIREBASE_DATABASE_URL.slice(0, -1) :
    env.FIREBASE_DATABASE_URL;
  const url = databaseUrl +
    "/" + path.split("/").map(encodeURIComponent).join("/") + ".json";

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const readResponse = await fetchImpl(url, {
      headers: {
        Authorization: "Bearer " + adminToken,
        "X-Firebase-ETag": "true",
      },
    });
    if (!readResponse.ok) {
      throw new Error(
        "Could not read group crypto membership (" + readResponse.status + ").",
      );
    }

    const currentChat = await readResponse.json();
    if (!currentChat || currentChat.type !== "group" ||
        !currentChat.members || typeof currentChat.members !== "object" ||
        Array.isArray(currentChat.members)) {
      throw new Response("Group not found.", { status: 404 });
    }

    const members = currentChat.members;
    if (!Object.prototype.hasOwnProperty.call(members, authorizedUid)) {
      throw new Response("You are not a current group member.", { status: 403 });
    }
    if (Object.keys(members).length < 2 || Object.keys(members).length > 50 ||
        Object.entries(members).some(([uid, role]) =>
          !uid || (role !== "admin" && role !== "member"),
        )) {
      throw new Error("Group membership map is invalid.");
    }

    const existing = currentChat.cryptoMembership;
    if (
      existing &&
      currentGroupCryptoMembershipEpoch(existing.epoch) > 0 &&
      sameMemberMap(existing.members, members)
    ) {
      return {
        groupId: chatId,
        epoch: existing.epoch,
        members: existing.members,
        changed: false,
      };
    }

    const etag = readResponse.headers.get("ETag");
    if (!etag) throw new Error("Realtime Database did not return a group ETag.");

    // A truly legacy group begins at epoch 1. If an existing snapshot drifted
    // from its canonical member map, advance the epoch to invalidate old keys.
    const epoch = existing ?
      nextGroupCryptoMembershipEpoch(existing.epoch) :
      INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH;
    const snapshot = { epoch, members };
    const writeResponse = await fetchImpl(url, {
      method: "PATCH",
      headers: {
        Authorization: "Bearer " + adminToken,
        "Content-Type": "application/json",
        "If-Match": etag,
      },
      body: JSON.stringify({ cryptoMembership: snapshot }),
    });

    if (writeResponse.status === 412) continue;
    if (!writeResponse.ok) {
      const details = await writeResponse.text();
      throw new Error(
        "Could not initialize group crypto membership (" +
        writeResponse.status + "): " + details.slice(0, 240),
      );
    }

    return {
      groupId: chatId,
      epoch,
      members,
      changed: true,
    };
  }

  throw new Response(
    "Group membership changed at the same time. Please try again.",
    { status: 409 },
  );
}

