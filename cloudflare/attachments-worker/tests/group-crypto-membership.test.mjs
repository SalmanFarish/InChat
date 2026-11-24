import test from "node:test";
import assert from "node:assert/strict";
import {
  INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH,
  currentGroupCryptoMembershipEpoch,
  nextGroupCryptoMembershipEpoch,
  membershipEpochUpdate,
  mutateGroupMembersWithEtag,
  ensureGroupCryptoMembershipSnapshotWithEtag,
} from "../src/group-crypto-membership.js";

const environment = {
  FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com/",
};

function groupFixture(group, { onConditionalWrite } = {}) {
  let version = 1;
  let reads = 0;
  let writes = 0;
  const readPaths = [];
  const writePaths = [];

  const fetchImpl = async (input, options = {}) => {
    const url = new URL(String(input));
    const path = decodeURIComponent(
      url.pathname.replace(/^\/+/, "").replace(/\.json$/, ""),
    );
    const method = options.method || "GET";
    const child = path.startsWith("chats/group-1/") ?
      path.slice("chats/group-1/".length) :
      "";

    if (method === "GET") {
      reads += 1;
      readPaths.push(path);
      if (path === "chats/group-1") {
        throw new Error("Whole chat root reads are forbidden.");
      }

      let value;
      if (child === "type") value = group.type ?? null;
      else if (child === "createdBy") value = group.createdBy ?? null;
      else if (child === "members") value = group.members ?? null;
      else if (child === "cryptoMembership") value = group.cryptoMembership ?? null;
      else throw new Error("Unexpected membership metadata read: " + path);

      return new Response(JSON.stringify(value), {
        status: 200,
        headers: child === "cryptoMembership" ? { ETag: '"v' + version + '"' } : {},
      });
    }

    assert.equal(method, "PUT", "Only metadata child PUTs are expected.");
    writePaths.push(path);
    const body = JSON.parse(options.body);

    if (child === "cryptoMembership") {
      writes += 1;
      if (options.headers?.["If-Match"] !== '"v' + version + '"') {
        return new Response("precondition failed", { status: 412 });
      }
      if (onConditionalWrite) {
        const reject = await onConditionalWrite({
          group,
          version,
          writeNumber: writes,
        });
        if (reject) {
          version += 1;
          return new Response("precondition failed", { status: 412 });
        }
      }
      group.cryptoMembership = body;
      version += 1;
      return new Response("null", { status: 200 });
    }

    if (child === "createdBy") {
      group.createdBy = body;
      return new Response("null", { status: 200 });
    }

    throw new Error("Unexpected membership metadata write: " + path);
  };

  return {
    group,
    readPaths,
    writePaths,
    fetchImpl,
    get reads() { return reads; },
    get writes() { return writes; },
  };
}

test("new groups start at crypto membership epoch one", () => {
  assert.equal(INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH, 1);
  assert.equal(currentGroupCryptoMembershipEpoch(null), 1);
  assert.equal(currentGroupCryptoMembershipEpoch(undefined), 1);
});

test("a legacy group uses epoch one as its baseline", () => {
  assert.equal(nextGroupCryptoMembershipEpoch(undefined), 2);
  assert.equal(membershipEpochUpdate(null, true), 2);
  assert.equal(membershipEpochUpdate(null, false), null);
});

test("member additions and removals advance the epoch monotonically", () => {
  assert.equal(nextGroupCryptoMembershipEpoch(1), 2);
  assert.equal(nextGroupCryptoMembershipEpoch(8), 9);
  assert.equal(membershipEpochUpdate(4, true), 5);
});

test("role-only changes do not rotate the membership epoch", () => {
  assert.equal(membershipEpochUpdate(7, false), null);
  assert.equal(membershipEpochUpdate(undefined, false), null);
});

test("rejects invalid, fractional, negative and exhausted epochs", () => {
  for (const value of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, "2"]) {
    assert.throws(() => currentGroupCryptoMembershipEpoch(value), TypeError);
  }
  assert.throws(
    () => nextGroupCryptoMembershipEpoch(Number.MAX_SAFE_INTEGER),
    RangeError,
  );
});

test("membership and epoch update atomically on the cryptoMembership child only", async () => {
  const legacyMembers = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    createdBy: "alice",
    members: { ...legacyMembers },
    cryptoMembership: { epoch: 4, members: { ...legacyMembers } },
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (members) => ({
      members: { ...members, carol: "member" },
      cryptoMembershipChanged: true,
      result: { added: "carol" },
    }),
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 5);
  assert.deepEqual(result.members, {
    alice: "admin",
    bob: "member",
    carol: "member",
  });
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 5,
    members: {
      alice: "admin",
      bob: "member",
      carol: "member",
    },
  });
  assert.deepEqual(fixture.group.members, legacyMembers);
  assert.deepEqual(fixture.writePaths, ["chats/group-1/cryptoMembership"]);
  assert.ok(fixture.readPaths.every((path) => path !== "chats/group-1"));
});

