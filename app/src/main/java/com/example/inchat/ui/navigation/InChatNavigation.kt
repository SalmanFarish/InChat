package com.example.inchat.ui.navigation

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.chat.ChatInfoScreen
import com.example.inchat.ui.chat.ChatScreen
import com.example.inchat.ui.chat.ChatViewModel
import com.example.inchat.ui.home.HomeScreen
import com.example.inchat.ui.home.HomeViewModel
import com.example.inchat.ui.profile.ProfileScreen
import com.example.inchat.ui.profile.PublicProfileScreen
import com.example.inchat.ui.profile.PublicProfileViewModel
import com.example.inchat.ui.search.SearchScreen
import com.example.inchat.ui.search.SearchViewModel
import com.example.inchat.ui.settings.SettingsScreen

data class NotificationChatTarget(
    val chatId: String,
    val otherUserId: String,
    val otherUserNickname: String
)

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems =
    listOf(

        BottomNavItem(
            route =
                "home",

            label =
                "Home",

            icon =
                Icons.Default.Home
        ),

        BottomNavItem(
            route =
                "search",

            label =
                "Search",

            icon =
                Icons.Default.Search
        ),

        BottomNavItem(
            route =
                "my_profile",

            label =
                "Profile",

            icon =
                Icons.Default.Person
        )
    )

