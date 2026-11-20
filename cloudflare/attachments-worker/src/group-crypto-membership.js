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
  const url = env.FIREBASE_DATABASE_URL.replace(/\\/$/, "") +
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
    const decision = mutate(currentMembers);
    if (!decision || decision.noChange) {
      return {
        members: currentMembers,
        changed: false,
        cryptoMembershipEpoch: currentGroupCryptoMembershipEpoch(
          currentChat.cryptoMembershipEpoch,
        ),
        result: decision ? decision.result : undefined,
      };
    }

    const nextEpoch = membershipEpochUpdate(
      currentChat.cryptoMembershipEpoch,
      decision.cryptoMembershipChanged === true,
    );
    const patch = { members: decision.members };
    if (nextEpoch !== null) {
      patch.cryptoMembershipEpoch = nextEpoch;
    }

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
      cryptoMembershipEpoch: nextEpoch ??
        currentGroupCryptoMembershipEpoch(currentChat.cryptoMembershipEpoch),
      result: decision.result,
    };
  }

  throw new Response(
    "Group membership changed at the same time. Please try again.",
    { status: 409 },
  );
}
