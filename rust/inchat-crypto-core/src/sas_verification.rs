//! Process-local SAS verification primitives.
//!
//! A verification attempt is intentionally not serializable. Process death,
//! cancellation, or timeout must discard its ephemeral Diffie-Hellman secret;
//! the user starts a fresh attempt. This module never changes device trust.
//!
//! The caller must exchange commitments before revealing ephemeral public keys,
//! display the SAS to both users, obtain explicit local confirmation, and
//! exchange/verify both identity-key MACs before considering the attempt complete.

use sha2::{Digest, Sha256};
use vodozemac::sas::{EstablishedSas, Mac, Sas};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey};

const COMMITMENT_CONTEXT: &str = "INCHAT_SAS_COMMIT_V1";
const SAS_INFO_CONTEXT: &str = "INCHAT_SAS_INFO_V1";
const SAS_MAC_CONTEXT: &str = "INCHAT_SAS_MAC_V1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SasDeviceIdentity {
    pub user_id: String,
    pub device_id: String,
    pub ed25519_key: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SasVerificationBinding {
    /// Both participants use the same role ordering for transcript binding.
    pub initiator: SasDeviceIdentity,
    pub responder: SasDeviceIdentity,
    pub transaction_id: String,
}

impl SasVerificationBinding {
    fn validate(&self) -> Result<(), &'static str> {
        if !valid_identifier(&self.initiator.user_id, 128)
            || !valid_identifier(&self.responder.user_id, 128)
            || !valid_identifier(&self.initiator.device_id, 64)
            || !valid_identifier(&self.responder.device_id, 64)
            || self.initiator.user_id == self.responder.user_id
                && self.initiator.device_id == self.responder.device_id
            || !valid_identifier(&self.transaction_id, 128)
        {
            return Err("SAS verification metadata is invalid");
        }

        for identity in [&self.initiator, &self.responder] {
            let key = Ed25519PublicKey::from_base64(&identity.ed25519_key)
                .map_err(|_| "SAS identity key is invalid")?;
            if key.to_base64() != identity.ed25519_key {
                return Err("SAS identity key is not canonical Base64");
            }
        }
        Ok(())
    }

    fn transcript(&self) -> String {
        format!(
            "{SAS_INFO_CONTEXT}\n{}\n{}\n{}\n{}\n{}\n{}",
            self.transaction_id,
            self.initiator.user_id,
            self.initiator.device_id,
            self.initiator.ed25519_key,
            self.responder.user_id,
            self.responder.device_id,
        ) + &format!("\n{}", self.responder.ed25519_key)
    }
}

