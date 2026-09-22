package com.example.inchat.ui.navigation

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
import com.example.inchat.ui.profile.EditProfileScreen
import com.example.inchat.ui.profile.ProfilePhotoScreen
import com.example.inchat.ui.profile.ProfileScreen
import com.example.inchat.ui.profile.PublicProfileScreen
import com.example.inchat.ui.profile.PublicProfileViewModel
import com.example.inchat.ui.search.SearchScreen
import com.example.inchat.ui.search.SearchViewModel
import com.example.inchat.ui.settings.PrivacySettingsScreen
import com.example.inchat.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

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
    }

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

            if (
                showBottomBar
            ) {

                InChatBottomBar(

                    pagerState =
                        pagerState,

                    onPageSelected = { page ->

                        navigationScope.launch {

                            pagerState
                                .animateScrollToPage(
                                    page
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
                        searchFocusRequest
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

                        navController
                            .popBackStack()
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

                        navController
                            .popBackStack()
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

                        navController
                            .popBackStack()
                    },

                    onPrivacyClick = {

                        navController.navigate(
                            "privacy_settings"
                        )
                    }
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
        Int
) {

    val pagerScope =
        rememberCoroutineScope()

    val searchViewModel:
        SearchViewModel =
        viewModel()

    HorizontalPager(

        state =
            pagerState,

        modifier =
            Modifier.fillMaxSize(),

        userScrollEnabled =
            true,

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
                    },

                    onProfileClick = {

                        pagerScope.launch {

                            pagerState
                                .animateScrollToPage(
                                    2
                                )
                        }
                    }
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
                                    encodedUsername
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

                    onBackClick = {

                        pagerScope.launch {

                            pagerState
                                .animateScrollToPage(
                                    0
                                )
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
 * CUSTOM BOTTOM NAVIGATION
 * ============================================================
 *
 * The dock supports two independent interactions:
 * 1. Normal tap selects a tab.
 * 2. Long-press + horizontal drag scrubs between tabs continuously.
 *
 * The scrub gesture is mapped from dock distance to pager distance, so
 * moving one tab-width with the finger moves the HorizontalPager by one
 * full page-width. The indicator and page therefore track the finger.
 */
@OptIn(
    ExperimentalFoundationApi::class
)
@Composable
private fun InChatBottomBar(
    pagerState:
        PagerState,

    onPageSelected:
        (Int) -> Unit
) {

    val dragScope =
        rememberCoroutineScope()

    val selectedPage =
        pagerState.currentPage

    val pagePosition =
        (
            pagerState.currentPage +
                    pagerState.currentPageOffsetFraction
            )
            .coerceIn(
                0f,
                bottomNavItems.lastIndex
                    .toFloat()
            )

    Box(

        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    bottom =
                        8.dp
                ),

        contentAlignment =
            Alignment.Center
    ) {

        Surface(

            color =
                MaterialTheme
                    .colorScheme
                    .surface,

            shape =
                RoundedCornerShape(
                    26.dp
                ),

            shadowElevation =
                5.dp,

            tonalElevation =
                0.dp
        ) {

            BoxWithConstraints(

                modifier =
                    Modifier
                        .width(
                            216.dp
                        )
                        .height(
                            58.dp
                        )
                        .padding(
                            4.dp
                        )
                        .pointerInput(
                            pagerState
                        ) {

                            val itemWidthPx =
                                size.width /
                                        bottomNavItems.size.toFloat()

                            var dragJob: kotlinx.coroutines.Job? = null

                            detectDragGesturesAfterLongPress(

                                onDragStart = {
                                    // Intentionally do not trigger Search focus.
                                    // Crossing/touching Search while scrubbing must
                                    // remain a navigation gesture only.
                                    dragJob?.cancel()
                                },

                                onDrag = {
                                        change,
                                        dragAmount ->

                                    val pageWidthPx =
                                        pagerState
                                            .layoutInfo
                                            .pageSize
                                            .toFloat()

                                    if (
                                        pageWidthPx >
                                        0f &&
                                        itemWidthPx >
                                        0f
                                    ) {

                                        change.consume()

                                        val pageDelta =
                                            dragAmount.x *
                                                    (
                                                        pageWidthPx /
                                                                itemWidthPx
                                                        )

                                        dragJob?.cancel()

                                        dragJob =
                                            dragScope.launch {
                                                pagerState.scroll(
                                                    MutatePriority.UserInput
                                                ) {
                                                    scrollBy(
                                                        pageDelta
                                                    )
                                                }
                                            }
                                    }
                                },

                                onDragEnd = {

                                    dragJob?.cancel()
                                    dragJob = null

                                    val targetPage =
                                        (
                                            pagerState.currentPage +
                                                    pagerState.currentPageOffsetFraction
                                            )
                                            .roundToInt()
                                            .coerceIn(
                                                0,
                                                bottomNavItems.lastIndex
                                            )

                                    onPageSelected(
                                        targetPage
                                    )
                                },

                                onDragCancel = {

                                    dragJob?.cancel()
                                    dragJob = null

                                    val targetPage =
                                        (
                                            pagerState.currentPage +
                                                    pagerState.currentPageOffsetFraction
                                            )
                                            .roundToInt()
                                            .coerceIn(
                                                0,
                                                bottomNavItems.lastIndex
                                            )

                                    onPageSelected(
                                        targetPage
                                    )
                                }
                            )
                        }
            ) {

                val itemWidth =
                    maxWidth /
                            bottomNavItems.size

                val swipeDistance =
                    abs(
                        pagePosition -
                                pagerState.settledPage
                        )
                        .coerceIn(
                            0f,
                            1f
                        )

                val liquidWidth =
                    itemWidth *
                            (
                                1f +
                                        swipeDistance * 0.50f
                                )

                val itemCenter =
                    itemWidth *
                            pagePosition +
                            itemWidth / 2f

                val liquidLeft =
                    (
                        itemCenter -
                                liquidWidth / 2f
                        )
                        .coerceIn(
                            0.dp,
                            maxWidth -
                                    liquidWidth
                        )

                val liquidAlpha =
                    0.12f +
                            swipeDistance * 0.04f

                Box(

                    modifier =
                        Modifier
                            .offset(
                                x =
                                    liquidLeft
                            )
                            .width(
                                liquidWidth
                            )
                            .height(
                                50.dp
                            )
                            .clip(
                                RoundedCornerShape(
                                    25.dp
                                )
                            )
                            .background(
                                MaterialTheme
                                    .colorScheme
                                    .primary
                                    .copy(
                                        alpha =
                                            liquidAlpha
                                    )
                            )
                )

                Row(

                    modifier =
                        Modifier.fillMaxSize(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    bottomNavItems.forEachIndexed {
                            index,
                            item ->

                        val selected =
                            selectedPage ==
                                    index

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

                                onPageSelected(
                                    index
                                )
                            }
                        )
                    }
                }
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
                    interactionSource = null,
                    indication = null,
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
