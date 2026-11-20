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
