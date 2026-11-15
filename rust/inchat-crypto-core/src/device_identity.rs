use jni::objects::{JByteArray, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use vodozemac::olm::{Account, AccountPickle, OlmMessage};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature, KeyId};
use zeroize::Zeroize;

const DEVICE_STATE_HEADER_V1: &[u8; 6] = b"ICDEV\x01";
const DEVICE_STATE_HEADER_V2: &[u8; 6] = b"ICDEV\x02";
const MAX_DEVICE_STATE_BYTES: usize = 8 * 1024 * 1024;
const INITIAL_ONE_TIME_KEY_COUNT: usize = 20;
const SIGNATURE_CONTEXT: &str = "INCHAT_OLM_PUBLIC_KEY_V1";

fn valid_device_id(device_id: &str) -> bool {
    !device_id.is_empty()
        && device_id.len() <= 64
        && device_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn valid_receipt_id(receipt_id: &str) -> bool {
    receipt_id.len() == 64
        && receipt_id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

struct PendingDecryption {
    session_id: String,
    ciphertext: Vec<u8>,
    plaintext: String,
}

struct DeviceCryptoState {
    account: Account,
    sessions: std::collections::BTreeMap<String, vodozemac::olm::Session>,
    pending_decryptions: std::collections::BTreeMap<String, PendingDecryption>,
}

fn encode_device_crypto_state(
    device_id: &str,
    state: &DeviceCryptoState,
) -> Result<Vec<u8>, &'static str> {
    if !valid_device_id(device_id) {
        return Err("Device ID has an invalid format");
    }
    let account = serde_json::to_value(state.account.pickle())
        .map_err(|_| "Olm device account serialization failed")?;
    let mut sessions = serde_json::Map::new();
    for (id, session) in &state.sessions {
        sessions.insert(
            id.clone(),
            serde_json::to_value(session.pickle())
                .map_err(|_| "Olm session serialization failed")?,
        );
    }
    let mut pending_decryptions = serde_json::Map::new();
    for (receipt_id, pending) in &state.pending_decryptions {
        pending_decryptions.insert(
            receipt_id.clone(),
            json!({
                "sessionId": pending.session_id,
                "ciphertext": pending.ciphertext,
                "plaintext": pending.plaintext
            }),
        );
    }
    let mut serialized = serde_json::to_vec(&json!({
        "account": account,
        "sessions": sessions,
        "pendingDecryptions": pending_decryptions
    }))
    .map_err(|_| "Olm device crypto state serialization failed")?;
    let mut blob =
        Vec::with_capacity(DEVICE_STATE_HEADER_V2.len() + 1 + device_id.len() + serialized.len());
    blob.extend_from_slice(DEVICE_STATE_HEADER_V2);
    blob.push(device_id.len() as u8);
    blob.extend_from_slice(device_id.as_bytes());
    blob.extend_from_slice(&serialized);
    serialized.zeroize();
    if blob.len() > MAX_DEVICE_STATE_BYTES {
        blob.zeroize();
        return Err("Olm device crypto state exceeds the storage limit");
    }
    Ok(blob)
}

fn decode_device_crypto_state(
    mut blob: Vec<u8>,
    expected_device_id: &str,
) -> Result<DeviceCryptoState, &'static str> {
    let result = (|| {
        if !valid_device_id(expected_device_id) || blob.len() > MAX_DEVICE_STATE_BYTES {
            return Err("Saved Olm device state has an invalid size or device ID");
        }
        let header = if blob.starts_with(DEVICE_STATE_HEADER_V2) {
            DEVICE_STATE_HEADER_V2
        } else if blob.starts_with(DEVICE_STATE_HEADER_V1) {
            DEVICE_STATE_HEADER_V1
        } else {
            return Err("Saved Olm device state has an unsupported format");
        };
        if blob.len() <= header.len() {
            return Err("Saved Olm device state is truncated");
        }
        let id_len = blob[header.len()] as usize;
        if id_len == 0 || id_len > 64 {
            return Err("Saved Olm device ID has an invalid length");
        }
        let id_start = header.len() + 1;
        let pickle_start = id_start + id_len;
        if pickle_start >= blob.len() {
            return Err("Saved Olm device state is truncated");
        }
        let stored_id = std::str::from_utf8(&blob[id_start..pickle_start])
            .map_err(|_| "Saved Olm device ID is not valid UTF-8")?;
        if stored_id != expected_device_id {
            return Err("Saved Olm state belongs to a different device ID");
        }
        if header == DEVICE_STATE_HEADER_V1 {
            let pickle = serde_json::from_slice::<AccountPickle>(&blob[pickle_start..])
                .map_err(|_| "Saved legacy Olm account could not be decoded")?;
            return Ok(DeviceCryptoState {
                account: Account::from(pickle),
                sessions: std::collections::BTreeMap::new(),
                pending_decryptions: std::collections::BTreeMap::new(),
            });
        }
        let envelope: Value = serde_json::from_slice(&blob[pickle_start..])
            .map_err(|_| "Saved Olm device crypto state could not be decoded")?;
        let account_pickle = serde_json::from_value::<AccountPickle>(
            envelope
                .get("account")
                .cloned()
                .ok_or("Saved Olm state has no account")?,
        )
        .map_err(|_| "Saved Olm device account could not be decoded")?;
        let values = envelope
            .get("sessions")
            .and_then(Value::as_object)
            .ok_or("Saved Olm device state has invalid sessions")?;
        if values.len() > 10_000 {
            return Err("Saved Olm device state contains too many sessions");
        }
        let mut sessions = std::collections::BTreeMap::new();
        for (id, value) in values {
            if id.is_empty() || id.len() > 128 {
                return Err("Saved Olm session ID has an invalid format");
            }
            let pickle = serde_json::from_value::<vodozemac::olm::SessionPickle>(value.clone())
                .map_err(|_| "Saved Olm session could not be decoded")?;
            let session = vodozemac::olm::Session::from_pickle(pickle);
            if session.session_id() != *id {
                return Err("Saved Olm session ID does not match its pickle");
            }
            sessions.insert(id.clone(), session);
        }
        let mut pending_decryptions = std::collections::BTreeMap::new();
        if let Some(pending_values) = envelope.get("pendingDecryptions").and_then(Value::as_object) {
            if pending_values.len() > 256 {
                return Err("Saved Olm state contains too many pending decryptions");
            }
            for (receipt_id, value) in pending_values {
                if !valid_receipt_id(receipt_id) {
                    return Err("Saved pending decryption has an invalid receipt ID");
                }
                let session_id = value.get("sessionId").and_then(Value::as_str)
                    .ok_or("Saved pending decryption has no session ID")?;
                if session_id.is_empty() || session_id.len() > 128 {
                    return Err("Saved pending decryption has an invalid session ID");
                }
                let ciphertext = value.get("ciphertext").and_then(Value::as_array)
                    .ok_or("Saved pending decryption has no ciphertext")?
                    .iter()
                    .map(|byte| byte.as_u64().and_then(|v| u8::try_from(v).ok())
                        .ok_or("Saved pending ciphertext is invalid"))
                    .collect::<Result<Vec<u8>, _>>()?;
                if ciphertext.len() < 2 || ciphertext.len() > MAX_OLM_WIRE_BYTES {
                    return Err("Saved pending ciphertext has an invalid size");
                }
                let plaintext = value.get("plaintext").and_then(Value::as_str)
                    .ok_or("Saved pending decryption has no plaintext")?.to_owned();
                if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
                    return Err("Saved pending plaintext exceeds the message size limit");
                }
                pending_decryptions.insert(receipt_id.clone(), PendingDecryption {
                    session_id: session_id.to_owned(),
                    ciphertext,
                    plaintext,
                });
            }
        }
        Ok(DeviceCryptoState {
            account: Account::from(account_pickle),
            sessions,
            pending_decryptions,
        })
    })();
    blob.zeroize();
    result
}

