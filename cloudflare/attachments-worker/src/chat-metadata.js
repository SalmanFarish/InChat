/**
 * Metadata-only conversation loader for privileged Worker paths.
 *
 * Never request chats/{chatId} as a whole object: historical installs may
 * still have a plaintext messages child there. E2EE group membership is
 * authoritative once cryptoMembership exists; legacy members is read only
 * when initializing older groups that have no snapshot yet.
 */
export async function loadChatMetadata(adminDatabaseRequest, env, adminToken, chatId) {
  const type = await adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/type");
  if (type === "group") {
    const [name, createdBy, cryptoMembership] = await Promise.all([
      adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/name"),
      adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/createdBy"),
      adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/cryptoMembership"),
    ]);
    let members = null;
    if (cryptoMembership && typeof cryptoMembership === "object" &&
        !Array.isArray(cryptoMembership)) {
      members = cryptoMembership.members;
    } else {
      members = await adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/members");
    }
    return {
      chatId,
      type,
      name: typeof name === "string" ? name : "",
      createdBy: typeof createdBy === "string" ? createdBy : "",
      members: members && typeof members === "object" && !Array.isArray(members) ? members : {},
      cryptoMembership: cryptoMembership && typeof cryptoMembership === "object" &&
        !Array.isArray(cryptoMembership) ? cryptoMembership : null,
    };
  }

  const [participantA, participantB] = await Promise.all([
    adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/participantA"),
    adminDatabaseRequest(env, adminToken, "chats/" + chatId + "/participantB"),
  ]);
  if (participantA == null && participantB == null) return null;
  return {
    chatId,
    type: type || "direct",
    participantA: participantA || "",
    participantB: participantB || "",
  };
}