@Composable
fun InChatApp(
    uid: String,
    username: String,
    authViewModel: AuthViewModel,
    homeViewModel: HomeViewModel,
    notificationTarget: NotificationChatTarget?,
    onNotificationHandled: () -> Unit
) {

    val navController =
        rememberNavController()

    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

    val showBottomBar =
        currentRoute ==
                "home" ||
                currentRoute ==
                "search" ||
                currentRoute ==
                "my_profile"

    /*
     * =========================================================
     * NOTIFICATION CHAT
     * =========================================================
     */
    LaunchedEffect(
        notificationTarget?.chatId,
        notificationTarget?.otherUserId,
        uid
    ) {

        val target =
            notificationTarget
                ?: return@LaunchedEffect

        val encodedUsername =
            Uri.encode(
                target.otherUserNickname
            )

        navController.navigate(
            "chat/${target.otherUserId}" +
                    "?otherUserNickname=$encodedUsername"
        ) {

            launchSingleTop =
                true
        }

        onNotificationHandled()
    }

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        bottomBar = {

            if (
                showBottomBar
            ) {

                InChatBottomBar(

                    navController =
                        navController
                )
            }
        }

    ) { innerPadding ->

        NavHost(

            navController =
                navController,

            startDestination =
                "home",

            modifier =
                Modifier.padding(
                    innerPadding
                )
        ) {

            /*
             * ==================================================
             * HOME
             * ==================================================
             */
            composable(
                "home"
            ) {

                HomeScreen(

                    authViewModel =
                        authViewModel,

                    homeViewModel =
                        homeViewModel,

                    onConversationClick = {
                            conversation ->

                        val encodedUsername =
                            Uri.encode(
                                conversation.otherUsername
                            )

                        navController.navigate(
                            "chat/${conversation.otherUserId}" +
                                    "?otherUserNickname=$encodedUsername"
                        )
                    },

                    onProfileClick = {

                        navController.navigate(
                            "my_profile"
                        ) {

                            launchSingleTop =
                                true
                        }
                    }
                )
            }

            /*
             * ==================================================
             * SEARCH
             * ==================================================
             *
             * Search now has its own screen and ViewModel.
             */
            composable(
                "search"
            ) { backStackEntry ->

                val searchViewModel:
                        SearchViewModel =
                    viewModel(
                        backStackEntry
                    )

                SearchScreen(

                    currentUserId =
                        uid,

                    searchViewModel =
                        searchViewModel,

                    onUserClick = {
                            user ->

                        val encodedUsername =
                            Uri.encode(
                                user.username
                            )

                        navController.navigate(
                            "profile?username=$encodedUsername"
                        )
                    }
                )
            }

            /*
             * ==================================================
             * MY PROFILE
             * ==================================================
             */
            composable(
                "my_profile"
            ) {

                ProfileScreen(

                    username =
                        username,

                    uid =
                        uid,

                    authViewModel =
                        authViewModel,

                    onBackClick = {

                        navController.navigate(
                            "home"
                        ) {

                            popUpTo(
                                "home"
                            ) {

                                inclusive =
                                    false
                            }

                            launchSingleTop =
                                true
                        }
                    },

                    onSettingsClick = {

                        navController.navigate(
                            "settings"
                        ) {

                            launchSingleTop =
                                true
                        }
                    }
                )
            }

            /*
             * ==================================================
             * SETTINGS
             * ==================================================
             */
            composable(
                "settings"
            ) {

                SettingsScreen(

                    username =
                        username,

                    uid =
                        uid,

                    authViewModel =
                        authViewModel,

                    onBackClick = {

                        navController.popBackStack()
                    }
                )
            }

            /*
             * ==================================================
             * PUBLIC PROFILE
             * ==================================================
             */
            composable(

                route =
                    "profile?username={username}",

                deepLinks =
                    listOf(

                        navDeepLink {

                            uriPattern =
                                "https://inchat.app/user/{username}"
                        }
                    ),

                arguments =
                    listOf(

                        navArgument(
                            "username"
                        ) {

                            type =
                                NavType.StringType

                            defaultValue =
                                ""
                        }
                    )

            ) { backStackEntry ->

                val otherUsername =
                    backStackEntry
                        .arguments
                        ?.getString(
                            "username"
                        )
                        .orEmpty()

                val publicProfileViewModel:
                        PublicProfileViewModel =
                    viewModel(
                        backStackEntry
                    )

                PublicProfileScreen(

                    username =
                        otherUsername,

                    viewModel =
                        publicProfileViewModel,

                    onStartChatClick = {
                            user ->

                        val encodedName =
                            Uri.encode(
                                user.username
                            )

                        navController.navigate(
                            "chat/${user.uid}" +
                                    "?otherUserNickname=$encodedName"
                        ) {

                            popUpTo(
                                "home"
                            )
                        }
                    },

                    onBackClick = {

                        navController.popBackStack()
                    }
                )
            }

            /*
             * ==================================================
             * CHAT INFO
             * ==================================================
             */
            composable(

                route =
                    "chat_info/{otherUserId}" +
                            "?otherUserNickname={otherUserNickname}",

                arguments =
                    listOf(

                        navArgument(
                            "otherUserId"
                        ) {

                            type =
                                NavType.StringType
                        },

                        navArgument(
                            "otherUserNickname"
                        ) {

                            type =
                                NavType.StringType

                            defaultValue =
                                ""
                        }
                    )

            ) { backStackEntry ->

                val infoUserId =
                    backStackEntry
                        .arguments
                        ?.getString(
                            "otherUserId"
                        )
                        .orEmpty()

                val infoUsername =
                    backStackEntry
                        .arguments
                        ?.getString(
                            "otherUserNickname"
                        )
                        .orEmpty()

                val infoChatViewModel:
                        ChatViewModel =
                    viewModel(
                        backStackEntry
                    )

                ChatInfoScreen(

                    currentUserId =
                        uid,

                    otherUserId =
                        infoUserId,

                    otherUserNickname =
                        infoUsername,

                    chatViewModel =
                        infoChatViewModel,

                    onBackClick = {

                        navController.popBackStack()
                    }
                )
            }

            /*
             * ==================================================
             * CHAT
             * ==================================================
             */
            composable(

                route =
                    "chat/{otherUserId}" +
                            "?otherUserNickname={otherUserNickname}",

                arguments =
                    listOf(

                        navArgument(
                            "otherUserId"
                        ) {

                            type =
                                NavType.StringType
                        },

                        navArgument(
                            "otherUserNickname"
                        ) {

                            type =
                                NavType.StringType

                            defaultValue =
                                ""
                        }
                    )

            ) { backStackEntry ->

                val otherUserId =
                    backStackEntry
                        .arguments
                        ?.getString(
                            "otherUserId"
                        )
                        .orEmpty()

                val otherUserNickname =
                    backStackEntry
                        .arguments
                        ?.getString(
                            "otherUserNickname"
                        )
                        .orEmpty()

                val chatViewModel:
                        ChatViewModel =
                    viewModel(
                        backStackEntry
                    )

                ChatScreen(

                    currentUserId =
                        uid,

                    currentNickname =
                        username,

                    otherUserId =
                        otherUserId,

                    otherUserNickname =
                        otherUserNickname,

                    chatViewModel =
                        chatViewModel,

                    onBackClick = {

                        navController.popBackStack()
                    },

                    onChatInfoClick = {

                        val encodedUsername =
                            Uri.encode(
                                otherUserNickname
                            )

                        navController.navigate(
                            "chat_info/${otherUserId}" +
                                    "?otherUserNickname=$encodedUsername"
                        )
                    }
                )
            }
        }
    }
}

