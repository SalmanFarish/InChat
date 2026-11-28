package com.example.inchat.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
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
import com.example.inchat.ui.chat.CreateGroupScreen
import com.example.inchat.ui.chat.GroupChatScreen
import com.example.inchat.ui.chat.GroupInfoScreen
import com.example.inchat.ui.chat.NewMessageScreen
import com.example.inchat.ui.chat.GroupChatViewModel
import com.example.inchat.ui.home.HomeScreen
import com.example.inchat.ui.home.HomeViewModel
import com.example.inchat.ui.profile.EditProfileScreen
import com.example.inchat.ui.profile.ProfilePhotoScreen
import com.example.inchat.ui.profile.ProfileScreen
import com.example.inchat.ui.profile.PublicProfileScreen
import com.example.inchat.ui.profile.PublicProfileViewModel
import com.example.inchat.ui.search.SearchScreen
import com.example.inchat.ui.search.SearchViewModel
import com.example.inchat.ui.settings.PrivacySettingsScreen
import com.example.inchat.ui.settings.SettingsScreen
import com.example.inchat.ui.settings.DeviceTransferScreen
import com.example.inchat.ui.theme.GoogleSansFlex
import com.example.inchat.ui.theme.InChatMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun isConversationRoute(route: String?): Boolean =
    route?.startsWith("chat/") == true || route?.startsWith("group_chat/") == true

