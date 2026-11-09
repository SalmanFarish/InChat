package com.example.inchat.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.inchat.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

object GoogleSignInHelper {

    suspend fun getIdToken(
        context: Context
    ): String {

        val serverClientId =
            context.getString(
                R.string.default_web_client_id
            )

        if (serverClientId.isBlank()) {
            throw IllegalStateException(
                "Google sign-in is not configured. Update the Firebase google-services.json file."
            )
        }

        val googleIdOption =
            GetGoogleIdOption.Builder()
                .setServerClientId(
                    serverClientId
                )
                .setFilterByAuthorizedAccounts(
                    false
                )
                .setAutoSelectEnabled(
                    false
                )
                .build()

        val request =
            GetCredentialRequest.Builder()
                .addCredentialOption(
                    googleIdOption
                )
                .build()

        val response =
            CredentialManager
                .create(context)
                .getCredential(
                    context =
                        context,
                    request =
                        request
                )

        val credential =
            response.credential

        if (
            credential is CustomCredential &&
            credential.type ==
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {

            return GoogleIdTokenCredential
                .createFrom(
                    credential.data
                )
                .idToken
        }

        throw IllegalStateException(
            "Google did not return a valid ID token."
        )
    }
}
