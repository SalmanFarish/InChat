//! JNI handle registry for process-local SAS verification attempts.
//!
//! Handles are opaque, process-local capabilities. They are invalid after process
//! death and never persist ephemeral SAS secrets or modify trusted-device state.

use crate::sas_verification::{SasDeviceIdentity, SasVerification, SasVerificationBinding};
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<String, SasVerification>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, SasVerification>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn read_string<'local>(
    env: &mut JNIEnv<'local>,
    value: &JString<'local>,
) -> Result<String, &'static str> {
    env.get_string(value)
        .map(String::from)
        .map_err(|_| "Invalid SAS string argument")
}

fn java_string<'local>(env: &mut JNIEnv<'local>, value: &str) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn with_session<R>(
    handle: &str,
    action: impl FnOnce(&mut SasVerification) -> Result<R, &'static str>,
) -> Result<R, &'static str> {
    let mut guard = sessions()
        .lock()
        .map_err(|_| "SAS registry is unavailable")?;
    let session = guard
        .get_mut(handle)
        .ok_or("SAS verification handle is invalid")?;
    action(session)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasCreate(
    mut env: JNIEnv,
    _this: JObject,
    initiator_user: JString,
    initiator_device: JString,
    initiator_ed25519: JString,
    responder_user: JString,
    responder_device: JString,
    responder_ed25519: JString,
    transaction_id: JString,
    local_is_initiator: jboolean,
) -> jstring {
    let result = (|| {
        let binding = SasVerificationBinding {
            initiator: SasDeviceIdentity {
                user_id: read_string(&mut env, &initiator_user)?,
                device_id: read_string(&mut env, &initiator_device)?,
                ed25519_key: read_string(&mut env, &initiator_ed25519)?,
            },
            responder: SasDeviceIdentity {
                user_id: read_string(&mut env, &responder_user)?,
                device_id: read_string(&mut env, &responder_device)?,
                ed25519_key: read_string(&mut env, &responder_ed25519)?,
            },
            transaction_id: read_string(&mut env, &transaction_id)?,
        };
        let verification = SasVerification::new(binding, local_is_initiator == JNI_TRUE)?;
        let commitment = verification.commitment().to_owned();
        let counter = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let handle = format!("sas-{counter:016x}-{commitment}");
        let mut guard = sessions()
            .lock()
            .map_err(|_| "SAS registry is unavailable")?;
        guard.insert(handle.clone(), verification);
        serde_json::to_string(&serde_json::json!({
            "handle": handle,
            "commitment": commitment,
        }))
        .map_err(|_| "Could not encode SAS session")
    })();
    match result {
        Ok(value) => java_string(&mut env, &value),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasAcceptPeerCommitment(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
    commitment: JString,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        let commitment = read_string(&mut env, &commitment)?;
        with_session(&handle, |session| {
            session.accept_peer_commitment(&commitment)
        })
    })();
    if matches!(result, Ok(Ok(()))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasRevealPublicKey(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
) -> jstring {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        with_session(&handle, |session| {
            Ok(session.reveal_public_key()?.to_owned())
        })
    })();
    match result {
        Ok(Ok(value)) => java_string(&mut env, &value),
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasAcceptPeerPublicKey(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
    public_key: JString,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        let public_key = read_string(&mut env, &public_key)?;
        with_session(&handle, |session| {
            session.accept_peer_public_key(&public_key)
        })
    })();
    if matches!(result, Ok(Ok(()))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasDisplayCodes(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
) -> jstring {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        with_session(&handle, |session| {
            let (emoji_indices, decimals) = session.display_codes()?;
            serde_json::to_string(&serde_json::json!({
                "emojiIndices": emoji_indices,
                "decimals": [decimals.0, decimals.1, decimals.2],
            }))
            .map_err(|_| "Could not encode SAS codes")
        })
    })();
    match result {
        Ok(Ok(value)) => java_string(&mut env, &value),
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasConfirmMatch(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
    matched: jboolean,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        with_session(&handle, |session| {
            session.confirm_sas_match(matched == JNI_TRUE)
        })
    })();
    if matches!(result, Ok(Ok(()))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasCreateLocalMac(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
) -> jstring {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        with_session(&handle, |session| {
            session.create_local_mac().map(str::to_owned)
        })
    })();
    match result {
        Ok(Ok(value)) => java_string(&mut env, &value),
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasVerifyPeerMac(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
    mac: JString,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        let mac = read_string(&mut env, &mac)?;
        with_session(&handle, |session| session.verify_peer_mac(&mac))
    })();
    if matches!(result, Ok(Ok(()))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasIsComplete(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        with_session(&handle, |session| Ok(session.is_complete()))
    })();
    if matches!(result, Ok(Ok(true))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_inchat_crypto_NativeCryptoBridge_nativeSasCancel(
    mut env: JNIEnv,
    _this: JObject,
    handle: JString,
) -> jboolean {
    let result = (|| {
        let handle = read_string(&mut env, &handle)?;
        let mut guard = sessions()
            .lock()
            .map_err(|_| "SAS registry is unavailable")?;
        Ok(guard.remove(&handle).is_some())
    })();
    if matches!(result, Ok(true)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

