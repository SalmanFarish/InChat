use jni::objects::{JByteArray, JObject, JString, JValue};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use vodozemac::olm::{Account, AccountPickle};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature, KeyId};
use zeroize::Zeroize;

const DEVICE_STATE_HEADER: &[u8; 6] = b"ICDEV\x01";
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

fn encode_device_account_state(
    device_id: &str,
    account: &Account,
) -> Result<Vec<u8>, &'static str> {
    if !valid_device_id(device_id) {
        return Err("Device ID has an invalid format");
    }

    let mut serialized_pickle = serde_json::to_vec(&account.pickle())
        .map_err(|_| "Olm device account serialization failed")?;

    let mut state_blob = Vec::with_capacity(
        DEVICE_STATE_HEADER.len() + 1 + device_id.len() + serialized_pickle.len(),
    );
    state_blob.extend_from_slice(DEVICE_STATE_HEADER);
    state_blob.push(device_id.len() as u8);
    state_blob.extend_from_slice(device_id.as_bytes());
    state_blob.extend_from_slice(&serialized_pickle);
    serialized_pickle.zeroize();

    if state_blob.len() > MAX_DEVICE_STATE_BYTES {
        state_blob.zeroize();
        return Err("Olm device account state exceeds the storage limit");
    }

    Ok(state_blob)
}

fn decode_device_account_state(
    mut state_blob: Vec<u8>,
    expected_device_id: &str,
) -> Result<Account, &'static str> {
    let result = (|| {
        if !valid_device_id(expected_device_id) {
            return Err("Device ID has an invalid format");
        }
        if state_blob.len() > MAX_DEVICE_STATE_BYTES
            || !state_blob.starts_with(DEVICE_STATE_HEADER)
            || state_blob.len() <= DEVICE_STATE_HEADER.len()
        {
            return Err("Saved Olm device state has an invalid format");
        }

        let id_length = state_blob[DEVICE_STATE_HEADER.len()] as usize;
        if id_length == 0 || id_length > 64 {
            return Err("Saved Olm device ID has an invalid length");
        }

        let id_start = DEVICE_STATE_HEADER.len() + 1;
        let pickle_start = id_start + id_length;
        if pickle_start >= state_blob.len() {
            return Err("Saved Olm device state is truncated");
        }

        let stored_device_id = std::str::from_utf8(&state_blob[id_start..pickle_start])
            .map_err(|_| "Saved Olm device ID is not valid UTF-8")?;
        if stored_device_id != expected_device_id {
            return Err("Saved Olm state belongs to a different device ID");
        }

        let pickle = serde_json::from_slice::<AccountPickle>(&state_blob[pickle_start..])
            .map_err(|_| "Saved Olm device account could not be decoded")?;
        Ok(Account::from(pickle))
    })();

    state_blob.zeroize();
    result
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
        format!("{SIGNATURE_CONTEXT}\\n{device_id}\\nidentity_curve25519\\n{curve25519}");
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

    if state_blob.len() < DEVICE_STATE_HEADER.len() || !state_blob.starts_with(DEVICE_STATE_HEADER)
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

fn ensure_device_identity<'local>(
    env: &mut JNIEnv<'local>,
    store: &JObject<'local>,
    device_id: &str,
) -> Result<String, &'static str> {
    if !valid_device_id(device_id) {
        return Err("Device ID has an invalid format");
    }

    let existing_state = read_optional_device_state(env, store)?;
    let (account, is_new) = match existing_state {
        Some(state_blob) => (decode_device_account_state(state_blob, device_id)?, false),
        None => {
            let mut account = Account::new();
            account.generate_one_time_keys(INITIAL_ONE_TIME_KEY_COUNT);
            account.generate_fallback_key();
            (account, true)
        }
    };

    // Save before returning the public bundle. Repeated calls then return the
    // same identity and unpublished prekeys until publication is acknowledged.
    if is_new {
        let state_blob = encode_device_account_state(device_id, &account)?;
        write_device_state(env, store, state_blob)?;
    }

    serde_json::to_string(&public_device_key_bundle(&account, device_id))
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

#[cfg(test)]
mod tests {
    use super::{
        decode_device_account_state, encode_device_account_state, public_device_key_bundle,
        signed_key_payload, valid_device_id, SIGNATURE_CONTEXT,
    };
    use vodozemac::olm::Account;
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
    fn independent_accounts_have_distinct_device_identity_keys() {
        let first = Account::new();
        let second = Account::new();

        assert_ne!(first.identity_keys(), second.identity_keys());
    }
}
