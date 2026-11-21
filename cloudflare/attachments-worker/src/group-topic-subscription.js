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

/**
 * Returns the previously registered token only when it differs from the
 * current token. The caller must clean this token before replacing its mirror.
 */
export function previousGroupNotificationToken(previousToken, currentToken) {
  const previous = typeof previousToken === "string" ? previousToken.trim() : "";
  const current = typeof currentToken === "string" ? currentToken.trim() : "";
  return previous && previous !== current ? previous : "";
}