/*
 * ============================================================
 * CUSTOM BOTTOM NAVIGATION
 * ============================================================
 */
@Composable
private fun InChatBottomBar(
    navController: NavHostController
) {

    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

    Surface(

        color =
            MaterialTheme
                .colorScheme
                .background,

        shadowElevation =
            8.dp,

        tonalElevation =
            0.dp,

        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        68.dp
                    )
                    .padding(
                        horizontal =
                            14.dp,

                        vertical =
                            6.dp
                    ),

            horizontalArrangement =
                Arrangement.SpaceEvenly,

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            bottomNavItems.forEach { item ->

                val selected =
                    currentRoute ==
                            item.route

                BottomNavigationItem(

                    item =
                        item,

                    selected =
                        selected,

                    onClick = {

                        if (
                            !selected
                        ) {

                            navController.navigate(
                                item.route
                            ) {

                                popUpTo(
                                    "home"
                                ) {

                                    saveState =
                                        true
                                }

                                launchSingleTop =
                                    true

                                restoreState =
                                    true
                            }
                        }
                    }
                )
            }
        }
    }
}

/*
 * ============================================================
 * BOTTOM NAVIGATION ITEM
 * ============================================================
 */
@Composable
private fun BottomNavigationItem(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit
) {

    val iconColor by
    animateColorAsState(

        targetValue =
            if (
                selected
            ) {

                MaterialTheme
                    .colorScheme
                    .primary

            } else {

                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
            },

        animationSpec =
            spring(),

        label =
            "bottomIconColor"
    )

    val textColor by
    animateColorAsState(

        targetValue =
            if (
                selected
            ) {

                MaterialTheme
                    .colorScheme
                    .onBackground

            } else {

                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
            },

        animationSpec =
            spring(),

        label =
            "bottomTextColor"
    )

    val iconSize by
    animateDpAsState(

        targetValue =
            if (
                selected
            ) {

                25.dp

            } else {

                22.dp
            },

        animationSpec =
            spring(),

        label =
            "bottomIconSize"
    )

    val iconScale by
    animateFloatAsState(

        targetValue =
            if (
                selected
            ) {

                1f

            } else {

                0.94f
            },

        animationSpec =
            spring(),

        label =
            "bottomIconScale"
    )

    Column(

        modifier =
            Modifier
                .width(
                    82.dp
                )
                .clickable(
                    onClick =
                        onClick
                )
                .padding(
                    vertical =
                        3.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Box(

            modifier =
                Modifier
                    .size(
                        38.dp
                    )
                    .then(

                        if (
                            selected
                        ) {

                            Modifier.background(

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                        .copy(
                                            alpha =
                                                0.10f
                                        ),

                                shape =
                                    RoundedCornerShape(
                                        14.dp
                                    )
                            )

                        } else {

                            Modifier
                        }
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Icon(

                imageVector =
                    item.icon,

                contentDescription =
                    item.label,

                tint =
                    iconColor,

                modifier =
                    Modifier
                        .size(
                            iconSize
                        )
                        .graphicsLayer {

                            scaleX =
                                iconScale

                            scaleY =
                                iconScale
                        }
            )
        }

        Spacer(
            modifier =
                Modifier.height(
                    1.dp
                )
        )

        Text(

            text =
                item.label,

            fontSize =
                11.sp,

            fontWeight =
                if (
                    selected
                ) {

                    androidx.compose
                        .ui.text
                        .font
                        .FontWeight
                        .SemiBold

                } else {

                    androidx.compose
                        .ui.text
                        .font
                        .FontWeight
                        .Normal
                },

            color =
                textColor
        )
    }
}