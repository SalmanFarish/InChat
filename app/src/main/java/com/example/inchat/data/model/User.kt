package com.example.inchat.data.model

data class User(

    val uid: String = "",

    val username: String = "",

    val displayName: String = "",

    val bio: String = "",

    /*
     * Whether this user allows read receipts to be published to other users.
     * Existing accounts default to true when the field is absent.
     */
    val readReceiptsVisible: Boolean = true,

    /*
     * Whether this user allows their typing status to be shared.
     * Existing accounts default to true when the field is absent.
     */
    val typingIndicatorVisible: Boolean = true,

    /*
     * Whether this account can be discovered through username search.
     * Existing accounts default to true when the field is absent.
     */
    val discoverableByUsername: Boolean = true,

    /*
     * Kept for backward compatibility with the existing app.
     * Older accounts may still contain a Storage URL here.
     */
    val profilePhotoUrl: String = "",

    /*
     * New free-plan profile photo storage.
     *
     * The photo will be stored as a compressed Base64 string
     * in Realtime Database instead of Firebase Cloud Storage.
     */
    val profilePhotoData: String = ""
)
