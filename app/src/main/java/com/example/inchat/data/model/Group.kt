package com.example.inchat.data.model

data class Group(
    val chatId: String = "",
    val type: String = "group",
    val name: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val members: Map<String, String> = emptyMap()
)