fn decode_device_account_state(blob: Vec<u8>, device_id: &str) -> Result<Account, &'static str> {
    Ok(decode_device_crypto_state(blob, device_id)?.account)
}

fn encode_device_account_state(
    device_id: &str,
    account: &Account,
) -> Result<Vec<u8>, &'static str> {
    let pickle = serde_json::to_value(account.pickle())
        .map_err(|_| "Olm device account serialization failed")?;
    let restored = Account::from(
        serde_json::from_value::<AccountPickle>(pickle)
            .map_err(|_| "Olm device account serialization failed")?,
    );
    encode_device_crypto_state(
        device_id,
        &DeviceCryptoState {
            account: restored,
            sessions: std::collections::BTreeMap::new(),
            pending_decryptions: std::collections::BTreeMap::new(),
        },
    )
}

fn signed_key_payload(device_id: &str, key_kind: &str, key_id: &str, public_key: &str) -> String {
    format!("{SIGNATURE_CONTEXT}\n{device_id}\n{key_kind}\n{key_id}\n{public_key}")
}

fn signed_public_key(
    account: &Account,
    device_id: &str,
    key_kind: &str,
    key_id: KeyId,
    public_key: Curve25519PublicKey,
) -> Value {
    let key_id = key_id.to_base64();
    let public_key = public_key.to_base64();
    let payload = signed_key_payload(device_id, key_kind, &key_id, &public_key);
    let signature = account.sign(payload.as_bytes()).to_base64();

    json!({
        "keyKind": key_kind,
        "keyId": key_id,
        "key": public_key,
        "signature": signature
    })
}

fn public_device_key_bundle(account: &Account, device_id: &str) -> Value {
    let identity_keys = account.identity_keys();
    let curve25519_identity_key = identity_keys.curve25519.to_base64();
    let identity_payload =
        format!("{SIGNATURE_CONTEXT}\n{device_id}\nidentity_curve25519\n{curve25519_identity_key}");
    let curve25519_identity_signature = account.sign(identity_payload.as_bytes()).to_base64();

    let mut one_time_keys: Vec<_> = account.one_time_keys().into_iter().collect();
    one_time_keys.sort_by_key(|(key_id, _)| key_id.to_base64());
    let one_time_keys: Vec<Value> = one_time_keys
        .into_iter()
        .map(|(key_id, key)| signed_public_key(account, device_id, "one_time", key_id, key))
        .collect();

    let mut fallback_keys: Vec<_> = account.fallback_key().into_iter().collect();
    fallback_keys.sort_by_key(|(key_id, _)| key_id.to_base64());
    let fallback_key = fallback_keys
        .into_iter()
        .next()
        .map(|(key_id, key)| signed_public_key(account, device_id, "fallback", key_id, key));

    json!({
        "schemaVersion": 1,
        "deviceId": device_id,
        "identityKeys": {
            "ed25519": identity_keys.ed25519.to_base64(),
            "curve25519": curve25519_identity_key,
            "curve25519Signature": curve25519_identity_signature
        },
        "fallbackKey": fallback_key,
        "oneTimeKeys": one_time_keys
    })
}

