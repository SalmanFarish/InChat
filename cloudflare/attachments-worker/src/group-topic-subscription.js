/**
 * Checks whether a group-topic subscription is still authorized after the
 * asynchronous FCM topic operations have completed.
 */
export function isCurrentGroupTopicSubscription({
  chat,
  uid,
  requestedToken,
  currentToken,
}) {
  if (
    !chat ||
    typeof chat !== "object" ||
    chat.type !== "group" ||
    typeof uid !== "string" ||
    uid.trim() === "" ||
    typeof requestedToken !== "string" ||
    requestedToken.trim() === "" ||
    typeof currentToken !== "string" ||
    currentToken.trim() !== requestedToken.trim()
  ) {
    return false;
  }

  const members = chat.members;
  if (!members || typeof members !== "object" || Array.isArray(members)) {
    return false;
  }

  const role = members[uid];
  return typeof role === "string" && role.trim() !== "";
}
