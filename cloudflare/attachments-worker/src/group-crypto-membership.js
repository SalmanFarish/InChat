export const INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH = 1;

export function currentGroupCryptoMembershipEpoch(value) {
  if (value === null || value === undefined) return INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH;
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new TypeError("Group crypto membership epoch is invalid.");
  }
  return value;
}

export function nextGroupCryptoMembershipEpoch(currentValue) {
  const current = currentGroupCryptoMembershipEpoch(currentValue);
  if (current >= Number.MAX_SAFE_INTEGER) {
    throw new RangeError("Group crypto membership epoch is exhausted.");
  }
  return current + 1;
}

export function membershipEpochUpdate(currentValue, membershipChanged) {
  return membershipChanged ? nextGroupCryptoMembershipEpoch(currentValue) : null;
}

function childUrl(env, chatId, child) {
  const base = env.FIREBASE_DATABASE_URL.endsWith("/") ?
    env.FIREBASE_DATABASE_URL.slice(0, -1) :
    env.FIREBASE_DATABASE_URL;
  const path = "chats/" + chatId + "/" + child;
  return base + "/" + path.split("/").map(encodeURIComponent).join("/") + ".json";
}

async function readValue(url, adminToken, fetchImpl, withEtag = false) {
  const response = await fetchImpl(url, {
    headers: {
      Authorization: "Bearer " + adminToken,
      Accept: "application/json",
      ...(withEtag ? { "X-Firebase-ETag": "true" } : {}),
    },
  });
  if (!response.ok) {
    throw new Error("Could not read group membership metadata (" + response.status + ").");
  }
  return { value: await response.json(), etag: response.headers.get("ETag") };
}

function validMembers(members) {
  if (!members || typeof members !== "object" || Array.isArray(members)) return false;
  const ids = Object.keys(members);
  return ids.length >= 2 && ids.length <= 50 &&
    ids.every((id) => id && (members[id] === "admin" || members[id] === "member"));
}

async function writeControl(url, adminToken, etag, snapshot, fetchImpl) {
  const response = await fetchImpl(url, {
    method: "PUT",
    headers: {
      Authorization: "Bearer " + adminToken,
      "Content-Type": "application/json",
      "If-Match": etag,
    },
    body: JSON.stringify(snapshot),
  });
  if (response.status === 412) return false;
  if (!response.ok) {
    const details = await response.text();
    throw new Error("Could not update group crypto membership (" +
      response.status + "): " + details.slice(0, 240));
  }
  return true;
}

/**
 * ETag compare-and-swap on chats/{groupId}/cryptoMembership only. A legacy
 * members map is consulted solely when that control record is absent.
 */
export async function mutateGroupMembersWithEtag(
  env,
  adminToken,
  chatId,
  mutate,
  fetchImpl = fetch,
) {
  const controlUrl = childUrl(env, chatId, "cryptoMembership");
  const typeUrl = childUrl(env, chatId, "type");
  const creatorUrl = childUrl(env, chatId, "createdBy");
  const legacyMembersUrl = childUrl(env, chatId, "members");

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const [controlResult, typeResult, creatorResult] = await Promise.all([
      readValue(controlUrl, adminToken, fetchImpl, true),
      readValue(typeUrl, adminToken, fetchImpl),
      readValue(creatorUrl, adminToken, fetchImpl),
    ]);
    if (typeResult.value !== "group") throw new Error("Group membership state is invalid.");
    if (!controlResult.etag) {
      throw new Error("Realtime Database did not return a crypto-membership ETag.");
    }

    const control = controlResult.value;
    let members;
    let epoch;
    if (control === null) {
      members = (await readValue(legacyMembersUrl, adminToken, fetchImpl)).value;
      epoch = INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH;
    } else {
      if (!control || typeof control !== "object" || Array.isArray(control) ||
          !validMembers(control.members)) {
        throw new Error("Group membership state is invalid.");
      }
      epoch = currentGroupCryptoMembershipEpoch(control.epoch);
      members = control.members;
    }
    if (!validMembers(members)) throw new Error("Group membership state is invalid.");

    const currentChat = {
      type: "group",
      createdBy: creatorResult.value || "",
      members,
      cryptoMembership: control,
    };
    const decision = mutate(members, currentChat);
    if (!decision || decision.noChange) {
      return {
        members,
        changed: false,
        cryptoMembershipEpoch: epoch,
        result: decision ? decision.result : undefined,
      };
    }
    if (!validMembers(decision.members)) throw new Error("Updated group membership state is invalid.");

    const nextEpoch = membershipEpochUpdate(epoch, decision.cryptoMembershipChanged === true);
    const resolvedEpoch = nextEpoch ?? epoch;
    const nextControl = { epoch: resolvedEpoch, members: decision.members };
    if (!await writeControl(controlUrl, adminToken, controlResult.etag, nextControl, fetchImpl)) {
      continue;
    }

    // Creator identity is separate metadata; update only after the roster CAS.
    if (typeof decision.createdBy === "string") {
      const creatorResponse = await fetchImpl(creatorUrl, {
        method: "PUT",
        headers: {
          Authorization: "Bearer " + adminToken,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(decision.createdBy),
      });
      if (!creatorResponse.ok) {
        throw new Error("Membership changed, but creator metadata could not be synchronized.");
      }
    }

    return {
      members: decision.members,
      changed: true,
      cryptoMembershipEpoch: resolvedEpoch,
      result: decision.result,
    };
  }

  throw new Response("Group membership changed at the same time. Please try again.", { status: 409 });
}

/** Initialize the metadata-only E2EE roster for a legacy group. */
export async function ensureGroupCryptoMembershipSnapshotWithEtag(
  env,
  adminToken,
  chatId,
  authorizedUid,
  fetchImpl = fetch,
) {
  const controlUrl = childUrl(env, chatId, "cryptoMembership");
  const typeUrl = childUrl(env, chatId, "type");
  const legacyMembersUrl = childUrl(env, chatId, "members");

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const [controlResult, typeResult] = await Promise.all([
      readValue(controlUrl, adminToken, fetchImpl, true),
      readValue(typeUrl, adminToken, fetchImpl),
    ]);
    if (typeResult.value !== "group") throw new Response("Group not found.", { status: 404 });
    if (!controlResult.etag) {
      throw new Error("Realtime Database did not return a crypto-membership ETag.");
    }

    if (controlResult.value !== null) {
      const existing = controlResult.value;
      if (!existing || typeof existing !== "object" || Array.isArray(existing) ||
          !validMembers(existing.members)) {
        throw new Error("Group membership snapshot is invalid.");
      }
      const epoch = currentGroupCryptoMembershipEpoch(existing.epoch);
      if (!Object.prototype.hasOwnProperty.call(existing.members, authorizedUid)) {
        throw new Response("You are not a current group member.", { status: 403 });
      }
      return { groupId: chatId, epoch, members: existing.members, changed: false };
    }

    const members = (await readValue(legacyMembersUrl, adminToken, fetchImpl)).value;
    if (!validMembers(members)) throw new Error("Group membership map is invalid.");
    if (!Object.prototype.hasOwnProperty.call(members, authorizedUid)) {
      throw new Response("You are not a current group member.", { status: 403 });
    }
    const snapshot = { epoch: INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH, members };
    if (!await writeControl(controlUrl, adminToken, controlResult.etag, snapshot, fetchImpl)) {
      continue;
    }
    return { groupId: chatId, epoch: snapshot.epoch, members, changed: true };
  }
  throw new Response("Group membership changed at the same time. Please try again.", { status: 409 });
}
