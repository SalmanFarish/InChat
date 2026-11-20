import test from "node:test";
import assert from "node:assert/strict";
import {
  INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH,
  currentGroupCryptoMembershipEpoch,
  nextGroupCryptoMembershipEpoch,
  membershipEpochUpdate,
  mutateGroupMembersWithEtag,
} from "../src/group-crypto-membership.js";

function groupFixture(group, { onConditionalWrite } = {}) {
  let version = 1;
  let reads = 0;
  let writes = 0;
  const patches = [];
  const fetchImpl = async (_url, options = {}) => {
    const method = options.method || "GET";
    if (method === "GET") {
      reads += 1;
      return new Response(JSON.stringify(structuredClone(group)), {
        status: 200,
        headers: { ETag: '"v' + version + '"' },
      });
    }
    assert.equal(method, "PATCH");
    writes += 1;
    const expected = '"v' + version + '"';
    if (options.headers["If-Match"] !== expected) {
      return new Response("precondition failed", { status: 412 });
    }
    if (onConditionalWrite) {
      const shouldReject = await onConditionalWrite({
        group,
        version,
        writeNumber: writes,
      });
      if (shouldReject) {
        version += 1;
        return new Response("precondition failed", { status: 412 });
      }
    }
    const patch = JSON.parse(options.body);
    patches.push(patch);
    Object.assign(group, patch);
    version += 1;
    return new Response("null", { status: 200 });
  };
  return {
    group,
    patches,
    fetchImpl,
    get reads() { return reads; },
    get writes() { return writes; },
  };
}

const environment = { FIREBASE_DATABASE_URL: "https://demo-inchat.firebaseio.com/" };

test("new group membership starts at crypto epoch one", () => {
  assert.equal(INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH, 1);
  assert.equal(currentGroupCryptoMembershipEpoch(null), 1);
  assert.equal(currentGroupCryptoMembershipEpoch(undefined), 1);
});

test("legacy group advances from implicit baseline one on first real membership change", () => {
  assert.equal(nextGroupCryptoMembershipEpoch(undefined), 2);
  assert.equal(membershipEpochUpdate(null, true), 2);
});

test("member additions and removals advance the epoch monotonically", () => {
  assert.equal(nextGroupCryptoMembershipEpoch(1), 2);
  assert.equal(nextGroupCryptoMembershipEpoch(8), 9);
  assert.equal(membershipEpochUpdate(4, true), 5);
});

test("role changes and no-op membership requests do not rotate the epoch", () => {
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

test("commits member map and next epoch together under one root ETag", async () => {
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
    cryptoMembership: { epoch: 4, members: { alice: "admin", bob: "member" } },
    name: "test",
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
  assert.deepEqual(fixture.group.members, {
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
  assert.deepEqual(fixture.patches, [{
    members: {
      alice: "admin",
      bob: "member",
      carol: "member",
    },
    cryptoMembership: {
      epoch: 5,
      members: {
        alice: "admin",
        bob: "member",
        carol: "member",
      },
    },
  }]);
});

test("role-only update patches members without advancing or inventing an epoch", async () => {
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
    cryptoMembership: { epoch: 6, members: { alice: "admin", bob: "member" } },
  });
  const result = await mutateGroupMembersWithEtag(
    environment,
    "admin-token",
    "group-1",
    (members) => ({
      members: { ...members, bob: "admin" },
      cryptoMembershipChanged: false,
    }),
    fixture.fetchImpl,
  );

  assert.equal(result.cryptoMembershipEpoch, 6);
  assert.deepEqual(fixture.group.cryptoMembership, {
    epoch: 6,
    members: { alice: "admin", bob: "admin" },
  });
  assert.deepEqual(fixture.patches, [{
    members: { alice: "admin", bob: "admin" },
    cryptoMembership: {
      epoch: 6,
      members: { alice: "admin", bob: "admin" },
    },
  }]);
});

test("no-op membership request makes no write and keeps the effective legacy epoch", async () => {
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
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
  assert.equal(fixture.reads, 1);
  assert.equal(fixture.group.cryptoMembershipEpoch, undefined);
});

test("retries a stale ETag against the latest membership and epoch without reusing an epoch", async () => {
  let changedElsewhere = false;
  const fixture = groupFixture({
    type: "group",
    members: { alice: "admin", bob: "member" },
    cryptoMembership: {
      epoch: 2,
      members: { alice: "admin", bob: "member" },
    },
  }, {
    onConditionalWrite: ({ group, writeNumber }) => {
      if (writeNumber === 1 && !changedElsewhere) {
        changedElsewhere = true;
        group.members = { ...group.members, carol: "member" };
        group.cryptoMembership = {
          epoch: 3,
          members: group.members,
        };
        return true;
      }
      return false;
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

  assert.equal(fixture.reads, 2);
  assert.equal(fixture.writes, 2);
  assert.equal(result.cryptoMembershipEpoch, 4);
  assert.equal(fixture.group.cryptoMembership.epoch, 4);
  assert.deepEqual(fixture.group.members, {
    alice: "admin",
    bob: "member",
    carol: "member",
    dave: "member",
  });
  assert.equal(fixture.patches.length, 1);
  assert.equal(fixture.patches[0].cryptoMembership.epoch, 4);
  assert.deepEqual(fixture.patches[0].cryptoMembership.members, fixture.group.members);
});

test("fails closed after repeated membership ETag conflicts", async () => {
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
  assert.equal(fixture.patches.length, 0);
});

test("rejects malformed group state and invalid persisted epoch without writing", async () => {
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
    members: { alice: "admin", bob: "member" },
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
