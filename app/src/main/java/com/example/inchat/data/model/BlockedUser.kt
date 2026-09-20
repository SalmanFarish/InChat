package com.example.inchat.data.model

data class BlockedUser(
    val uid: String = "",
    val username: String = "",
    val blockedAt: Long = 0L
)