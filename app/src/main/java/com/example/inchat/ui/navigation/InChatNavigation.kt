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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.chat.ChatInfoScreen
import com.example.inchat.ui.chat.ChatScreen
import com.example.inchat.ui.chat.ChatViewModel
import com.example.inchat.ui.home.HomeScreen
import com.example.inchat.ui.home.HomeViewModel
import com.example.inchat.ui.profile.ProfilePhotoScreen
import com.example.inchat.ui.profile.ProfileScreen
import com.example.inchat.ui.profile.PublicProfileScreen
import com.example.inchat.ui.profile.PublicProfileViewModel
import com.example.inchat.ui.search.SearchScreen
import com.example.inchat.ui.search.SearchViewModel
import com.example.inchat.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

data class NotificationChatTarget(
    val chatId: String,
    val otherUserId: String,
    val otherUserNickname: String
)

private data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomNavItems =
    listOf(

        BottomNavItem(
            route =
                "home",

            label =
                "Home",

            selectedIcon =
                Icons.Default.Home,

            unselectedIcon =
                Icons.Outlined.Home
        ),

        BottomNavItem(
            route =
                "search",

            label =
                "Search",

            selectedIcon =
                Icons.Default.Search,

            unselectedIcon =
                Icons.Outlined.Search
        ),

        BottomNavItem(
            route =
                "my_profile",

            label =
                "Profile",

            selectedIcon =
                Icons.Default.Person,

            unselectedIcon =
                Icons.Outlined.Person
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
                ),

            enterTransition = {
                val from =
                    bottomNavIndex(
                        initialState
                            .destination
                            .route
                    )

                val to =
                    bottomNavIndex(
                        targetState
                            .destination
                            .route
                    )

                if (
                    from >= 0 &&
                    to >= 0 &&
                    from != to
                ) {

                    if (
                        to > from
                    ) {

                        slideIntoContainer(
                            AnimatedContentTransitionScope
                                .SlideDirection
                                .Left,

                            animationSpec =
                                spring(
                                    dampingRatio =
                                        0.88f,

                                    stiffness =
                                        420f
                                )
                        )

                    } else {

                        slideIntoContainer(
                            AnimatedContentTransitionScope
                                .SlideDirection
                                .Right,

                            animationSpec =
                                spring(
                                    dampingRatio =
                                        0.88f,

                                    stiffness =
                                        420f
                                )
                        )
                    }

                } else {

                    null
                }
            },

            exitTransition = {
                val from =
                    bottomNavIndex(
                        initialState
                            .destination
                            .route
                    )

                val to =
                    bottomNavIndex(
                        targetState
                            .destination
                            .route
                    )

                if (
                    from >= 0 &&
                    to >= 0 &&
                    from != to
                ) {

                    if (
                        to > from
                    ) {

                        slideOutOfContainer(
                            AnimatedContentTransitionScope
                                .SlideDirection
                                .Left,

                            animationSpec =
                                spring(
                                    dampingRatio =
                                        0.88f,

                                    stiffness =
                                        420f
                                )
                        )

                    } else {

                        slideOutOfContainer(
                            AnimatedContentTransitionScope
                                .SlideDirection
                                .Right,

                            animationSpec =
                                spring(
                                    dampingRatio =
                                        0.88f,

                                    stiffness =
                                        420f
                                )
                        )
                    }

                } else {

                    null
                }
            }
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
             */

            composable(
                "search"
            ) { searchBackStackEntry ->

                val searchViewModel:
                        SearchViewModel =
                    viewModel(
                        searchBackStackEntry
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
                    },

                    onProfilePhotoClick = {

                        navController.navigate(
                            "profile_photo"
                        ) {

                            launchSingleTop =
                                true
                        }
                    }
                )
            }

            /*
             * ==================================================
             * PROFILE PHOTO EDITOR
             * ==================================================
             */

            composable(
                "profile_photo"
            ) {

                val repository =
                    remember {
                        UserRepository()
                    }

                val coroutineScope =
                    rememberCoroutineScope()

                var currentPhotoData by
                remember {
                    mutableStateOf("")
                }

                var isLoadingPhoto by
                remember {
                    mutableStateOf(true)
                }

                var isSavingPhoto by
                remember {
                    mutableStateOf(false)
                }

                LaunchedEffect(
                    uid
                ) {

                    isLoadingPhoto =
                        true

                    val user =
                        repository
                            .getUserByIdFast(
                                uid
                            )

                    if (
                        user != null
                    ) {

                        currentPhotoData =
                            user
                                .profilePhotoData
                                .ifBlank {
                                    user.profilePhotoUrl
                                }
                    }

                    isLoadingPhoto =
                        false
                }

                if (
                    isLoadingPhoto
                ) {

                    Box(

                        modifier =
                            Modifier.fillMaxSize(),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        CircularProgressIndicator()
                    }

                } else {

                    ProfilePhotoScreen(

                        currentPhotoData =
                            currentPhotoData,

                        onBackClick = {

                            if (
                                !isSavingPhoto
                            ) {

                                navController
                                    .popBackStack()
                            }
                        },

                        onSavePhoto = {
                                photoBytes ->

                            if (
                                !isSavingPhoto
                            ) {

                                isSavingPhoto =
                                    true

                                coroutineScope.launch {

                                    repository
                                        .uploadProfilePhoto(
                                            uid =
                                                uid,

                                            photoBytes =
                                                photoBytes
                                        )
                                        .onSuccess {

                                            isSavingPhoto =
                                                false

                                            navController
                                                .popBackStack()
                                        }
                                        .onFailure {

                                            isSavingPhoto =
                                                false
                                        }
                                }
                            }
                        },

                        onRemovePhoto = {

                            if (
                                !isSavingPhoto
                            ) {

                                isSavingPhoto =
                                    true

                                coroutineScope.launch {

                                    repository
                                        .removeProfilePhoto(
                                            uid =
                                                uid
                                        )
                                        .onSuccess {

                                            isSavingPhoto =
                                                false

                                            navController
                                                .popBackStack()
                                        }
                                        .onFailure {

                                            isSavingPhoto =
                                                false
                                        }
                                }
                            }
                        },

                        isSaving =
                            isSavingPhoto
                    )
                }
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

                        navController
                            .popBackStack()
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

            ) { publicProfileBackStackEntry ->

                val otherUsername =
                    publicProfileBackStackEntry
                        .arguments
                        ?.getString(
                            "username"
                        )
                        .orEmpty()

                val publicProfileViewModel:
                        PublicProfileViewModel =
                    viewModel(
                        publicProfileBackStackEntry
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

                        navController
                            .popBackStack()
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

            ) { chatInfoBackStackEntry ->

                val infoUserId =
                    chatInfoBackStackEntry
                        .arguments
                        ?.getString(
                            "otherUserId"
                        )
                        .orEmpty()

                val infoUsername =
                    chatInfoBackStackEntry
                        .arguments
                        ?.getString(
                            "otherUserNickname"
                        )
                        .orEmpty()

                val infoChatViewModel:
                        ChatViewModel =
                    viewModel(
                        chatInfoBackStackEntry
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

                        navController
                            .popBackStack()
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

            ) { chatBackStackEntry ->

                val otherUserId =
                    chatBackStackEntry
                        .arguments
                        ?.getString(
                            "otherUserId"
                        )
                        .orEmpty()

                val otherUserNickname =
                    chatBackStackEntry
                        .arguments
                        ?.getString(
                            "otherUserNickname"
                        )
                        .orEmpty()

                val chatViewModel:
                        ChatViewModel =
                    viewModel(
                        chatBackStackEntry
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

                        navController
                            .popBackStack()
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
 *
 * Compact iOS / Telegram-inspired dock:
 * - centered instead of stretched edge-to-edge
 * - one shared selection capsule
 * - outlined icons when inactive
 * - stronger icons when active
 * - spring-driven movement
 */
@Composable
private fun InChatBottomBar(
    navController:
        NavHostController
) {

    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

    val selectedIndex =
        bottomNavIndex(
            currentRoute
        )
            .coerceAtLeast(
                0
            )

    Surface(

        color =
            MaterialTheme
                .colorScheme
                .surface,

        shape =
            RoundedCornerShape(
                25.dp
            ),

        shadowElevation =
            5.dp,

        tonalElevation =
            0.dp,

        modifier =
            Modifier
                .wrapContentWidth(
                    Alignment.CenterHorizontally
                )
                .padding(
                    bottom =
                        8.dp
                )
    ) {

        BoxWithConstraints(

            modifier =
                Modifier
                    .width(
                        220.dp
                    )
                    .height(
                        58.dp
                    )
                    .padding(
                        4.dp
                    )
        ) {

            val itemWidth =
                maxWidth /
                        bottomNavItems.size

            val indicatorOffset by
            animateDpAsState(

                targetValue =
                    itemWidth *
                            selectedIndex,

                animationSpec =
                    spring(

                        dampingRatio =
                            0.78f,

                        stiffness =
                            520f
                    ),

                label =
                    "bottomBarIndicatorOffset"
            )

            Box(

                modifier =
                    Modifier
                        .offset(
                            x =
                                indicatorOffset
                        )
                        .width(
                            itemWidth
                        )
                        .height(
                            50.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                19.dp
                            )
                        )
                        .background(
                            MaterialTheme
                                .colorScheme
                                .primary
                                .copy(
                                    alpha =
                                        0.12f
                                )
                        )
            )

            Row(

                modifier =
                    Modifier
                        .fillMaxSize(),

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

                        modifier =
                            Modifier
                                .weight(
                                    1f
                                )
                                .fillMaxSize(),

                        onClick = {

                            if (
                                !selected
                            ) {

                                navController
                                    .navigate(
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
}

private fun bottomNavIndex(
    route:
        String?
): Int {

    return bottomNavItems
        .indexOfFirst {
            it.route ==
                    route
        }
}

/*
 * ============================================================
 * BOTTOM NAVIGATION ITEM
 * ============================================================
 */
@Composable
private fun BottomNavigationItem(
    item:
        BottomNavItem,

    selected:
        Boolean,

    modifier:
        Modifier,

    onClick:
        () -> Unit
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
            spring(
                dampingRatio =
                    0.85f,

                stiffness =
                    500f
            ),

        label =
            "bottomIconColor"
    )

    val iconSize by
    animateDpAsState(

        targetValue =
            if (
                selected
            ) {

                25.dp

            } else {

                21.dp
            },

        animationSpec =
            spring(
                dampingRatio =
                    0.82f,

                stiffness =
                    560f
            ),

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

                0.95f
            },

        animationSpec =
            spring(
                dampingRatio =
                    0.80f,

                stiffness =
                    520f
            ),

        label =
            "bottomIconScale"
    )

    Box(

        modifier =
            modifier
                .clickable(
                    onClick =
                        onClick
                ),

        contentAlignment =
            Alignment.Center
    ) {

        Icon(

            imageVector =
                if (
                    selected
                ) {

                    item.selectedIcon

                } else {

                    item.unselectedIcon
                },

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
}