/// Verify the identity binding and every signed prekey in an untrusted public bundle.
fn verify_public_device_key_bundle(encoded: &str) -> bool {
    let Ok(bundle) = serde_json::from_str::<Value>(encoded) else {
        return false;
    };
    let Some(device_id) = bundle.get("deviceId").and_then(Value::as_str) else {
        return false;
    };
    if !valid_device_id(device_id) || bundle.get("schemaVersion").and_then(Value::as_u64) != Some(1)
    {
        return false;
    }

    let Some(identity) = bundle.get("identityKeys") else {
        return false;
    };
    let Some(ed25519) = identity.get("ed25519").and_then(Value::as_str) else {
        return false;
    };
    let Some(curve25519) = identity.get("curve25519").and_then(Value::as_str) else {
        return false;
    };
    let Some(identity_signature) = identity.get("curve25519Signature").and_then(Value::as_str)
    else {
        return false;
    };
    let Ok(identity_key) = Ed25519PublicKey::from_base64(ed25519) else {
        return false;
    };
    let Ok(identity_signature) = Ed25519Signature::from_base64(identity_signature) else {
        return false;
    };
    let identity_payload =
        format!("{SIGNATURE_CONTEXT}\n{device_id}\nidentity_curve25519\n{curve25519}");
    if identity_key
        .verify(identity_payload.as_bytes(), &identity_signature)
        .is_err()
    {
        return false;
    }

    let Some(fallback) = bundle.get("fallbackKey").filter(|value| !value.is_null()) else {
        return false;
    };
    let Some(one_time_keys) = bundle.get("oneTimeKeys").and_then(Value::as_array) else {
        return false;
    };
    if one_time_keys.len() > INITIAL_ONE_TIME_KEY_COUNT {
        return false;
    }

    let verify_prekey = |entry: &Value, expected_kind: &str| -> bool {
        let Some(kind) = entry.get("keyKind").and_then(Value::as_str) else {
            return false;
        };
        let Some(key_id) = entry.get("keyId").and_then(Value::as_str) else {
            return false;
        };
        let Some(public_key) = entry.get("key").and_then(Value::as_str) else {
            return false;
        };
        let Some(signature) = entry.get("signature").and_then(Value::as_str) else {
            return false;
        };
        if kind != expected_kind || key_id.is_empty() || public_key.is_empty() {
            return false;
        }
        let Ok(_) = Curve25519PublicKey::from_base64(public_key) else {
            return false;
        };
        let Ok(signature) = Ed25519Signature::from_base64(signature) else {
            return false;
        };
        let payload = signed_key_payload(device_id, kind, key_id, public_key);
        identity_key.verify(payload.as_bytes(), &signature).is_ok()
    };

    verify_prekey(fallback, "fallback")
        && one_time_keys
            .iter()
            .all(|entry| verify_prekey(entry, "one_time"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeVerifyDeviceKeyBundle<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    encoded_bundle: JString<'local>,
) -> jboolean {
    let encoded: String = match env.get_string(&encoded_bundle) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    match catch_unwind(AssertUnwindSafe(|| {
        verify_public_device_key_bundle(&encoded)
    })) {
        Ok(true) => JNI_TRUE,
        Ok(false) | Err(_) => JNI_FALSE,
    }
}

fn read_optional_device_state<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
) -> Result<Option<Vec<u8>>, &'static str> {
    let stored_value = env
        .call_method(store, "readForNative", "()[B", &[])
        .map_err(|_| "Android Keystore could not read device identity state")?;
    let stored_object = stored_value
        .l()
        .map_err(|_| "Crypto state store returned an invalid value")?;
    if stored_object.is_null() {
        return Ok(None);
    }

    let stored_array = JByteArray::from(stored_object);
    let length = env
        .get_array_length(&stored_array)
        .map_err(|_| "Could not inspect saved device identity state")? as usize;
    if length > MAX_DEVICE_STATE_BYTES {
        let java_zeros = vec![0_i8; length];
        env.set_byte_array_region(&stored_array, 0, &java_zeros)
            .map_err(|_| "Could not clear oversized device identity state buffer")?;
        return Err("Saved device identity state exceeds the storage limit");
    }

    let mut java_copy = vec![0_i8; length];
    let copy_result = env.get_byte_array_region(&stored_array, 0, &mut java_copy);

    // Wipe the Java-side decrypted buffer immediately after the copy attempt.
    let java_zeros = vec![0_i8; length];
    let clear_result = env.set_byte_array_region(&stored_array, 0, &java_zeros);
    copy_result.map_err(|_| "Could not copy saved device identity state")?;
    clear_result.map_err(|_| "Could not clear temporary Java device state buffer")?;

    let mut state_blob: Vec<u8> = java_copy.iter().map(|value| *value as u8).collect();
    java_copy.zeroize();

    if state_blob.len() < DEVICE_STATE_HEADER_V1.len()
        || !(state_blob.starts_with(DEVICE_STATE_HEADER_V1)
            || state_blob.starts_with(DEVICE_STATE_HEADER_V2))
    {
        state_blob.zeroize();
        return Err("Saved device identity state uses an unsupported format");
    }

    Ok(Some(state_blob))
}

fn write_device_state<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    mut state_blob: Vec<u8>,
) -> Result<(), &'static str> {
    if state_blob.is_empty() || state_blob.len() > MAX_DEVICE_STATE_BYTES {
        state_blob.zeroize();
        return Err("Olm device identity state has an invalid size");
    }

    let java_state_result = env.byte_array_from_slice(&state_blob);
    state_blob.zeroize();
    let java_state =
        java_state_result.map_err(|_| "Could not create temporary device identity buffer")?;

    env.call_method(
        store,
        "writeForNative",
        "([B)V",
        &[JValue::Object(java_state.as_ref())],
    )
    .map_err(|_| "Android Keystore could not persist the Olm device identity")?;

    Ok(())
}

fn ensure_unpublished_prekeys(account: &mut Account) -> bool {
    let mut changed = false;
    let unpublished_count = account.one_time_keys().len();
    if unpublished_count < INITIAL_ONE_TIME_KEY_COUNT {
        account.generate_one_time_keys(INITIAL_ONE_TIME_KEY_COUNT - unpublished_count);
        changed = true;
    }
    if account.fallback_key().is_empty() {
        account.generate_fallback_key();
        changed = true;
    }
    changed
}