test("creator metadata updates separately after a canonical roster CAS", async () => {
  const legacyMembers = {
    alice: "admin",
    bob: "admin",
    carol: "member",
  };
  const fixture = groupFixture({
    type: "group",
    createdBy: "alice",
    members: { ...legacyMembers },
    cryptoMembership: { epoch: 3, members: { ...legacyMembers } },
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (members, currentChat) => {
      assert.equal(currentChat.createdBy, "alice");
      const next = { ...members };
      delete next.alice;
      return {
        members: next,
        cryptoMembershipChanged: true,
        createdBy: "bob",
      };
    },
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 4);
  assert.equal(fixture.group.createdBy, "bob");
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 4,
    members: { bob: "admin", carol: "member" },
  });
  assert.deepEqual(fixture.group.members, legacyMembers);
  assert.deepEqual(fixture.writePaths, [
    "chats/group-1/cryptoMembership",
    "chats/group-1/createdBy",
  ]);
});

test("role-only update writes the roster without advancing the epoch", async () => {
  const members = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    members: { ...members },
    cryptoMembership: { epoch: 6, members: { ...members } },
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (current) => ({
      members: { ...current, bob: "admin" },
      cryptoMembershipChanged: false,
    }),
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 6);
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 6,
    members: { alice: "admin", bob: "admin" },
  });
  assert.deepEqual(fixture.group.members, members);
  assert.deepEqual(fixture.writePaths, ["chats/group-1/cryptoMembership"]);
});

test("no-op membership requests do not write", async () => {
  const members = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    members,
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    () => ({ noChange: true, result: { added: false } }),
    fixture.fetchImpl,
  );

  assert.equal(result.changed, false);
  assert.equal(result.cryptoMembershipEpoch, 1);
  assert.equal(fixture.writes, 0);
  assert.deepEqual(fixture.group.members, members);
  assert.equal(fixture.group.cryptoMembership, undefined);
  assert.ok(fixture.readPaths.includes("chats/group-1/members"));
});

test("a stale ETag retries from the latest canonical membership snapshot", async () => {
  let changedElsewhere = false;
  const legacyMembers = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    members: { ...legacyMembers },
    cryptoMembership: { epoch: 2, members: { ...legacyMembers } },
  }, {
    onConditionalWrite: ({ group, writeNumber }) => {
      if (writeNumber !== 1 || changedElsewhere) return false;
      changedElsewhere = true;
      group.cryptoMembership = {
        epoch: 3,
        members: { ...group.cryptoMembership.members, carol: "member" },
      };
      return true;
    },
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (members) => ({
      members: { ...members, dave: "member" },
      cryptoMembershipChanged: true,
    }),
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 4);
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 4,
    members: {
      alice: "admin",
      bob: "member",
      carol: "member",
      dave: "member",
    },
  });
  assert.deepEqual(fixture.group.members, legacyMembers);
  assert.equal(fixture.writes, 2);
  assert.deepEqual(fixture.writePaths, [
    "chats/group-1/cryptoMembership",
    "chats/group-1/cryptoMembership",
  ]);
});

test("repeated membership ETag conflicts fail closed", async () => {
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
    cryptoMembership: {
      epoch: 1,
      members: { alice: "admin", bob: "member" },
    },
  }, {
    onConditionalWrite: () => true,
  });

  await assert.rejects(
    mutateGroupMembersWithEtag(
      environment,
      "admin-token",
      "group-1",
      (members) => ({
        members: { ...members, carol: "member" },
        cryptoMembershipChanged: true,
      }),
      fixture.fetchImpl,
    ),
    (error) => error instanceof Response && error.status === 409,
  );
  assert.equal(fixture.writes, 5);
});

test("malformed groups and invalid persisted epochs fail without writing", async () => {
  const malformed = groupFixture({
    type: "direct",
    members: { alice: "admin", bob: "member" },
  });
  await assert.rejects(
    mutateGroupMembersWithEtag(
      environment,
      "admin-token",
      "group-1",
      () => ({ members: {}, cryptoMembershipChanged: true }),
      malformed.fetchImpl,
    ),
    /Group membership state is invalid/,
  );

  const invalidEpoch = groupFixture({
    type: "group",
    cryptoMembership: {
      epoch: 2.5,
      members: { alice: "admin", bob: "member" },
    },
  });
  await assert.rejects(
    mutateGroupMembersWithEtag(
      environment,
      "admin-token",
      "group-1",
      (members) => ({
        members: { ...members, carol: "member" },
        cryptoMembershipChanged: true,
      }),
      invalidEpoch.fetchImpl,
    ),
    /epoch is invalid/,
  );
  assert.equal(invalidEpoch.writes, 0);
});

