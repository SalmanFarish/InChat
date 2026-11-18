function asTimestamp(value) {
  if (typeof value !== "number" && typeof value !== "string") {
    return Number.NaN;
  }
  const timestamp = Number(value);
  return Number.isFinite(timestamp) ? timestamp : Number.NaN;
}

export function hasReadThrough(readTimestamp, messageTimestamp) {
  const messageTime = asTimestamp(messageTimestamp);
  const readTime = readTimestamp == null ? 0 : asTimestamp(readTimestamp);
  return (
    Number.isFinite(messageTime) &&
    messageTime > 0 &&
    Number.isFinite(readTime) &&
    readTime >= messageTime
  );
}

export function shouldIncrementGroupUnread(messageTimestamp, readTimestamp) {
  const messageTime = asTimestamp(messageTimestamp);
  if (!Number.isFinite(messageTime) || messageTime <= 0) return false;
  return !hasReadThrough(readTimestamp, messageTime);
}

export function membersReadThroughLatestMessage(
  memberIds,
  readCursors,
  latestMessageTimestamp,
) {
  if (!Array.isArray(memberIds)) return [];
  const cursors =
    readCursors && typeof readCursors === "object" && !Array.isArray(readCursors) ?
      readCursors :
      {};
  return memberIds.filter((memberId) =>
    hasReadThrough(cursors[memberId], latestMessageTimestamp)
  );
}
