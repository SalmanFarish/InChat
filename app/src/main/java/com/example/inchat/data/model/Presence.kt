package com.example.inchat.data.model

data class Presence(
    val online: Boolean = false,
    val lastSeen: Long = 0L
)