test("an authorized legacy member initializes the canonical snapshot at epoch one", async () => {
  const legacyMembers = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    members: { ...legacyMembers },
  });

  const result = await ensureGroupCryptoMembershipSnapshotWithEtag(
    environment,
    "admin-token",
    "group-1",
    "bob",
    fixture.fetchImpl,
  );

  assert.equal(result.epoch, 1);
  assert.equal(result.changed, true);
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 1,
    members: legacyMembers,
  });
  assert.deepEqual(fixture.group.members, legacyMembers);
  assert.deepEqual(fixture.writePaths, ["chats/group-1/cryptoMembership"]);
});

test("an existing crypto snapshot is authoritative over a stale legacy roster", async () => {
  const fixture = groupFixture({
    type: "group",
    members: {
      alice: "admin",
      bob: "member",
      carol: "member",
    },
    cryptoMembership: {
      epoch: 5,
      members: { alice: "admin", bob: "member" },
    },
  });

  const result = await ensureGroupCryptoMembershipSnapshotWithEtag(
    environment,
    "admin-token",
    "group-1",
    "alice",
    fixture.fetchImpl,
  );
  assert.equal(result.epoch, 5);
  assert.equal(result.changed, false);
  assert.deepEqual(result.members, { alice: "admin", bob: "member" });
  await assert.rejects(
    ensureGroupCryptoMembershipSnapshotWithEtag(
      environment,
      "admin-token",
      "group-1",
      "carol",
      fixture.fetchImpl,
    ),
    (error) => error instanceof Response && error.status === 403,
  );
  assert.equal(fixture.writes, 0);
});

test("removed or non-members cannot initialize a legacy snapshot", async () => {
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
  });

  await assert.rejects(
    ensureGroupCryptoMembershipSnapshotWithEtag(
      environment,
      "admin-token",
      "group-1",
      "mallory",
      fixture.fetchImpl,
    ),
    (error) => error instanceof Response && error.status === 403,
  );
  assert.equal(fixture.writes, 0);
});

test("initialization retries after a concurrent canonical membership change", async () => {
  const legacyMembers = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    members: { ...legacyMembers },
  }, {
    onConditionalWrite: ({ group, writeNumber }) => {
      if (writeNumber !== 1) return false;
      group.cryptoMembership = {
        epoch: 2,
        members: { ...legacyMembers, carol: "member" },
      };
      return true;
    },
  });

  const result = await ensureGroupCryptoMembershipSnapshotWithEtag(
    environment,
    "admin-token",
    "group-1",
    "alice",
    fixture.fetchImpl,
  );

  assert.equal(result.epoch, 2);
  assert.equal(result.changed, false);
  assert.deepEqual(result.members, {
    alice: "admin",
    bob: "member",
    carol: "member",
  });
  assert.equal(fixture.writes, 1);
});

test("malformed canonical snapshot epochs fail closed", async () => {
  const fixture = groupFixture({
    type: "group",
    cryptoMembership: {
      epoch: 1.25,
      members: { alice: "admin", bob: "member" },
    },
  });

  await assert.rejects(
    ensureGroupCryptoMembershipSnapshotWithEtag(
      environment,
      "admin-token",
      "group-1",
      "alice",
      fixture.fetchImpl,
    ),
    /epoch is invalid/,
  );
  assert.equal(fixture.writes, 0);
});

test("group membership operations never request the chat root or messages subtree", async () => {
  const legacyMembers = { alice: "admin", bob: "member" };
  const fixture = groupFixture({
    type: "group",
    createdBy: "alice",
    members: { ...legacyMembers },
    cryptoMembership: { epoch: 4, members: { ...legacyMembers } },
    messages: { "legacy-1": { text: "must not be fetched" } },
  });

  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (members) => ({
      members: { ...members, carol: "member" },
      cryptoMembershipChanged: true,
    }),
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 5);
  assert.ok(fixture.readPaths.every((path) => !path.endsWith("/messages") && path !== "chats/group-1"));
  assert.deepEqual(fixture.writePaths, ["chats/group-1/cryptoMembership"]);
  assert.deepEqual(fixture.group.members, legacyMembers);
});