fn ensure_device_identity<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
) -> Result<String, &'static str> {
    if !valid_device_id(device_id) {
        return Err("Device ID has an invalid format");
    }

    let existing_state = read_optional_device_state(env, store)?;
    let (mut state, is_new) = match existing_state {
        Some(state_blob) => (decode_device_crypto_state(state_blob, device_id)?, false),
        None => (
            DeviceCryptoState {
                account: Account::new(),
                sessions: std::collections::BTreeMap::new(),
                pending_decryptions: std::collections::BTreeMap::new(),
            },
            true,
        ),
    };

    // Replenish only unpublished keys. Published private keys remain in the
    // account until vodozemac consumes them while creating an inbound session.
    // A fresh batch is persisted before its public parts can be uploaded.
    let replenished = ensure_unpublished_prekeys(&mut state.account);
    if is_new || replenished {
        let state_blob = encode_device_crypto_state(device_id, &state)?;
        write_device_state(env, store, state_blob)?;
    }

    serde_json::to_string(&public_device_key_bundle(&state.account, device_id))
        .map_err(|_| "Could not encode the public Olm device key bundle")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeEnsureDeviceIdentity<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
) -> jstring {
    let device_id: String = match env.get_string(&device_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let result = catch_unwind(AssertUnwindSafe(|| {
        ensure_device_identity(&mut env, &store, &device_id)
    }));

    match result {
        Ok(Ok(public_bundle)) => env
            .new_string(public_bundle)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(Err(_)) | Err(_) => std::ptr::null_mut(),
    }
}

fn mark_device_identity_published<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
) -> Result<(), &'static str> {
    let state_blob =
        read_optional_device_state(env, store)?.ok_or("No saved Olm device identity exists")?;
    let mut state = decode_device_crypto_state(state_blob, device_id)?;
    state.account.mark_keys_as_published();
    let updated_state = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, updated_state)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeMarkDeviceKeysPublished<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
) -> jboolean {
    let device_id: String = match env.get_string(&device_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    match catch_unwind(AssertUnwindSafe(|| {
        mark_device_identity_published(&mut env, &store, &device_id)
    })) {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(_)) | Err(_) => JNI_FALSE,
    }
}

const MAX_OLM_PLAINTEXT_BYTES: usize = 64 * 1024;
const MAX_OLM_WIRE_BYTES: usize = 1024 * 1024;

fn read_olm_wire_message<'local>(
    env: &mut JNIEnv<'local>,
    bytes: &JByteArray<'local>,
) -> Result<Vec<u8>, &'static str> {
    let length = env
        .get_array_length(bytes)
        .map_err(|_| "Could not inspect encrypted Olm message")? as usize;
    if length < 2 || length > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm message has an invalid size");
    }
    let mut java_copy = vec![0_i8; length];
    let copy_result = env.get_byte_array_region(bytes, 0, &mut java_copy);
    let zeros = vec![0_i8; length];
    let clear_result = env.set_byte_array_region(bytes, 0, &zeros);
    copy_result.map_err(|_| "Could not copy encrypted Olm message")?;
    clear_result.map_err(|_| "Could not clear temporary encrypted message buffer")?;
    let result = java_copy.iter().map(|byte| *byte as u8).collect();
    java_copy.zeroize();
    Ok(result)
}

fn load_device_crypto_state<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
) -> Result<DeviceCryptoState, &'static str> {
    let blob =
        read_optional_device_state(env, store)?.ok_or("No saved Olm device identity exists")?;
    decode_device_crypto_state(blob, device_id)
}

fn create_outbound_olm_session<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    remote_identity_key: &str,
    remote_one_time_key: &str,
) -> Result<String, &'static str> {
    let remote_identity = Curve25519PublicKey::from_base64(remote_identity_key)
        .map_err(|_| "Remote Olm identity key is invalid")?;
    let remote_one_time = Curve25519PublicKey::from_base64(remote_one_time_key)
        .map_err(|_| "Remote Olm one-time key is invalid")?;
    let mut state = load_device_crypto_state(env, store, device_id)?;
    let session = state
        .account
        .create_outbound_session(
            vodozemac::olm::SessionConfig::version_1(),
            remote_identity,
            remote_one_time,
        )
        .map_err(|_| "Could not create outbound Olm session")?;
    let session_id = session.session_id();
    if state.sessions.contains_key(&session_id) {
        return Err("An Olm session with this ID already exists");
    }
    state.sessions.insert(session_id.clone(), session);
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    Ok(session_id)
}

fn encrypt_olm_message<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    session_id: &str,
    plaintext: &str,
) -> Result<Vec<u8>, &'static str> {
    if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
        return Err("Olm plaintext exceeds the message size limit");
    }
    let mut state = load_device_crypto_state(env, store, device_id)?;
    let session = state
        .sessions
        .get_mut(session_id)
        .ok_or("Olm session was not found")?;
    let message = session
        .encrypt(plaintext)
        .map_err(|_| "Could not encrypt Olm message")?;
    let (message_type, ciphertext) = message.to_parts();
    let message_type =
        u8::try_from(message_type).map_err(|_| "Olm message type is out of range")?;
    if ciphertext.len() + 1 > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm message exceeds the size limit");
    }
    let mut wire = Vec::with_capacity(ciphertext.len() + 1);
    wire.push(message_type);
    wire.extend_from_slice(&ciphertext);
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    Ok(wire)
}

fn create_inbound_olm_session<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    remote_identity_key: &str,
    wire: &[u8],
) -> Result<String, &'static str> {
    if wire.len() < 2 || wire.len() > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm pre-key message has an invalid size");
    }
    let remote_identity = Curve25519PublicKey::from_base64(remote_identity_key)
        .map_err(|_| "Remote Olm identity key is invalid")?;
    let message = OlmMessage::from_parts(wire[0] as usize, &wire[1..])
        .map_err(|_| "Encrypted Olm message has an invalid type or encoding")?;
    let pre_key = match message {
        OlmMessage::PreKey(message) => message,
        OlmMessage::Normal(_) => return Err("Initial Olm message must be a pre-key message"),
    };

    let mut state = load_device_crypto_state(env, store, device_id)?;
    // Account one-time-prekey consumption and new-session persistence share this
    // single encrypted AtomicFile write. No intermediate account-only save occurs.
    let inbound = state
        .account
        .create_inbound_session(
            vodozemac::olm::SessionConfig::version_1(),
            remote_identity,
            &pre_key,
        )
        .map_err(|_| "Could not establish inbound Olm session")?;
    let plaintext = String::from_utf8(inbound.plaintext)
        .map_err(|_| "Decrypted Olm message is not valid UTF-8")?;
    if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
        return Err("Decrypted Olm plaintext exceeds the message size limit");
    }
    let session_id = inbound.session.session_id();
    if state.sessions.contains_key(&session_id) {
        return Err("Inbound Olm session already exists");
    }
    state.sessions.insert(session_id.clone(), inbound.session);
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    // Return only session ID and application plaintext; never private state.
    serde_json::to_string(&json!({"sessionId": session_id, "plaintext": plaintext}))
        .map_err(|_| "Could not encode decrypted Olm message")
}

