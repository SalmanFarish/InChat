//! Isolated Megolm primitives for end-to-end encrypted group conversations.
//!
//! Session keys are sensitive and must be transported only inside an
//! authenticated Olm message. Serialized pickles are secret state and must be
//! encrypted with the Android Keystore-backed native state store before they
//! are persisted. This module does not implement group membership, epoch
//! authorization, replay persistence, or Firebase/UI integration; those belong
//! to the authenticated application protocol surrounding this primitive.

use vodozemac::megolm::{
    GroupSession, GroupSessionPickle, InboundGroupSession, InboundGroupSessionPickle,
    MegolmMessage, SessionKey,
};

/// Maximum application plaintext accepted by the isolated group-message API.
pub const MAX_GROUP_PLAINTEXT_BYTES: usize = 64 * 1024;

/// Maximum serialized ciphertext accepted by the isolated group-message API.
pub const MAX_GROUP_CIPHERTEXT_BYTES: usize = 1024 * 1024;

/// Maximum serialized native group-session state accepted from local storage.
pub const MAX_GROUP_SESSION_STATE_BYTES: usize = 8 * 1024 * 1024;

/// A Megolm ciphertext plus the cryptographic message index needed for
/// application-level replay detection.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EncryptedGroupMessage {
    /// Megolm's signing-key-derived session identifier.
    pub session_id: String,
    /// The message's monotonic index within this sender session.
    pub message_index: u32,
    /// Binary Megolm message including its MAC and Ed25519 signature.
    pub ciphertext: Vec<u8>,
}

/// A sender-side Megolm session for one sender device and one group epoch.
pub struct OutboundGroupSession {
    session: GroupSession,
}

impl OutboundGroupSession {
    /// Create a new independent Megolm sender session.
    ///
    /// A fresh session must be created for every group membership epoch. Never
    /// continue an old outbound session after a member is added or removed.
    pub fn new() -> Self {
        Self {
            session: GroupSession::new(Default::default()),
        }
    }

    /// Get the public session identifier.
    pub fn session_id(&self) -> String {
        self.session.session_id()
    }

    /// Get the next message index.
    pub fn message_index(&self) -> u32 {
        self.session.message_index()
    }

    /// Export the signed session key for delivery to current group members.
    ///
    /// The returned bytes contain decryption capability. Callers MUST encrypt
    /// them separately to every authorized member device using an authenticated
    /// Olm session; they must never be published to a public directory or relay.
    pub fn export_session_key(&self) -> Vec<u8> {
        self.session.session_key().to_bytes()
    }

    /// Encrypt one message and return its authenticated Megolm wire bytes.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<EncryptedGroupMessage, &'static str> {
        if plaintext.is_empty() || plaintext.len() > MAX_GROUP_PLAINTEXT_BYTES {
            return Err("Group plaintext is empty or exceeds the 64 KiB limit");
        }
        if self.session.message_index() == u32::MAX {
            return Err("Megolm message index is exhausted; rotate the group epoch");
        }

        let session_id = self.session.session_id();
        let message = self.session.encrypt(plaintext);
        let message_index = message.message_index();
        let ciphertext = message.to_bytes();
        if ciphertext.len() > MAX_GROUP_CIPHERTEXT_BYTES {
            return Err("Megolm ciphertext exceeds the size limit");
        }

        Ok(EncryptedGroupMessage {
            session_id,
            message_index,
            ciphertext,
        })
    }

    /// Serialize the outbound ratchet for encrypted local persistence.
    ///
    /// The caller must immediately encrypt this buffer through the
    /// Android Keystore-backed native state store and zeroize the temporary
    /// buffer. It is not a portable wire format and must not be sent to peers.
    pub fn serialize_for_encrypted_storage(&self) -> Result<Vec<u8>, &'static str> {
        let serialized = serde_json::to_vec(&self.session.pickle())
            .map_err(|_| "Could not serialize Megolm outbound session")?;
        if serialized.len() > MAX_GROUP_SESSION_STATE_BYTES {
            return Err("Megolm outbound state exceeds the storage limit");
        }
        Ok(serialized)
    }

    /// Restore an outbound ratchet from locally stored, already decrypted state.
    pub fn restore_from_encrypted_storage(state: &[u8]) -> Result<Self, &'static str> {
        if state.is_empty() || state.len() > MAX_GROUP_SESSION_STATE_BYTES {
            return Err("Megolm outbound state has an invalid size");
        }
        let pickle: GroupSessionPickle = serde_json::from_slice(state)
            .map_err(|_| "Megolm outbound state could not be decoded")?;
        Ok(Self {
            session: GroupSession::from_pickle(pickle),
        })
    }
}

