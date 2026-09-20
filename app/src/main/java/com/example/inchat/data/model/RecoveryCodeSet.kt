package com.example.inchat.data.model

data class RecoveryCodeSet(
    val codes: List<String>,
    val saltHex: String,
    val codeHashesHex: List<String>
)