fn pending_decryption_result(
    pending: &PendingDecryption,
    receipt_id: &str,
    wire: &[u8],
    expected_session_id: Option<&str>,
) -> Result<String, &'static str> {
    if pending.ciphertext != wire
        || expected_session_id.is_some_and(|expected| expected != pending.session_id)
    {
        return Err("Receipt ID is already bound to different ciphertext or session metadata");
    }
    serde_json::to_string(&json!({
        "receiptId": receipt_id,
        "sessionId": pending.session_id,
        "plaintext": pending.plaintext
    }))
    .map_err(|_| "Could not encode recovered Olm plaintext")
}

fn prepare_inbound_prekey_decryption<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    receipt_id: &str,
    remote_identity_key: &str,
    wire: &[u8],
) -> Result<String, &'static str> {
    if !valid_receipt_id(receipt_id) {
        return Err("Inbound receipt ID must be a SHA-256 hex digest");
    }
    if wire.len() < 2 || wire.len() > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm pre-key message has an invalid size");
    }
    let mut state = load_device_crypto_state(env, store, device_id)?;
    if let Some(pending) = state.pending_decryptions.get(receipt_id) {
        return pending_decryption_result(pending, receipt_id, wire, None);
    }
    if state.pending_decryptions.len() >= 256 {
        return Err("Too many pending Olm decryptions; finalize committed receipts first");
    }

    let remote_identity = Curve25519PublicKey::from_base64(remote_identity_key)
        .map_err(|_| "Remote Olm identity key is invalid")?;
    let message = OlmMessage::from_parts(wire[0] as usize, &wire[1..])
        .map_err(|_| "Encrypted Olm message has an invalid type or encoding")?;
    let pre_key = match message {
        OlmMessage::PreKey(message) => message,
        OlmMessage::Normal(_) => return Err("Initial Olm message must be a pre-key message"),
    };
    // OTK consumption, new session, receive ratchet, and staged plaintext are
    // committed in one Keystore-encrypted AtomicFile write.
    let inbound = state.account.create_inbound_session(
        vodozemac::olm::SessionConfig::version_1(),
        remote_identity,
        &pre_key,
    ).map_err(|_| "Could not establish inbound Olm session")?;
    let mut plaintext = String::from_utf8(inbound.plaintext)
        .map_err(|_| "Decrypted Olm message is not valid UTF-8")?;
    if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
        plaintext.zeroize();
        return Err("Decrypted Olm plaintext exceeds the message size limit");
    }
    let session_id = inbound.session.session_id();
    if state.sessions.contains_key(&session_id) {
        plaintext.zeroize();
        return Err("Inbound Olm session already exists");
    }
    state.sessions.insert(session_id.clone(), inbound.session);
    state.pending_decryptions.insert(receipt_id.to_owned(), PendingDecryption {
        session_id: session_id.clone(),
        ciphertext: wire.to_vec(),
        plaintext: plaintext.clone(),
    });
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    let result = serde_json::to_string(&json!({
        "receiptId": receipt_id,
        "sessionId": session_id,
        "plaintext": plaintext
    })).map_err(|_| "Could not encode staged Olm plaintext");
    plaintext.zeroize();
    result
}

fn prepare_existing_session_decryption<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    receipt_id: &str,
    session_id: &str,
    wire: &[u8],
) -> Result<String, &'static str> {
    if !valid_receipt_id(receipt_id) {
        return Err("Inbound receipt ID must be a SHA-256 hex digest");
    }
    if session_id.is_empty() || session_id.len() > 128 {
        return Err("Olm session ID has an invalid format");
    }
    if wire.len() < 2 || wire.len() > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm message has an invalid size");
    }
    let mut state = load_device_crypto_state(env, store, device_id)?;
    if let Some(pending) = state.pending_decryptions.get(receipt_id) {
        return pending_decryption_result(pending, receipt_id, wire, Some(session_id));
    }
    if state.pending_decryptions.len() >= 256 {
        return Err("Too many pending Olm decryptions; finalize committed receipts first");
    }
    let message = OlmMessage::from_parts(wire[0] as usize, &wire[1..])
        .map_err(|_| "Encrypted Olm message has an invalid type or encoding")?;
    if matches!(&message, OlmMessage::PreKey(_)) {
        return Err("A pre-key message requires inbound-session creation");
    }
    let session = state.sessions.get_mut(session_id)
        .ok_or("Olm session was not found")?;
    let mut plaintext_bytes = session.decrypt(&message)
        .map_err(|_| "Could not decrypt Olm message")?;
    let mut plaintext = match String::from_utf8(plaintext_bytes.clone()) {
        Ok(value) => value,
        Err(_) => {
            plaintext_bytes.zeroize();
            return Err("Decrypted Olm message is not valid UTF-8");
        }
    };
    plaintext_bytes.zeroize();
    if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
        plaintext.zeroize();
        return Err("Decrypted Olm plaintext exceeds the message size limit");
    }
    state.pending_decryptions.insert(receipt_id.to_owned(), PendingDecryption {
        session_id: session_id.to_owned(),
        ciphertext: wire.to_vec(),
        plaintext: plaintext.clone(),
    });
    // Ratchet advancement and pending plaintext are persisted atomically.
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    let result = serde_json::to_string(&json!({
        "receiptId": receipt_id,
        "sessionId": session_id,
        "plaintext": plaintext
    })).map_err(|_| "Could not encode staged Olm plaintext");
    plaintext.zeroize();
    result
}

fn finalize_pending_decryption<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    receipt_id: &str,
) -> Result<bool, &'static str> {
    if !valid_receipt_id(receipt_id) {
        return Err("Inbound receipt ID must be a SHA-256 hex digest");
    }
    let mut state = load_device_crypto_state(env, store, device_id)?;
    if state.pending_decryptions.remove(receipt_id).is_none() {
        return Ok(true);
    }
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    Ok(true)
}

