#![deny(unsafe_op_in_unsafe_fn)]

//! Isolated cryptographic prototype for InChat.
//!
//! This crate is not connected to message sending, receiving, or Firebase.
//! The JNI surface intentionally exposes only a version string and a local
//! two-device Olm self-test. It does not export account private keys or
//! serialized ratchet state to Kotlin.

use jni::objects::JObject;
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use vodozemac::olm::{Account, OlmMessage, SessionConfig};

/// The exact upstream cryptography engine version used by this prototype.
pub const VODOZEMAC_VERSION: &str = "0.11.2";

/// The two parts required to reconstruct an Olm message across a transport boundary.
///
/// The caller must preserve the message-type discriminator together with the
/// ciphertext. It must never infer the type from ciphertext bytes.
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

fn cross_transport_boundary(
    message: OlmMessage,
) -> Result<OlmMessage, &'static str> {
    let parts = OlmMessageParts::from(message);
    OlmMessage::try_from(parts).map_err(|_| "Olm message decoding failed")
}

/// Run a local two-device Olm handshake and exchange messages in both directions.
///
/// No network, Android account, Firebase user, or persistent key material is used.
pub fn run_olm_self_test() -> Result<(), &'static str> {
    let alice = Account::new();
    let mut bob = Account::new();

    bob.generate_one_time_keys(1);
    let bob_one_time_key = bob
        .one_time_keys()
        .values()
        .next()
        .copied()
        .ok_or("Bob did not generate a one-time key")?;

    let mut alice_session = alice
        .create_outbound_session(
            SessionConfig::version_1(),
            bob.curve25519_key(),
            bob_one_time_key,
        )
        .map_err(|_| "Alice could not create an outbound session")?;

    // In a real flow the public one-time key is published to the key directory.
    // Bob retains the private key locally.
    bob.mark_keys_as_published();

    let first_plaintext = "InChat encrypted direct-message test";
    let first_message = alice_session
        .encrypt(first_plaintext)
        .map_err(|_| "Alice could not encrypt the initial message")?;

    let first_message = cross_transport_boundary(first_message)?;
    let pre_key_message = match first_message {
        OlmMessage::PreKey(message) => message,
        OlmMessage::Normal(_) => {
            return Err("The first outbound message was not a pre-key message");
        }
    };

    let inbound = bob
        .create_inbound_session(
            SessionConfig::version_1(),
            alice.curve25519_key(),
            &pre_key_message,
        )
        .map_err(|_| "Bob could not create the inbound session")?;

    if inbound.plaintext.as_slice() != first_plaintext.as_bytes() {
        return Err("Bob decrypted different initial plaintext");
    }

    let mut bob_session = inbound.session;

    let reply_plaintext = "Reply decrypted by the other device";
    let reply = bob_session
        .encrypt(reply_plaintext)
        .map_err(|_| "Bob could not encrypt the reply")?;
    let reply = cross_transport_boundary(reply)?;

    let alice_received = alice_session
        .decrypt(&reply)
        .map_err(|_| "Alice could not decrypt Bob's reply")?;

    if alice_received.as_slice() != reply_plaintext.as_bytes() {
        return Err("Alice decrypted different reply plaintext");
    }

    let follow_up_plaintext = "The established session works both ways";
    let follow_up = alice_session
        .encrypt(follow_up_plaintext)
        .map_err(|_| "Alice could not encrypt the follow-up")?;
    let follow_up = cross_transport_boundary(follow_up)?;

    let bob_received = bob_session
        .decrypt(&follow_up)
        .map_err(|_| "Bob could not decrypt Alice's follow-up")?;

    if bob_received.as_slice() != follow_up_plaintext.as_bytes() {
        return Err("Bob decrypted different follow-up plaintext");
    }

    Ok(())
}

// This symbol is intentionally narrow: it verifies that the native library
// can invoke the real vodozemac implementation without exposing key material.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeVodozemacVersion<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match env.new_string(VODOZEMAC_VERSION) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeOlmSelfTest(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(run_olm_self_test)) {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(_)) | Err(_) => JNI_FALSE,
    }
}

#[cfg(test)]
mod tests {
    use super::{run_olm_self_test, OlmMessageParts};
    use vodozemac::olm::OlmMessage;

    #[test]
    fn two_independent_devices_establish_and_exchange_olm_messages() {
        run_olm_self_test().expect("two-device Olm exchange should succeed");
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
