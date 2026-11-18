import test from "node:test";
import assert from "node:assert/strict";
import {
  hasReadThrough,
  shouldIncrementGroupUnread,
} from "../src/group-unread.js";

test("a message newer than the read cursor increments unread", () => {
  assert.equal(shouldIncrementGroupUnread(200, 150), true);
});

test("a message at the read cursor is already read", () => {
  assert.equal(shouldIncrementGroupUnread(200, 200), false);
  assert.equal(hasReadThrough(200, 200), true);
});

test("a delayed older message does not restore unread state", () => {
  assert.equal(shouldIncrementGroupUnread(150, 200), false);
});

test("a missing read cursor treats a valid message as unread", () => {
  assert.equal(shouldIncrementGroupUnread(200, null), true);
  assert.equal(shouldIncrementGroupUnread(200, undefined), true);
});

test("an invalid read cursor does not suppress a valid new message", () => {
  assert.equal(shouldIncrementGroupUnread(200, "not-a-timestamp"), true);
});

test("an invalid or non-positive message timestamp is never incremented", () => {
  assert.equal(shouldIncrementGroupUnread(Number.NaN, 0), false);
  assert.equal(shouldIncrementGroupUnread("bad", 0), false);
  assert.equal(shouldIncrementGroupUnread(0, 0), false);
});
