package com.example.inchat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Presence
import com.example.inchat.ui.profile.InChatProfileAvatar

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun ChatTopBar(
    otherUserNickname: String,
    otherUserProfilePhoto: String,
    otherUserPresence: Presence,
    otherUserTyping: Boolean,
    blockState: BlockState,
    onBackClick: () -> Unit,
    onChatInfoClick: () -> Unit
) {

    TopAppBar(

        title = {

            Row(

                modifier =
                    Modifier
                        .clickable(
                            onClick =
                                onChatInfoClick
                        )
                        .padding(
                            start =
                                4.dp,

                            top =
                                2.dp,

                            end =
                                8.dp,

                            bottom =
                                2.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                InChatProfileAvatar(

                    profilePhotoUrl =
                        otherUserProfilePhoto,

                    modifier =
                        Modifier.size(
                            40.dp
                        ),

                    iconSize =
                        21.dp,

                    contentDescription =
                        "Profile picture"
                )

                Column(

                    modifier =
                        Modifier.padding(
                            start =
                                10.dp
                        )
                ) {

                    Text(

                        text =
                            otherUserNickname,

                        fontSize =
                            18.sp,

                        fontWeight =
                            FontWeight.SemiBold,

                        letterSpacing =
                            (-0.2).sp
                    )

                    if (
                        blockState ==
                        BlockState.NONE
                    ) {

                        PresenceStatus(

                            presence =
                                otherUserPresence,

                            isTyping =
                                otherUserTyping
                        )
                    }
                }
            }
        },

        navigationIcon = {

            IconButton(

                onClick =
                    onBackClick
            ) {

                Icon(

                    imageVector =
                        Icons.AutoMirrored
                            .Filled
                            .ArrowBack,

                    contentDescription =
                        "Back"
                )
            }
        },

        colors =
            TopAppBarDefaults
                .topAppBarColors(

                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .background,

                    scrolledContainerColor =
                        MaterialTheme
                            .colorScheme
                            .background,

                    titleContentColor =
                        MaterialTheme
                            .colorScheme
                            .onBackground,

                    navigationIconContentColor =
                        MaterialTheme
                            .colorScheme
                            .onBackground
                )
    )
}