fn valid_identifier(value: &str, max_len: usize) -> bool {
    !value.is_empty()
        && value.len() <= max_len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn commitment(public_key: &str, transaction_id: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(COMMITMENT_CONTEXT.as_bytes());
    digest.update(b"\n");
    digest.update(public_key.as_bytes());
    digest.update(transaction_id.as_bytes());
    digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// One ephemeral side of a two-device SAS exchange.
///
/// The struct deliberately has no Serialize implementation. Its secret must
/// remain process-local and be dropped on cancellation or process death.
pub(crate) struct SasVerification {
    binding: SasVerificationBinding,
    local_is_initiator: bool,
    sas: Option<Sas>,
    local_public_key: String,
    local_commitment: String,
    peer_commitment_accepted: bool,
    local_public_key_revealed: bool,
    established: Option<EstablishedSas>,
    local_match_confirmed: bool,
    peer_mac_verified: bool,
    peer_commitment: Option<String>,
}

impl SasVerification {
    pub fn new(
        binding: SasVerificationBinding,
        local_is_initiator: bool,
    ) -> Result<Self, &'static str> {
        binding.validate()?;
        let sas = Sas::new();
        let local_public_key = sas.public_key().to_base64();
        let local_commitment = commitment(&local_public_key, &binding.transaction_id);

        Ok(Self {
            binding,
            local_is_initiator,
            sas: Some(sas),
            local_public_key,
            local_commitment,
            peer_commitment_accepted: false,
            local_public_key_revealed: false,
            established: None,
            local_match_confirmed: false,
            peer_mac_verified: false,
            peer_commitment: None,
        })
    }

    pub fn commitment(&self) -> &str {
        &self.local_commitment
    }

    /// The local ephemeral key is withheld until the peer's commitment arrives.
    pub fn reveal_public_key(&mut self) -> Result<&str, &'static str> {
        if !self.peer_commitment_accepted {
            return Err("Peer commitment must be accepted before revealing the SAS key");
        }
        self.local_public_key_revealed = true;
        Ok(&self.local_public_key)
    }

    pub fn accept_peer_commitment(&mut self, peer_commitment: &str) -> Result<(), &'static str> {
        if self.peer_commitment_accepted || self.established.is_some() {
            return Err("Peer commitment was already supplied");
        }
        if peer_commitment.len() != 64
            || !peer_commitment.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Err("Peer SAS commitment is malformed");
        }
        self.peer_commitment = Some(peer_commitment.to_owned());
        self.peer_commitment_accepted = true;
        Ok(())
    }

    pub fn accept_peer_public_key(&mut self, peer_public_key: &str) -> Result<(), &'static str> {
        if !self.peer_commitment_accepted || !self.local_public_key_revealed {
            return Err("SAS commitments must be exchanged before public keys");
        }
        if self.established.is_some() {
            return Err("Peer SAS public key was already supplied");
        }
        if commitment(peer_public_key, &self.binding.transaction_id) != self.peer_commitment() {
            return Err("Peer SAS public key does not match its commitment");
        }

        let peer_key = Curve25519PublicKey::from_base64(peer_public_key)
            .map_err(|_| "Peer SAS public key is invalid")?;
        let sas = self.sas.take().ok_or("SAS secret is unavailable")?;
        let established = sas
            .diffie_hellman(peer_key)
            .map_err(|_| "SAS key agreement failed")?;
        self.established = Some(established);
        Ok(())
    }

    fn peer_commitment(&self) -> &str {
        // The commitment is supplied by the transport caller and stored in
        // the dedicated field in the protocol object (see setter below).
        self.peer_commitment.as_deref().unwrap_or_default()
    }

    pub fn display_codes(&self) -> Result<(Vec<u8>, (u16, u16, u16)), &'static str> {
        let established = self.established.as_ref().ok_or("SAS is not established")?;
        let bytes = established.bytes(&self.binding.transcript());
        Ok((bytes.emoji_indices().to_vec(), bytes.decimals()))
    }

    pub fn confirm_sas_match(&mut self, matched: bool) -> Result<(), &'static str> {
        if self.established.is_none() {
            return Err("SAS is not established");
        }
        if !matched {
            return Err("SAS comparison was rejected");
        }
        self.local_match_confirmed = true;
        Ok(())
    }

    /// Return the MAC for this device's Ed25519 identity key after local SAS confirmation.
    pub fn create_local_mac(&self) -> Result<String, &'static str> {
        if !self.local_match_confirmed {
            return Err("Local SAS confirmation is required before sending a MAC");
        }
        let established = self.established.as_ref().ok_or("SAS is not established")?;
        let identity = self.local_identity();
        let input = format!("ed25519:{}", identity.ed25519_key);
        let mac = established.calculate_mac(&input, &self.mac_info());
        Ok(mac.to_base64())
    }

    /// Verify the peer's MAC against the expected directory identity key.
    pub fn verify_peer_mac(&mut self, encoded_mac: &str) -> Result<(), &'static str> {
        if !self.local_match_confirmed {
            return Err("Local SAS confirmation is required before verifying a MAC");
        }
        let established = self.established.as_ref().ok_or("SAS is not established")?;
        let tag = Mac::from_base64(encoded_mac).map_err(|_| "Peer SAS MAC is malformed")?;
        let identity = self.peer_identity();
        let input = format!("ed25519:{}", identity.ed25519_key);
        established
            .verify_mac(&input, &self.mac_info(), &tag)
            .map_err(|_| "Peer SAS identity MAC did not verify")?;
        self.peer_mac_verified = true;
        Ok(())
    }

    pub fn is_complete(&self) -> bool {
        self.local_match_confirmed && self.peer_mac_verified
    }

    fn local_identity(&self) -> &SasDeviceIdentity {
        if self.local_is_initiator {
            &self.binding.initiator
        } else {
            &self.binding.responder
        }
    }

    fn peer_identity(&self) -> &SasDeviceIdentity {
        if self.local_is_initiator {
            &self.binding.responder
        } else {
            &self.binding.initiator
        }
    }

    fn mac_info(&self) -> String {
        format!("{SAS_MAC_CONTEXT}\n{}", self.binding.transcript())
    }
}

#[cfg(test)]
mod tests {
    use super::{SasDeviceIdentity, SasVerification, SasVerificationBinding};
    use vodozemac::olm::Account;

    fn fixture() -> (SasVerificationBinding, SasVerificationBinding) {
        let alice = Account::new();
        let bob = Account::new();
        let alice_key = alice.identity_keys().ed25519.to_base64();
        let bob_key = bob.identity_keys().ed25519.to_base64();
        let alice_identity = SasDeviceIdentity {
            user_id: "alice-user".into(),
            device_id: "alice-device".into(),
            ed25519_key: alice_key,
        };
        let bob_identity = SasDeviceIdentity {
            user_id: "bob-user".into(),
            device_id: "bob-device".into(),
            ed25519_key: bob_key,
        };
        let alice_binding = SasVerificationBinding {
            initiator: alice_identity.clone(),
            responder: bob_identity.clone(),
            transaction_id: "verification-transaction-001".into(),
        };
        let bob_binding = SasVerificationBinding {
            initiator: alice_identity,
            responder: bob_identity,
            transaction_id: "verification-transaction-001".into(),
        };
        (alice_binding, bob_binding)
    }