fn decrypt_olm_message<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
    session_id: &str,
    wire: &[u8],
) -> Result<String, &'static str> {
    if wire.len() < 2 || wire.len() > MAX_OLM_WIRE_BYTES {
        return Err("Encrypted Olm message has an invalid size");
    }
    let message = OlmMessage::from_parts(wire[0] as usize, &wire[1..])
        .map_err(|_| "Encrypted Olm message has an invalid type or encoding")?;
    if matches!(&message, OlmMessage::PreKey(_)) {
        return Err("A pre-key message requires inbound-session creation");
    }
    let mut state = load_device_crypto_state(env, store, device_id)?;
    let session = state
        .sessions
        .get_mut(session_id)
        .ok_or("Olm session was not found")?;
    let plaintext = session
        .decrypt(&message)
        .map_err(|_| "Could not decrypt Olm message")?;
    let plaintext =
        String::from_utf8(plaintext).map_err(|_| "Decrypted Olm message is not valid UTF-8")?;
    if plaintext.len() > MAX_OLM_PLAINTEXT_BYTES {
        return Err("Decrypted Olm plaintext exceeds the message size limit");
    }
    // Persist the advanced receive ratchet before returning plaintext to Kotlin.
    let blob = encode_device_crypto_state(device_id, &state)?;
    write_device_state(env, store, blob)?;
    Ok(plaintext)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeCreateOutboundOlmSession<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
    remote_identity_key: JString<'local>,
    remote_one_time_key: JString<'local>,
) -> jstring {
    let device_id: String = match env.get_string(&device_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let identity: String = match env.get_string(&remote_identity_key) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let one_time: String = match env.get_string(&remote_one_time_key) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match catch_unwind(AssertUnwindSafe(|| {
        create_outbound_olm_session(&mut env, &store, &device_id, &identity, &one_time)
    })) {
        Ok(Ok(id)) => env
            .new_string(id)
            .map(|v| v.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeEncryptOlmMessage<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
    session_id: JString<'local>,
    plaintext: JString<'local>,
) -> jbyteArray {
    let device_id: String = match env.get_string(&device_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let session_id: String = match env.get_string(&session_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mut plaintext: String = match env.get_string(&plaintext) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        encrypt_olm_message(&mut env, &store, &device_id, &session_id, &plaintext)
    }));
    plaintext.zeroize();
    match result {
        Ok(Ok(mut wire)) => {
            let output = env.byte_array_from_slice(&wire);
            wire.zeroize();
            output.map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeCreateInboundOlmSession<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
    remote_identity_key: JString<'local>,
    wire_message: JByteArray<'local>,
) -> jstring {
    let device_id: String = match env.get_string(&device_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let identity: String = match env.get_string(&remote_identity_key) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mut wire = match read_olm_wire_message(&mut env, &wire_message) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        create_inbound_olm_session(&mut env, &store, &device_id, &identity, &wire)
    }));
    wire.zeroize();
    match result {
        Ok(Ok(mut value)) => {
            let output = env.new_string(&value);
            value.zeroize();
            output.map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeDecryptOlmMessage<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    store: JObject<'local>,
    device_id: JString<'local>,
    session_id: JString<'local>,
    wire_message: JByteArray<'local>,
) -> jstring {
    let device_id: String = match env.get_string(&device_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let session_id: String = match env.get_string(&session_id) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mut wire = match read_olm_wire_message(&mut env, &wire_message) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        decrypt_olm_message(&mut env, &store, &device_id, &session_id, &wire)
    }));
    wire.zeroize();
    match result {
        Ok(Ok(mut value)) => {
            let output = env.new_string(&value);
            value.zeroize();
            output.map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        _ => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        decode_device_account_state, encode_device_account_state, public_device_key_bundle,
        signed_key_payload, valid_device_id, SIGNATURE_CONTEXT,
    };
    use vodozemac::olm::{Account, OlmMessage, Session, SessionConfig};
    use vodozemac::{Ed25519PublicKey, Ed25519Signature};

    #[test]
    fn device_account_pickle_round_trips_and_is_bound_to_device_id() {
        let mut account = Account::new();
        account.generate_one_time_keys(4);
        account.generate_fallback_key();
        let expected_identity = account.identity_keys();

        let state = encode_device_account_state("device-alpha", &account)
            .expect("device state should serialize");
        let restored = decode_device_account_state(state.clone(), "device-alpha")
            .expect("device state should restore");

        assert_eq!(expected_identity, restored.identity_keys());
        assert!(decode_device_account_state(state, "device-beta").is_err());
    }

    #[test]
    fn pending_decryption_records_round_trip_in_device_state() {
        let account = Account::new();
        let mut pending_decryptions = std::collections::BTreeMap::new();
        pending_decryptions.insert(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_owned(),
            super::PendingDecryption {
                session_id: "session-123".to_owned(),
                ciphertext: vec![1, 2, 3, 4],
                plaintext: "recover this exact plaintext".to_owned(),
            },
        );
        let state = super::DeviceCryptoState {
            account,
            sessions: std::collections::BTreeMap::new(),
            pending_decryptions,
        };

        let encoded = super::encode_device_crypto_state("pending-device", &state)
            .expect("pending state should serialize");
        let restored = super::decode_device_crypto_state(encoded, "pending-device")
            .expect("pending state should restore");
        let pending = restored.pending_decryptions
            .get("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            .expect("pending receipt should survive state restoration");

        assert_eq!(pending.session_id, "session-123");
        assert_eq!(pending.ciphertext, vec![1, 2, 3, 4]);
        assert_eq!(pending.plaintext, "recover this exact plaintext");
    }

    #[test]
    fn legacy_device_state_migrates_without_changing_identity() {
        let mut account = Account::new();
        account.generate_one_time_keys(3);
        account.generate_fallback_key();
        let expected_identity = account.identity_keys();
        let serialized =
            serde_json::to_vec(&account.pickle()).expect("legacy account should serialize");
        let device_id = "legacy-device";
        let mut legacy = Vec::new();
        legacy.extend_from_slice(super::DEVICE_STATE_HEADER_V1);
        legacy.push(device_id.len() as u8);
        legacy.extend_from_slice(device_id.as_bytes());
        legacy.extend_from_slice(&serialized);

        let restored = super::decode_device_crypto_state(legacy, device_id)
            .expect("legacy state should remain readable");
        assert_eq!(restored.account.identity_keys(), expected_identity);
        assert!(restored.sessions.is_empty());

        let migrated = super::encode_device_crypto_state(device_id, &restored)
            .expect("legacy state should migrate to the unified format");
        assert!(migrated.starts_with(super::DEVICE_STATE_HEADER_V2));
        let round_trip = super::decode_device_crypto_state(migrated, device_id)
            .expect("migrated state should restore");
        assert_eq!(round_trip.account.identity_keys(), expected_identity);
    }

    #[test]
    fn unified_device_state_restores_sessions_and_receive_ratchet() {
        let alice = Account::new();
        let mut bob = Account::new();
        bob.generate_one_time_keys(1);
        let bob_key = *bob.one_time_keys().values().next().expect("Bob key exists");
        let mut alice_session = alice
            .create_outbound_session(SessionConfig::version_1(), bob.curve25519_key(), bob_key)
            .expect("Alice session should be created");
        let session_id = alice_session.session_id();

        let first = alice_session
            .encrypt("hello")
            .expect("first message encrypts");
        let pre_key = match first {
            OlmMessage::PreKey(message) => message,
            OlmMessage::Normal(_) => panic!("first message should be a pre-key message"),
        };
        bob.mark_keys_as_published();
        let inbound = bob
            .create_inbound_session(SessionConfig::version_1(), alice.curve25519_key(), &pre_key)
            .expect("Bob should establish the inbound session");
        assert_eq!(inbound.plaintext.as_slice(), b"hello");
        let mut bob_session = inbound.session;

        let mut sessions = std::collections::BTreeMap::new();
        sessions.insert(session_id.clone(), alice_session);
        let state = super::DeviceCryptoState {
            account: alice,
            sessions,
            pending_decryptions: std::collections::BTreeMap::new(),
        };
        let encoded = super::encode_device_crypto_state("session-device", &state)
            .expect("unified state should serialize");
        let mut restored = super::decode_device_crypto_state(encoded, "session-device")
            .expect("unified state should restore");
        assert_eq!(restored.sessions.len(), 1);

        let reply = bob_session
            .encrypt("persisted reply")
            .expect("reply encrypts");
        let session = restored
            .sessions
            .get_mut(&session_id)
            .expect("session restored");
        let plaintext = session
            .decrypt(&reply)
            .expect("restored ratchet decrypts reply");
        assert_eq!(plaintext.as_slice(), b"persisted reply");

        let second_save = super::encode_device_crypto_state("session-device", &restored)
            .expect("advanced ratchet should serialize");
        let mut restored_again = super::decode_device_crypto_state(second_save, "session-device")
            .expect("advanced ratchet should restore");
        let follow_up = bob_session
            .encrypt("second reply")
            .expect("second reply encrypts");
        let plaintext = restored_again
            .sessions
            .get_mut(&session_id)
            .expect("session survives second restore")
            .decrypt(&follow_up)
            .expect("second restored ratchet decrypts");
        assert_eq!(plaintext.as_slice(), b"second reply");
    }

    #[test]
    fn device_ids_reject_delimiters_and_unbounded_values() {
        assert!(valid_device_id("device-123_abc"));
        assert!(!valid_device_id(""));
        assert!(!valid_device_id("device/other"));
        assert!(!valid_device_id("device\nother"));
        assert!(!valid_device_id(&"x".repeat(65)));
    }

    #[test]
    fn public_bundle_contains_signed_one_time_and_fallback_keys() {
        let mut account = Account::new();
        account.generate_one_time_keys(3);
        account.generate_fallback_key();

        let device_id = "device-test-1";
        let bundle = public_device_key_bundle(&account, device_id);
        assert_eq!(bundle["schemaVersion"], 1);
        assert_eq!(bundle["deviceId"], device_id);

        let identity_key = Ed25519PublicKey::from_base64(
            bundle["identityKeys"]["ed25519"]
                .as_str()
                .expect("Ed25519 identity key should be a string"),
        )
        .expect("Ed25519 identity key should decode");

        let curve25519_identity_key = bundle["identityKeys"]["curve25519"]
            .as_str()
            .expect("Curve25519 identity key should be a string");
        let curve25519_identity_signature = Ed25519Signature::from_base64(
            bundle["identityKeys"]["curve25519Signature"]
                .as_str()
                .expect("Curve25519 identity signature should be a string"),
        )
        .expect("Curve25519 identity signature should decode");
        let identity_payload = format!(
            "{SIGNATURE_CONTEXT}\n{device_id}\nidentity_curve25519\n{curve25519_identity_key}"
        );
        identity_key
            .verify(identity_payload.as_bytes(), &curve25519_identity_signature)
            .expect("Curve25519 identity key should be signed by the Ed25519 identity");

        let mut entries = bundle["oneTimeKeys"]
            .as_array()
            .expect("one-time keys should be an array")
            .clone();
        entries.push(
            bundle["fallbackKey"]
                .as_object()
                .map(|_| bundle["fallbackKey"].clone())
                .expect("fallback key should exist"),
        );

        assert_eq!(entries.len(), 4);
        for entry in entries {
            let key_id = entry["keyId"].as_str().expect("key ID should be a string");
            let public_key = entry["key"]
                .as_str()
                .expect("public prekey should be a string");
            let signature = Ed25519Signature::from_base64(
                entry["signature"]
                    .as_str()
                    .expect("signature should be a string"),
            )
            .expect("signature should decode");

            let kind = entry["keyKind"]
                .as_str()
                .expect("prekey kind should be a string");
            assert!(kind == "one_time" || kind == "fallback");
            let payload = signed_key_payload(device_id, kind, key_id, public_key);
            identity_key
                .verify(payload.as_bytes(), &signature)
                .expect("public prekey signature should verify");
        }

        assert!(SIGNATURE_CONTEXT.starts_with("INCHAT_"));
    }

    #[test]
    fn public_device_bundle_verification_rejects_tampering() {
        let mut account = Account::new();
        account.generate_one_time_keys(2);
        account.generate_fallback_key();

        let mut bundle = public_device_key_bundle(&account, "device-verify-1");
        let encoded = serde_json::to_string(&bundle).expect("bundle should encode");
        assert!(super::verify_public_device_key_bundle(&encoded));

        bundle["fallbackKey"]["key"] =
            serde_json::json!("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        let tampered = serde_json::to_string(&bundle).expect("tampered bundle should encode");
        assert!(!super::verify_public_device_key_bundle(&tampered));

        let mut bundle = public_device_key_bundle(&account, "device-verify-1");
        bundle["identityKeys"]["curve25519"] =
            serde_json::json!("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        let tampered_identity =
            serde_json::to_string(&bundle).expect("tampered identity should encode");
        assert!(!super::verify_public_device_key_bundle(&tampered_identity));
    }

    #[test]
    fn unpublished_prekeys_replenish_after_previous_batch_is_published() {
        let mut account = Account::new();
        assert!(super::ensure_unpublished_prekeys(&mut account));
        let first_ids: std::collections::HashSet<_> = account
            .one_time_keys()
            .keys()
            .map(|id| id.to_base64())
            .collect();
        assert_eq!(first_ids.len(), 20);
        assert_eq!(account.fallback_key().len(), 1);

        account.mark_keys_as_published();
        assert!(account.one_time_keys().is_empty());
        assert!(account.fallback_key().is_empty());

        assert!(super::ensure_unpublished_prekeys(&mut account));
        let next_ids: std::collections::HashSet<_> = account
            .one_time_keys()
            .keys()
            .map(|id| id.to_base64())
            .collect();
        assert_eq!(next_ids.len(), 20);
        assert!(first_ids.is_disjoint(&next_ids));
        assert_eq!(account.fallback_key().len(), 1);
    }

    #[test]
    fn existing_unpublished_prekeys_are_not_regenerated() {
        let mut account = Account::new();
        account.generate_one_time_keys(7);
        account.generate_fallback_key();
        let before: std::collections::HashSet<_> = account
            .one_time_keys()
            .keys()
            .map(|id| id.to_base64())
            .collect();

        assert!(super::ensure_unpublished_prekeys(&mut account));
        let after: std::collections::HashSet<_> = account
            .one_time_keys()
            .keys()
            .map(|id| id.to_base64())
            .collect();
        assert!(before.is_subset(&after));
        assert_eq!(after.len(), 20);
    }

    #[test]
    fn olm_prekey_handshake_consumes_key_and_ratchets_both_directions() {
        let alice = Account::new();
        let mut bob = Account::new();
        bob.generate_one_time_keys(1);
        bob.generate_fallback_key();

        let bob_one_time_key = *bob
            .one_time_keys()
            .values()
            .next()
            .expect("Bob should have one unpublished one-time key");
        assert_eq!(bob.stored_one_time_key_count(), 1);

        let mut alice_session = alice
            .create_outbound_session(
                SessionConfig::version_1(),
                bob.curve25519_key(),
                bob_one_time_key,
            )
            .expect("Alice should create an outbound Olm session");

        bob.mark_keys_as_published();
        assert!(bob.one_time_keys().is_empty());
        assert_eq!(bob.stored_one_time_key_count(), 1);

        let first_plaintext = b"InChat native Olm handshake test";
        let first_message = alice_session
            .encrypt(first_plaintext)
            .expect("Alice should encrypt the first message");
        let pre_key_message = match first_message {
            OlmMessage::PreKey(message) => message,
            OlmMessage::Normal(_) => panic!("First Olm message must be a pre-key message"),
        };

        let inbound = bob
            .create_inbound_session(
                SessionConfig::version_1(),
                alice.curve25519_key(),
                &pre_key_message,
            )
            .expect("Bob should authenticate and decrypt the pre-key message");

        assert_eq!(inbound.plaintext, first_plaintext);
        assert_eq!(alice_session.session_id(), inbound.session.session_id());
        assert_eq!(bob.stored_one_time_key_count(), 0);

        let mut bob_session = inbound.session;
        let bob_reply = b"Olm reply received";
        let encrypted_reply = bob_session
            .encrypt(bob_reply)
            .expect("Bob should encrypt a reply");
        let decrypted_reply = alice_session
            .decrypt(&encrypted_reply)
            .expect("Alice should decrypt Bob's reply");
        assert_eq!(decrypted_reply, bob_reply);

        let saved_account = serde_json::to_vec(&bob.pickle())
            .expect("Bob's consumed account state should serialize");
        let restored_account = Account::from(
            serde_json::from_slice::<vodozemac::olm::AccountPickle>(&saved_account)
                .expect("Bob's consumed account state should deserialize"),
        );
        assert_eq!(restored_account.stored_one_time_key_count(), 0);

        let saved_session = serde_json::to_vec(&bob_session.pickle())
            .expect("Bob's ratchet state should serialize");
        let mut restored_session = Session::from_pickle(
            serde_json::from_slice(&saved_session).expect("Bob's ratchet state should deserialize"),
        );
        let next_reply = b"Ratchet state survived persistence";
        let encrypted_next_reply = restored_session
            .encrypt(next_reply)
            .expect("Restored session should continue encrypting");
        let decrypted_next_reply = alice_session
            .decrypt(&encrypted_next_reply)
            .expect("Alice should decrypt after Bob's session restore");
        assert_eq!(decrypted_next_reply, next_reply);
    }

    #[test]
    fn independent_accounts_have_distinct_device_identity_keys() {
        let first = Account::new();
        let second = Account::new();

        assert_ne!(first.identity_keys(), second.identity_keys());
    }
}
