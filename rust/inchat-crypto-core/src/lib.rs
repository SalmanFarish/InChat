#![forbid(unsafe_code)]

//! Isolated cryptographic prototype for InChat.
//!
//! This crate is deliberately not connected to Android messaging yet.
//! It currently provides only the small transport adapter needed to preserve
//! the message-type discriminator when moving Olm messages across a platform
//! boundary. It does not define InChat's final network envelope or key schema.

use vodozemac::olm::OlmMessage;

/// The exact upstream cryptography engine version used by this prototype.
pub const VODOZEMAC_VERSION: &str = "0.11.2";

/// The two parts required to reconstruct an Olm message across an FFI boundary.
///
/// The caller must persist/transmit the type discriminator together with the
/// ciphertext. Do not infer the message type from ciphertext bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OlmMessageParts {
    pub message_type: usize,
    pub ciphertext: Vec<u8>,
}

impl From<OlmMessage> for OlmMessageParts {
    fn from(message: OlmMessage) -> Self {
        let (message_type, ciphertext) = message.to_parts();

        Self {
            message_type,
            ciphertext,
        }
    }
}

impl TryFrom<OlmMessageParts> for OlmMessage {
    type Error = vodozemac::DecodeError;

    fn try_from(parts: OlmMessageParts) -> Result<Self, Self::Error> {
        OlmMessage::from_parts(parts.message_type, &parts.ciphertext)
    }
}

#[cfg(test)]
mod tests {
    use super::OlmMessageParts;
    use vodozemac::olm::{Account, OlmMessage, SessionConfig};

    fn cross_transport_boundary(message: OlmMessage) -> OlmMessage {
        let parts = OlmMessageParts::from(message);
        OlmMessage::try_from(parts).expect("valid Olm message parts should decode")
    }

    #[test]
    fn two_independent_devices_establish_and_exchange_olm_messages() {
        // These are two independent device identities, not shared account keys.
        let alice = Account::new();
        let mut bob = Account::new();

        bob.generate_one_time_keys(1);
        let bob_one_time_key = *bob
            .one_time_keys()
            .values()
            .next()
            .expect("Bob generated one one-time key");

        let mut alice_session = alice
            .create_outbound_session(
                SessionConfig::version_1(),
                bob.curve25519_key(),
                bob_one_time_key,
            )
            .expect("Alice should establish an outbound session");

        // Simulate publishing Bob's public one-time key to an untrusted server.
        // The private one-time key remains in Bob's local Account.
        bob.mark_keys_as_published();

        let first_plaintext = "InChat encrypted direct-message test";
        let first_message = alice_session
            .encrypt(first_plaintext)
            .expect("Alice should encrypt the first message");

        // The first Olm message must carry the pre-key handshake information.
        let first_message = cross_transport_boundary(first_message);
        let pre_key_message = match first_message {
            OlmMessage::PreKey(message) => message,
            OlmMessage::Normal(_) => {
                panic!("the first outbound message should be a pre-key message")
            }
        };

        let inbound = bob
            .create_inbound_session(
                SessionConfig::version_1(),
                alice.curve25519_key(),
                &pre_key_message,
            )
            .expect("Bob should establish the matching inbound session");

        assert_eq!(inbound.plaintext, first_plaintext.as_bytes());
        let mut bob_session = inbound.session;

        // Bob's reply confirms that both devices derived a matching session.
        let reply_plaintext = "Reply decrypted by the other device";
        let reply = bob_session
            .encrypt(reply_plaintext)
            .expect("Bob should encrypt a reply");
        let reply = cross_transport_boundary(reply);

        let alice_received = alice_session
            .decrypt(&reply)
            .expect("Alice should decrypt Bob's reply");
        assert_eq!(alice_received, reply_plaintext.as_bytes());

        // Check the established session in the opposite direction as well.
        let follow_up_plaintext = "The established session works both ways";
        let follow_up = alice_session
            .encrypt(follow_up_plaintext)
            .expect("Alice should encrypt a follow-up");
        let follow_up = cross_transport_boundary(follow_up);

        let bob_received = bob_session
            .decrypt(&follow_up)
            .expect("Bob should decrypt Alice's follow-up");
        assert_eq!(bob_received, follow_up_plaintext.as_bytes());
    }

    #[test]
    fn invalid_olm_message_type_is_rejected() {
        let invalid = OlmMessageParts {
            message_type: usize::MAX,
            ciphertext: vec![0, 1, 2],
        };

        assert!(OlmMessage::try_from(invalid).is_err());
    }
}