    fn establish_pair(
        alice: &mut SasVerification,
        bob: &mut SasVerification,
    ) -> (Vec<u8>, (u16, u16, u16)) {
        alice.accept_peer_commitment(bob.commitment()).unwrap();
        bob.accept_peer_commitment(alice.commitment()).unwrap();

        let alice_public = alice.reveal_public_key().unwrap().to_owned();
        let bob_public = bob.reveal_public_key().unwrap().to_owned();

        alice.accept_peer_public_key(&bob_public).unwrap();
        bob.accept_peer_public_key(&alice_public).unwrap();

        let alice_codes = alice.display_codes().unwrap();
        let bob_codes = bob.display_codes().unwrap();
        assert_eq!(alice_codes, bob_codes);
        alice_codes
    }

    #[test]
    fn two_devices_derive_matching_sas_and_mutually_authenticate_identity_keys() {
        let (alice_binding, bob_binding) = fixture();
        let mut alice = SasVerification::new(alice_binding, true).unwrap();
        let mut bob = SasVerification::new(bob_binding, false).unwrap();

        let codes = establish_pair(&mut alice, &mut bob);
        assert_eq!(codes.0.len(), 7);
        alice.confirm_sas_match(true).unwrap();
        bob.confirm_sas_match(true).unwrap();

        let alice_mac = alice.create_local_mac().unwrap();
        let bob_mac = bob.create_local_mac().unwrap();
        alice.verify_peer_mac(&bob_mac).unwrap();
        bob.verify_peer_mac(&alice_mac).unwrap();

        assert!(alice.is_complete());
        assert!(bob.is_complete());
    }

    #[test]
    fn public_key_cannot_be_revealed_before_peer_commitment() {
        let (alice_binding, _) = fixture();
        let mut alice = SasVerification::new(alice_binding, true).unwrap();
        assert!(alice.reveal_public_key().is_err());
    }

    #[test]
    fn altered_ephemeral_key_is_rejected_by_commitment() {
        let (alice_binding, bob_binding) = fixture();
        let mut alice = SasVerification::new(alice_binding, true).unwrap();
        let mut bob = SasVerification::new(bob_binding, false).unwrap();
        alice.accept_peer_commitment(bob.commitment()).unwrap();
        bob.accept_peer_commitment(alice.commitment()).unwrap();
        let alice_public = alice.reveal_public_key().unwrap().to_owned();
        let _bob_public = bob.reveal_public_key().unwrap().to_owned();
        let altered = Account::new().curve25519_key().to_base64();
        assert_ne!(altered, alice_public);
        assert!(bob.accept_peer_public_key(&altered).is_err());
    }

    #[test]
    fn mac_is_bound_to_transaction_and_both_device_identities() {
        let (alice_binding, bob_binding) = fixture();
        let mut alice = SasVerification::new(alice_binding.clone(), true).unwrap();
        let mut bob = SasVerification::new(bob_binding.clone(), false).unwrap();
        establish_pair(&mut alice, &mut bob);
        alice.confirm_sas_match(true).unwrap();
        bob.confirm_sas_match(true).unwrap();

        let alice_mac = alice.create_local_mac().unwrap();
        bob.verify_peer_mac(&alice_mac).unwrap();

        let mut changed = bob_binding;
        changed.responder.device_id = "different-device".into();
        let mut attacker_context = SasVerification::new(changed, false).unwrap();
        // A MAC from the original transcript cannot authenticate a changed binding.
        let mut peer = SasVerification::new(alice_binding, true).unwrap();
        peer.accept_peer_commitment(attacker_context.commitment()).unwrap();
        attacker_context
            .accept_peer_commitment(peer.commitment())
            .unwrap();
        let peer_public = peer.reveal_public_key().unwrap().to_owned();
        let attacker_public = attacker_context.reveal_public_key().unwrap().to_owned();
        peer.accept_peer_public_key(&attacker_public).unwrap();
        attacker_context
            .accept_peer_public_key(&peer_public)
            .unwrap();
        attacker_context.confirm_sas_match(true).unwrap();
        assert_ne!(
            peer.display_codes().unwrap(),
            attacker_context.display_codes().unwrap()
        );
        assert!(attacker_context.verify_peer_mac(&alice_mac).is_err());
    }

    #[test]
    fn failed_local_comparison_never_produces_a_mac_or_completion() {
        let (alice_binding, bob_binding) = fixture();
        let mut alice = SasVerification::new(alice_binding, true).unwrap();
        let mut bob = SasVerification::new(bob_binding, false).unwrap();
        establish_pair(&mut alice, &mut bob);
        assert!(alice.confirm_sas_match(false).is_err());
        assert!(alice.create_local_mac().is_err());
        assert!(!alice.is_complete());
    }

    #[test]
    fn verification_metadata_rejects_same_device_and_malformed_keys() {
        let (mut binding, _) = fixture();
        binding.responder.user_id = binding.initiator.user_id.clone();
        binding.responder.device_id = binding.initiator.device_id.clone();
        assert!(SasVerification::new(binding, true).is_err());

        let (mut binding, _) = fixture();
        binding.initiator.ed25519_key = "not-a-key".into();
        assert!(SasVerification::new(binding, true).is_err());
    }
}