data class NotificationChatTarget(
    val recipientUserId: String,
    val chatId: String,
    val otherUserId: String,
    val otherUserNickname: String,
    val isGroup: Boolean
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
    onChatOpened: () -> Unit,
    onNotificationHandled: () -> Unit
) {

    val navController =
        rememberNavController()

    val navigationScope =
        rememberCoroutineScope()

    val pagerState =
        rememberPagerState(
            initialPage =
                0,

            pageCount = {
                bottomNavItems.size
            }
        )

    var searchFocusRequest by
    remember {
        mutableStateOf(
            0
        )
    }

    // Selection mode is owned by the navigation host so it can coordinate
    // the Home inbox actions with the app-level bottom navigation dock.
    var isHomeSelectionMode by remember {
        mutableStateOf(false)
    }

    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun dismissKeyboardBeforeOpeningConversation() {
        // Search/new-message fields can remain focused while their destination
        // is kept on the back stack. Clear that focus before mounting a composer.
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    var backNavigationInProgress by remember { mutableStateOf(false) }

    /*
     * Keep the NavHost start destination alive and serialize Back navigation.
     * The guard remains active through the return transition, so a rapid
     * second press cannot immediately finish the Activity after a chat pop.
     */
    fun handleBack() {
        if (backNavigationInProgress) return

        val activeRoute = navController.currentDestination?.route
        if (activeRoute == "home") {
            if (
                pagerState.currentPage != 0 ||
                pagerState.currentPageOffsetFraction != 0f
            ) {
                backNavigationInProgress = true
                navigationScope.launch {
                    try {
                        pagerState.animateScrollToPage(
                            page = 0,
                            animationSpec = InChatMotion.navigationSpring()
                        )
                    } finally {
                        backNavigationInProgress = false
                    }
                }
            } else {
                context.findActivity()?.finish()
            }
        } else {
            backNavigationInProgress = true
            if (!navController.popBackStack()) {
                backNavigationInProgress = false
                context.findActivity()?.finish()
            }
        }
    }

    fun handleBackToHome() {
        if (backNavigationInProgress) return
        backNavigationInProgress = true
        if (!navController.popBackStack("home", false)) {
            backNavigationInProgress = false
        }
    }

    val showBottomBar =
        currentRoute ==
                "home"

    /*
     * When returning from a nested destination, make sure the
     * persistent tab pager is fully settled before the Home
     * destination is displayed again. This prevents a partially
     * positioned pager from leaving the content area blank while
     * the bottom dock is already visible.
     */
    LaunchedEffect(
        currentRoute
    ) {
        if (currentRoute != "home") {
            isHomeSelectionMode = false
        }

        if (isConversationRoute(currentRoute)) {
            dismissKeyboardBeforeOpeningConversation()
            onChatOpened()
        }

        if (
            currentRoute ==
            "home" &&
            pagerState.currentPageOffsetFraction !=
            0f
        ) {
            pagerState.scrollToPage(
                pagerState.currentPage.coerceIn(
                    0,
                    bottomNavItems.lastIndex
                )
            )
        }

        // Navigation transitions are short; keep duplicate Back presses
        // blocked until the outgoing destination has fully settled.
        if (backNavigationInProgress) {
            delay(700)
            backNavigationInProgress = false
        }
    }

    /*
     * =========================================================
     * NOTIFICATION CHAT
     * =========================================================
     */

    LaunchedEffect(
        notificationTarget?.recipientUserId,
        notificationTarget?.chatId,
        notificationTarget?.otherUserId,
        notificationTarget?.isGroup,
        uid
    ) {

        val target =
            notificationTarget
                ?: return@LaunchedEffect

        if (target.recipientUserId != uid) {
            // A notification can outlive the session that received it.
            // Never route its conversation into a different signed-in account.
            onNotificationHandled()
            return@LaunchedEffect
        }

        dismissKeyboardBeforeOpeningConversation()
        if (target.isGroup) {
            navController.navigate(
                "group_chat/${target.chatId}"
            ) {
                launchSingleTop = true
            }
        } else {
            val encodedUsername =
                Uri.encode(target.otherUserNickname)

            navController.navigate(
                "chat/${target.otherUserId}" +
                        "?otherUserNickname=$encodedUsername"
            ) {
                launchSingleTop = true
            }
        }

        onNotificationHandled()
    }

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        /*
         * Child destinations already use their own Material 3
         * Scaffold/TopAppBar and therefore own their system-bar
         * insets. The root scaffold should only reserve space
         * for this custom dock.
         */
        contentWindowInsets =
            WindowInsets(
                0,
                0,
                0,
                0
            ),

        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar && !isHomeSelectionMode,
                enter = expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = InChatMotion.navigationSpring()
                ) + slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = InChatMotion.navigationSpring()
                ) + fadeIn(animationSpec = InChatMotion.effects(190)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = InChatMotion.navigationSpring()
                ) + slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = InChatMotion.navigationSpring()
                ) + fadeOut(animationSpec = InChatMotion.effects(150))
            ) {
                InChatBottomBar(
                    pagerState = pagerState,
                    onPageSelected = { page ->
                        navigationScope.launch {
                            pagerState.animateScrollToPage(
                                page = page,
                                animationSpec = InChatMotion.navigationSpring()
                            )
                        }
                    }
                )
            }
        }

    ) { innerPadding ->

        NavHost(

            navController =
                navController,

            startDestination =
                "home",

            // Short shared-axis movement plus a soft fade connects screens
            // without resizing or scaling the full content tree.
            enterTransition = {
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeIn(animationSpec = InChatMotion.effects(170))
                } else {
                    fadeIn(animationSpec = InChatMotion.effects(220)) +
                        slideInHorizontally(
                            initialOffsetX = { width -> width / 14 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },
            exitTransition = {
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeOut(animationSpec = InChatMotion.effects(145))
                } else {
                    fadeOut(animationSpec = InChatMotion.effects(155)) +
                        slideOutHorizontally(
                            targetOffsetX = { width -> -width / 28 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },
            popEnterTransition = {
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeIn(animationSpec = InChatMotion.effects(170))
                } else {
                    fadeIn(animationSpec = InChatMotion.effects(220)) +
                        slideInHorizontally(
                            initialOffsetX = { width -> -width / 14 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },
            popExitTransition = {
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeOut(animationSpec = InChatMotion.effects(145))
                } else {
                    fadeOut(animationSpec = InChatMotion.effects(155)) +
                        slideOutHorizontally(
                            targetOffsetX = { width -> width / 28 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },
            predictivePopEnterTransition = { _ ->
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeIn(animationSpec = InChatMotion.effects(170))
                } else {
                    fadeIn(animationSpec = InChatMotion.effects(220)) +
                        slideInHorizontally(
                            initialOffsetX = { width -> -width / 14 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },
            predictivePopExitTransition = { _ ->
                if (
                    isConversationRoute(initialState.destination.route) ||
                    isConversationRoute(targetState.destination.route)
                ) {
                    fadeOut(animationSpec = InChatMotion.effects(145))
                } else {
                    fadeOut(animationSpec = InChatMotion.effects(155)) +
                        slideOutHorizontally(
                            targetOffsetX = { width -> width / 28 },
                            animationSpec = InChatMotion.navigationSpring()
                        )
                }
            },

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
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

                // This destination-level handler is registered after NavHost's
                // own handler, so Back cannot pop the start destination.
                BackHandler {
                    handleBack()
                }

                MainTabPager(

                    pagerState =
                        pagerState,

                    uid =
                        uid,

                    username =
                        username,

                    authViewModel =
                        authViewModel,

                    homeViewModel =
                        homeViewModel,

                    navController =
                        navController,

                    searchFocusRequest =
                        searchFocusRequest,

                    homeSelectionMode =
                        isHomeSelectionMode,

                    onHomeSelectionModeChange = { enabled ->
                        isHomeSelectionMode = enabled
                    },
                    onBeforeConversationOpen = {
                        dismissKeyboardBeforeOpeningConversation()
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

                                handleBack()
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

                                            handleBack()
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

                                            handleBack()
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
             * EDIT PROFILE
             * ==================================================
             */

            composable(
                "edit_profile"
            ) {

                EditProfileScreen(

                    username =
                        username,

                    uid =
                        uid,

                    currentDisplayName =
                        authViewModel
                            .displayName
                            .value,

                    currentBio =
                        authViewModel
                            .bio
                            .value,

                    onBackClick = {

                        handleBack()
                    },

                    onProfileSaved = {
                            savedDisplayName,
                            savedBio ->

                        authViewModel
                            .applySavedProfileState(
                                displayName =
                                    savedDisplayName,

                                bio =
                                    savedBio
                            )

                        handleBack()
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

                        handleBack()
                    },

                    onPrivacyClick = {

                        navController.navigate(
                            "privacy_settings"
                        )
                    },

                    onDeviceTransferClick = {
                        navController.navigate("device_transfer")
                    }
                )
            }

            composable("device_transfer") {
                DeviceTransferScreen(
                    uid = uid,
                    onBackClick = { handleBack() }
                )
            }

            /*
             * ==================================================
             * PRIVACY SETTINGS
             * ==================================================
             */

            composable(
                "privacy_settings"
            ) {

                PrivacySettingsScreen(

                    uid =
                        uid,

                    onBackClick = {

                        handleBack()
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
                    "profile?username={username}&uid={uid}",

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
                        },

                        navArgument(
                            "uid"
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

                val otherUserId =
                    publicProfileBackStackEntry
                        .arguments
                        ?.getString(
                            "uid"
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

                    userId =
                        otherUserId,

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

                        handleBack()
                    }
                )
            }

            /*
             * ==================================================
             * NEW MESSAGE
             * ==================================================
             */

            composable(
                "new_message"
            ) {
                NewMessageScreen(
                    currentUserId = uid,
                    onBackClick = {
                        handleBack()
                    },
                    onUserClick = { user ->
                        dismissKeyboardBeforeOpeningConversation()
                        val encodedName = Uri.encode(user.username)
                        navController.navigate(
                            "chat/${user.uid}?otherUserNickname=$encodedName"
                        ) {
                            popUpTo("new_message") {
                                inclusive = true
                            }
                        }
                    }
                )
            }

            /*
             * ==================================================
             * CREATE GROUP
             * ==================================================
             */

            composable(
                "create_group"
            ) {

                CreateGroupScreen(
                    currentUserId =
                        uid,

                    onBackClick = {
                        handleBack()
                    },

                    onGroupCreated = { groupId ->
                        dismissKeyboardBeforeOpeningConversation()
                        navController.navigate(
                            "group_chat/" +
                                    groupId
                        ) {
                            popUpTo(
                                "create_group"
                            ) {
                                inclusive = true
                            }
                        }
                    }
                )
            }

            /*
             * ==================================================
             * GROUP CHAT
             * ==================================================
             */

            composable(
                route =
                    "group_chat/{chatId}",
                arguments =
                    listOf(
                        navArgument("chatId") {
                            type =
                                NavType.StringType
                        }
                    )
            ) { entry ->

                val groupId =
                    entry.arguments
                        ?.getString(
                            "chatId"
                        )
                        .orEmpty()

                val groupChatViewModel:
                        GroupChatViewModel =
                    viewModel(
                        entry
                    )

                GroupChatScreen(
                    currentUserId =
                        uid,

                    currentNickname =
                        username,

                    groupId =
                        groupId,

                    groupViewModel =
                        groupChatViewModel,

                    onBackClick = {
                        handleBack()
                    },

                    onGroupInfoClick = {
                        navController.navigate(
                            "group_info/" + groupId
                        )
                    }
                )
            }

            /*
             * ==================================================
             * GROUP INFO
             * ==================================================
             */

            composable(
                route = "group_info/{chatId}",
                arguments = listOf(
                    navArgument("chatId") {
                        type = NavType.StringType
                    }
                )
            ) { entry ->
                val infoGroupId =
                    entry.arguments
                        ?.getString("chatId")
                        .orEmpty()

                GroupInfoScreen(
                    currentUserId = uid,
                    groupId = infoGroupId,
                    onBackClick = {
                        handleBack()
                    },
                    onGroupLeft = {
                        handleBackToHome()
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
                        navArgument("otherUserId") {
                            type = NavType.StringType
                        },
                        navArgument("otherUserNickname") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )

            ) { entry ->

                val infoUserId = entry.arguments?.getString("otherUserId").orEmpty()
                val infoUsername = entry.arguments?.getString("otherUserNickname").orEmpty()
                val infoChatViewModel: ChatViewModel = viewModel(entry)

                ChatInfoScreen(
                    currentUserId = uid,
                    otherUserId = infoUserId,
                    otherUserNickname = infoUsername,
                    chatViewModel = infoChatViewModel,
                    onBackClick = { handleBack() }
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
                        handleBack()
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
 * MAIN TAB PAGER
 * ============================================================
 */
@OptIn(
    ExperimentalFoundationApi::class
)
@Composable
private fun MainTabPager(
    pagerState:
        PagerState,

    uid:
        String,

    username:
        String,

    authViewModel:
        AuthViewModel,

    homeViewModel:
        HomeViewModel,

    navController:
        NavHostController,

    searchFocusRequest:
        Int,

    homeSelectionMode:
        Boolean,

    onHomeSelectionModeChange:
        (Boolean) -> Unit,

    onBeforeConversationOpen:
        () -> Unit
) {

    val searchViewModel:
        SearchViewModel =
        viewModel()

    HorizontalPager(

        state =
            pagerState,

        modifier =
            Modifier.fillMaxSize(),

        // Selection mode is a focused inbox task; prevent tab swipes while
        // its navigation dock is intentionally collapsed.
        userScrollEnabled =
            !homeSelectionMode,

        beyondViewportPageCount =
            1,

        pageSpacing =
            0.dp
    ) { page ->

        when (
            page
        ) {

            0 -> {

                HomeScreen(

                    authViewModel =
                        authViewModel,

                    homeViewModel =
                        homeViewModel,

                    onConversationClick = {
                            conversation ->
                        onBeforeConversationOpen()

                        if (
                            conversation.chatType ==
                            "group"
                        ) {

                            navController.navigate(
                                "group_chat/" +
                                        conversation.chatId
                            )

                        } else {

                            val encodedUsername =
                                Uri.encode(
                                    conversation.otherUsername
                                )

                            navController.navigate(
                                "chat/" +
                                        conversation.otherUserId +
                                        "?otherUserNickname=" +
                                        encodedUsername
                            )
                        }
                    },

                    onNewMessageClick = {
                        navController.navigate("new_message")
                    },

                    onCreateGroupClick = {
                        navController.navigate("create_group")
                    },

                    homeSelectionMode = homeSelectionMode,
                    onSelectionModeChange = onHomeSelectionModeChange
                )
            }

            1 -> {

                SearchScreen(

                    currentUserId =
                        uid,

                    searchViewModel =
                        searchViewModel,

                    searchFocusRequest =
                        searchFocusRequest,

                    onUserClick = {
                            user ->

                        val encodedUsername =
                            Uri.encode(
                                user.username
                            )

                        navController.navigate(
                            "profile?username=" +
                                    encodedUsername +
                                    "&uid=" +
                                    Uri.encode(
                                        user.uid
                                    )
                        )
                    }
                )
            }

            2 -> {

                ProfileScreen(

                    username =
                        username,

                    uid =
                        uid,

                    authViewModel =
                        authViewModel,

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
                    },
                        
                        onEditProfileClick = {

                            navController.navigate(
                                "edit_profile"
                            ) {

                                launchSingleTop =
                                    true
                            }
                        }
                )
            }
        }
    }
}

/*
 * ============================================================
 * INCHAT BOTTOM NAVIGATION
 * ============================================================
 *
 * A compact floating dock with three equal destinations.
 * HorizontalPager keeps its normal swipe gesture; the dock
 * itself uses ordinary taps only.
 */
@Composable
private fun InChatBottomBar(
    pagerState: PagerState,
    onPageSelected: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(60.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(50.dp)
                )
                .padding(4.dp)
        ) {
            // Every destination keeps one fixed-width slot. Only the
            // selection pill moves, so the other icons never get pushed.
            val slotWidth = maxWidth / bottomNavItems.size
            // Track the pager continuously during finger swipes and tab animations.
            val pagerPosition = (
                pagerState.currentPage.toFloat() +
                    pagerState.currentPageOffsetFraction
                ).coerceIn(0f, bottomNavItems.lastIndex.toFloat())
            val indicatorOffset = slotWidth * pagerPosition

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .size(
                        width = slotWidth,
                        height = maxHeight
                    )
                    .clip(RoundedCornerShape(50.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomNavItems.forEachIndexed { index, item ->
                    BottomNavigationItem(
                        item = item,
                        selected = pagerState.targetPage == index,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        onClick = { onPageSelected(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationItem(
    item: BottomNavItem,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = InChatMotion.effects(180),
        label = "bottomNavContent"
    )

    val iconSize by animateFloatAsState(
        targetValue = if (selected) 25f else 23f,
        animationSpec = InChatMotion.contentSpring(),
        label = "bottomNavIconSize"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(40.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(iconSize.dp)
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = InChatMotion.effects(170)
            ) + androidx.compose.animation.expandHorizontally(
                expandFrom = Alignment.Start,
                animationSpec = InChatMotion.contentSpring()
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = InChatMotion.effects(140)
            ) + androidx.compose.animation.shrinkHorizontally(
                shrinkTowards = Alignment.Start,
                animationSpec = InChatMotion.contentSpring()
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(5.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = GoogleSansFlex,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.1.sp
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1
                )
            }
        }
    }
}
