package com.example.inchat.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PasswordRecoveryRepository {

    companion object {

        private const val TAG =
            "PasswordRecoveryRepository"

        private const val RECOVERY_WORKER_URL =
            "https://icy-base-2bc4.s91670002.workers.dev/recover"

        private const val CONNECT_TIMEOUT =
            10_000

        private const val READ_TIMEOUT =
            15_000
    }

    suspend fun recoverPassword(
        username: String,
        recoveryCode: String,
        newPassword: String
    ): Result<String> {

        return withContext(
            Dispatchers.IO
        ) {

            try {

                if (
                    username.isBlank() ||
                    recoveryCode.isBlank() ||
                    newPassword.isBlank()
                ) {

                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "All fields are required."
                        )
                    )
                }

                val requestBody =
                    JSONObject().apply {

                        put(
                            "username",
                            username.trim()
                        )

                        put(
                            "recoveryCode",
                            recoveryCode.trim()
                        )

                        put(
                            "newPassword",
                            newPassword
                        )
                    }.toString()

                val connection =
                    URL(
                        RECOVERY_WORKER_URL
                    ).openConnection()
                            as HttpURLConnection

                try {

                    connection.requestMethod =
                        "POST"

                    connection.connectTimeout =
                        CONNECT_TIMEOUT

                    connection.readTimeout =
                        READ_TIMEOUT

                    connection.doOutput =
                        true

                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    connection.setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    connection.outputStream
                        .use { outputStream ->

                            outputStream.write(
                                requestBody.toByteArray(
                                    Charsets.UTF_8
                                )
                            )
                        }

                    val responseCode =
                        connection.responseCode

                    val responseStream =
                        if (
                            responseCode in 200..299
                        ) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                    val responseText =
                        responseStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            .orEmpty()

                    if (
                        responseText.isBlank()
                    ) {

                        return@withContext Result.failure(
                            IllegalStateException(
                                "Empty server response."
                            )
                        )
                    }

                    val json =
                        try {

                            JSONObject(
                                responseText
                            )

                        } catch (
                            _: Exception
                        ) {

                            null
                        }

                    if (
                        responseCode in 200..299 &&
                        json != null &&
                        json.optBoolean(
                            "success",
                            false
                        )
                    ) {

                        val message =
                            json.optString(
                                "message",
                                "Password changed successfully."
                            )

                        return@withContext Result.success(
                            message
                        )
                    }

                    val serverError =
                        json
                            ?.optString(
                                "error",
                                ""
                            )
                            ?.trim()
                            .orEmpty()

                    val finalError =
                        if (
                            serverError.isNotBlank()
                        ) {

                            serverError

                        } else {

                            when (
                                responseCode
                            ) {

                                400 ->
                                    "Invalid recovery information."

                                401 ->
                                    "Invalid username or recovery code."

                                500 ->
                                    "Server error. Please try again."

                                else ->
                                    "Could not reset your password."
                            }
                        }

                    Result.failure(
                        IllegalStateException(
                            finalError
                        )
                    )

                } finally {

                    connection.disconnect()
                }

            } catch (e: Exception) {

                Result.failure(
                    e
                )
            }
        }
    }
}