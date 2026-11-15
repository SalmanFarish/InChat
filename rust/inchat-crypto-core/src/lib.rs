#![deny(unsafe_op_in_unsafe_fn)]

//! Isolated cryptographic prototype for InChat.
//!
//! This crate is not connected to message sending, receiving, or Firebase.
//! The JNI surface intentionally exposes only a version string and a local
//! two-device Olm self-test. It does not export account private keys or
//! serialized ratchet state to Kotlin.

use jni::objects::{JByteArray, JObject, JString, JValue};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use vodozemac::olm::{Account, AccountPickle, OlmMessage, SessionConfig};
use zeroize::Zeroize;

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

fn cross_transport_boundary(message: OlmMessage) -> Result<OlmMessage, &'static str> {
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

// The state blob has its own version independent of the Android Keystore
// envelope. vodozemac's Serde pickle format is not a stable wire format.
const ACCOUNT_STATE_HEADER: &[u8; 6] = b"ICACC\x01";
const MAX_ACCOUNT_STATE_BYTES: usize = 8 * 1024 * 1024;

fn account_identity_fingerprint(account: &Account) -> String {
    let identity = account.identity_keys();
    format!(
        "{}:{}",
        identity.ed25519.to_base64(),
        identity.curve25519.to_base64()
    )
}

fn persist_test_account<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
) -> Result<String, &'static str> {
    let mut account = Account::new();
    account.generate_one_time_keys(5);
    account.generate_fallback_key();

    let expected_identity = account_identity_fingerprint(&account);
    let mut serialized =
        serde_json::to_vec(&account.pickle()).map_err(|_| "Olm account serialization failed")?;

    let mut state_blob = Vec::with_capacity(ACCOUNT_STATE_HEADER.len() + serialized.len());
    state_blob.extend_from_slice(ACCOUNT_STATE_HEADER);
    state_blob.extend_from_slice(&serialized);
    serialized.zeroize();

    if state_blob.len() > MAX_ACCOUNT_STATE_BYTES {
        state_blob.zeroize();
        return Err("Olm account state exceeds the storage limit");
    }

    // The byte array is passed directly to the store's native-only method.
    // CryptoStateStore encrypts it with Android Keystore and zeroes the array.
    let java_state_result = env.byte_array_from_slice(&state_blob);
    state_blob.zeroize();
    let java_state =
        java_state_result.map_err(|_| "Could not create temporary Java state buffer")?;

    env.call_method(
        store,
        "writeForNative",
        "([B)V",
        &[JValue::Object(java_state.as_ref())],
    )
    .map_err(|_| "Android Keystore could not persist the Olm account")?;

    Ok(expected_identity)
}

fn restore_test_account<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
) -> Result<String, &'static str> {
    let stored_value = env
        .call_method(store, "readForNative", "()[B", &[])
        .map_err(|_| "Android Keystore could not read the Olm account")?;
    let stored_object = stored_value
        .l()
        .map_err(|_| "Crypto state store returned an invalid value")?;
    if stored_object.is_null() {
        return Err("No saved Olm account state exists");
    }

    let stored_array = JByteArray::from(stored_object);
    let length = env
        .get_array_length(&stored_array)
        .map_err(|_| "Could not inspect saved Olm account state")? as usize;
    if length < ACCOUNT_STATE_HEADER.len() || length > MAX_ACCOUNT_STATE_BYTES {
        return Err("Saved Olm account state has an invalid size");
    }

    let mut java_copy = vec![0_i8; length];
    let copy_result = env.get_byte_array_region(&stored_array, 0, &mut java_copy);

    // Clear the Java-side decrypted buffer as soon as the copy was attempted.
    let java_zeros = vec![0_i8; length];
    let clear_result = env.set_byte_array_region(&stored_array, 0, &java_zeros);
    copy_result.map_err(|_| "Could not copy saved Olm account state")?;
    clear_result.map_err(|_| "Could not clear temporary Java state buffer")?;

    let mut state_blob: Vec<u8> = java_copy.iter().map(|value| *value as u8).collect();
    java_copy.zeroize();

    if &state_blob[..ACCOUNT_STATE_HEADER.len()] != ACCOUNT_STATE_HEADER {
        state_blob.zeroize();
        return Err("Saved Olm account state uses an unsupported format");
    }

    let pickle_result =
        serde_json::from_slice::<AccountPickle>(&state_blob[ACCOUNT_STATE_HEADER.len()..]);
    state_blob.zeroize();

    let pickle = pickle_result.map_err(|_| "Saved Olm account state could not be decoded")?;
    let account = Account::from(pickle);
    Ok(account_identity_fingerprint(&account))
}

// This symbol is intentionally narrow: it verifies that the native library
// can invoke the real vodozemac implementation without exposing key material.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeVodozemacVersion<
    'local,
>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match env.new_string(VODOZEMAC_VERSION) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeOlmSelfTest<
    'local,
>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(run_olm_self_test)) {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(_)) | Err(_) => JNI_FALSE,
    }
}

#[cfg(test)]
mod tests {
    use super::{run_olm_self_test, OlmMessageParts};
    use vodozemac::olm::{Account, AccountPickle, OlmMessage};

    #[test]
    fn two_independent_devices_establish_and_exchange_olm_messages() {
        run_olm_self_test().expect("two-device Olm exchange should succeed");
    }

    #[test]
    fn account_pickle_round_trips_public_identity_keys() {
        let mut account = Account::new();
        account.generate_one_time_keys(5);
        account.generate_fallback_key();

        let identity_keys = account.identity_keys();
        let serialized =
            serde_json::to_vec(&account.pickle()).expect("an Olm account pickle should serialize");
        let pickle: AccountPickle =
            serde_json::from_slice(&serialized).expect("an Olm account pickle should deserialize");
        let restored = Account::from(pickle);

        assert_eq!(identity_keys, restored.identity_keys());
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

// Persist a temporary Olm account through the Android Keystore-backed store.
// The public identity fingerprint is returned for the restoration test; no
// private key or serialized state is returned to Kotlin.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativePersistAccountState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(|| persist_test_account(&mut env, &store))) {
        Ok(Ok(identity)) => env
            .new_string(identity)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(Err(_)) | Err(_) => std::ptr::null_mut(),
    }
}

// Restore a serialized Olm account from the Keystore-backed store and compare
// its public identity keys against the test's expected fingerprint.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeRestoreAccountState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    expected_identity: JString<'local>,
) -> jboolean {
    let expected: String = match env.get_string(&expected_identity) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    match catch_unwind(AssertUnwindSafe(|| restore_test_account(&mut env, &store))) {
        Ok(Ok(restored_identity)) if restored_identity == expected => JNI_TRUE,
        Ok(Ok(_)) | Ok(Err(_)) | Err(_) => JNI_FALSE,
    }
}