impl Default for OutboundGroupSession {
    fn default() -> Self {
        Self::new()
    }
}

/// A recipient-side Megolm session, constructed from a signed key delivered
/// through an authenticated Olm channel.
pub struct InboundGroupSession {
    session: InboundGroupSession,
}

impl InboundGroupSession {
    /// Authenticate and import a sender's signed Megolm session key.
    pub fn from_session_key(session_key: &[u8]) -> Result<Self, &'static str> {
        if session_key.is_empty() || session_key.len() > 1024 {
            return Err("Megolm session key has an invalid size");
        }
        let key = SessionKey::from_bytes(session_key)
            .map_err(|_| "Megolm session key signature or encoding is invalid")?;
        Ok(Self {
            session: InboundGroupSession::new(&key, Default::default()),
        })
    }

    /// Get the sender session identifier bound to this imported key.
    pub fn session_id(&self) -> String {
        self.session.session_id()
    }

    /// Get the first message index this imported key can decrypt.
    ///
    /// Exporting a key after a sender has already sent messages intentionally
    /// prevents the new recipient from decrypting earlier messages in the epoch.
    pub fn first_known_index(&self) -> u32 {
        self.session.first_known_index()
    }

    /// Decrypt and authenticate one Megolm ciphertext.
    ///
    /// The returned message index must be durably checked against the
    /// application replay ledger before the plaintext is committed or shown.
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<(Vec<u8>, u32), &'static str> {
        if ciphertext.is_empty() || ciphertext.len() > MAX_GROUP_CIPHERTEXT_BYTES {
            return Err("Megolm ciphertext has an invalid size");
        }
        let message = MegolmMessage::from_bytes(ciphertext)
            .map_err(|_| "Megolm ciphertext has an invalid encoding")?;
        let decrypted = self.session.decrypt(&message)
            .map_err(|_| "Megolm ciphertext authentication or decryption failed")?;
        Ok((decrypted.plaintext, decrypted.message_index))
    }

    /// Serialize the inbound ratchet for encrypted local persistence.
    pub fn serialize_for_encrypted_storage(&self) -> Result<Vec<u8>, &'static str> {
        let serialized = serde_json::to_vec(&self.session.pickle())
            .map_err(|_| "Could not serialize Megolm inbound session")?;
        if serialized.len() > MAX_GROUP_SESSION_STATE_BYTES {
            return Err("Megolm inbound state exceeds the storage limit");
        }
        Ok(serialized)
    }

    /// Restore an inbound ratchet from locally stored, already decrypted state.
    pub fn restore_from_encrypted_storage(state: &[u8]) -> Result<Self, &'static str> {
        if state.is_empty() || state.len() > MAX_GROUP_SESSION_STATE_BYTES {
            return Err("Megolm inbound state has an invalid size");
        }
        let pickle: InboundGroupSessionPickle = serde_json::from_slice(state)
            .map_err(|_| "Megolm inbound state could not be decoded")?;
        Ok(Self {
            session: InboundGroupSession::from_pickle(pickle),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{InboundGroupSession, OutboundGroupSession};

    #[test]
    fn authorized_group_member_decrypts_messages_and_receives_indices() {
        let mut sender = OutboundGroupSession::new();
        let recipient_key = sender.export_session_key();
        let mut recipient = InboundGroupSession::from_session_key(&recipient_key).unwrap();

        let first = sender.encrypt(b"group message one").unwrap();
        let second = sender.encrypt(b"group message two").unwrap();

        let (plaintext, index) = recipient.decrypt(&second.ciphertext).unwrap();
        assert_eq!(plaintext, b"group message two");
        assert_eq!(index, 1);

        let (plaintext, index) = recipient.decrypt(&first.ciphertext).unwrap();
        assert_eq!(plaintext, b"group message one");
        assert_eq!(index, 0);
        assert_eq!(recipient.session_id(), sender.session_id());
    }

    #[test]
    fn member_added_after_sending_cannot_decrypt_earlier_epoch_messages() {
        let mut sender = OutboundGroupSession::new();
        let earlier = sender.encrypt(b"before member joined").unwrap();

        let late_member_key = sender.export_session_key();
        let mut late_member = InboundGroupSession::from_session_key(&late_member_key).unwrap();
        assert_eq!(late_member.first_known_index(), 1);

        let later = sender.encrypt(b"after member joined").unwrap();
        let (plaintext, index) = late_member.decrypt(&later.ciphertext).unwrap();
        assert_eq!(plaintext, b"after member joined");
        assert_eq!(index, 1);

        assert!(late_member.decrypt(&earlier.ciphertext).is_err());
    }

    #[test]
    fn modified_session_key_is_rejected() {
        let sender = OutboundGroupSession::new();
        let mut key = sender.export_session_key();
        let last = key.len() - 1;
        key[last] ^= 0x01;

        assert!(InboundGroupSession::from_session_key(&key).is_err());
    }

    #[test]
    fn modified_message_signature_is_rejected() {
        let mut sender = OutboundGroupSession::new();
        let mut recipient = InboundGroupSession::from_session_key(&sender.export_session_key()).unwrap();
        let mut message = sender.encrypt(b"authentic group message").unwrap();
        let last = message.ciphertext.len() - 1;
        message.ciphertext[last] ^= 0x01;

        assert!(recipient.decrypt(&message.ciphertext).is_err());
    }

    #[test]
    fn outbound_pickle_restores_sender_ratchet_and_inbound_can_continue() {
        let mut sender = OutboundGroupSession::new();
        let mut recipient = InboundGroupSession::from_session_key(&sender.export_session_key()).unwrap();
        let first = sender.encrypt(b"before sender restart").unwrap();
        assert_eq!(recipient.decrypt(&first.ciphertext).unwrap().0, b"before sender restart");

        let state = sender.serialize_for_encrypted_storage().unwrap();
        let mut restored_sender = OutboundGroupSession::restore_from_encrypted_storage(&state).unwrap();
        assert_eq!(restored_sender.session_id(), sender.session_id());
        assert_eq!(restored_sender.message_index(), sender.message_index());

        let second = restored_sender.encrypt(b"after sender restart").unwrap();
        assert_eq!(recipient.decrypt(&second.ciphertext).unwrap().0, b"after sender restart");
    }

    #[test]
    fn inbound_pickle_restores_recipient_ratchet() {
        let mut sender = OutboundGroupSession::new();
        let mut recipient = InboundGroupSession::from_session_key(&sender.export_session_key()).unwrap();
        let first = sender.encrypt(b"before recipient restart").unwrap();
        assert_eq!(recipient.decrypt(&first.ciphertext).unwrap().0, b"before recipient restart");

        let state = recipient.serialize_for_encrypted_storage().unwrap();
        let mut restored_recipient = InboundGroupSession::restore_from_encrypted_storage(&state).unwrap();
        let second = sender.encrypt(b"after recipient restart").unwrap();
        assert_eq!(restored_recipient.decrypt(&second.ciphertext).unwrap().0, b"after recipient restart");
    }

    #[test]
    fn new_epoch_uses_a_new_signing_identity_and_rejects_old_members() {
        let old_sender = OutboundGroupSession::new();
        let mut old_member = InboundGroupSession::from_session_key(&old_sender.export_session_key()).unwrap();

        let new_sender = OutboundGroupSession::new();
        assert_ne!(old_sender.session_id(), new_sender.session_id());

        let new_epoch_message = {
            let mut next = new_sender;
            next.encrypt(b"new membership epoch").unwrap()
        };
        assert!(old_member.decrypt(&new_epoch_message.ciphertext).is_err());
    }

    #[test]
    fn empty_or_oversized_group_plaintext_is_rejected_before_ratchet_advance() {
        let mut sender = OutboundGroupSession::new();
        assert!(sender.encrypt(b"").is_err());
        assert_eq!(sender.message_index(), 0);

        let oversized = vec![0_u8; super::super::MAX_GROUP_PLAINTEXT_BYTES + 1];
        assert!(sender.encrypt(&oversized).is_err());
        assert_eq!(sender.message_index(), 0);
    }
}
