import test from "node:test";
import assert from "node:assert/strict";
import {
  INITIAL_GROUP_CRYPTO_MEMBERSHIP_EPOCH,
  currentGroupCryptoMembershipEpoch,
  nextGroupCryptoMembershipEpoch,
  membershipEpochUpdate,
} from "../src/group-crypto-membership.js";

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
