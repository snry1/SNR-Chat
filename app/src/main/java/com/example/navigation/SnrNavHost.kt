package com.example.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.example.ui.theme.CyberMonoFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.authentication.AuthSessionState
import com.example.authentication.AuthViewModel
import com.example.authentication.LoginScreen
import com.example.authentication.ProfileSetupScreen
import com.example.authentication.SplashScreen
import com.example.calls.CallOverlayScreen
import com.example.contacts.ContactsScreen
import com.example.contacts.ContactsTab
import com.example.contacts.ContactsViewModel
import com.example.core.NotificationType
import com.example.core.SnrContainer
import com.example.core.UiState
import com.example.groups.GroupDetailScreen
import com.example.groups.GroupDetailViewModel
import com.example.groups.GroupsScreen
import com.example.groups.GroupsViewModel
import com.example.messaging.ChatRoomScreen
import com.example.messaging.ChatRoomViewModel
import com.example.messaging.ChatsScreen
import com.example.messaging.ChatsViewModel
import com.example.profile.ProfileScreen
import com.example.profile.ProfileViewModel
import kotlinx.coroutines.delay

object SnrRoutes {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val GROUPS = "groups"
    const val PROFILE = "profile"
    const val CHAT_ROOM = "chat/{conversationId}"
    const val GROUP_DETAIL = "group/{groupId}"

    fun chatRoom(conversationId: String) = "chat/$conversationId"
    fun groupDetail(groupId: String) = "group/$groupId"
}

private enum class MainTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CHATS(SnrRoutes.CHATS, "Chats", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    CONTACTS(SnrRoutes.CONTACTS, "Contacts", Icons.Filled.People, Icons.Outlined.PeopleOutline),
    GROUPS(SnrRoutes.GROUPS, "Groups", Icons.Filled.Group, Icons.Outlined.Group),
    PROFILE(SnrRoutes.PROFILE, "Profile", Icons.Filled.Person, Icons.Outlined.PersonOutline)
}

@Composable
fun SnrAppNavigation(
    container: SnrContainer,
    isDarkTheme: Boolean,
    initialTargetType: String? = null,
    initialTargetId: String? = null
) {
    val context = LocalContext.current
    val authViewModel: AuthViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AuthViewModel(container.authRepository, container.userRepository)
            }
        }
    )

    val sessionState by authViewModel.sessionState.collectAsStateWithLifecycle()
    val loginState by authViewModel.loginState.collectAsStateWithLifecycle()
    val setupState by authViewModel.setupState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        delay(350)
        authViewModel.attemptAutoSignIn(context)
    }

    when (val session = sessionState) {
        AuthSessionState.Checking -> {
            SplashScreen()
        }
        AuthSessionState.Unauthenticated -> {
            LoginScreen(
                uiState = loginState,
                onGoogleSignIn = authViewModel::signInWithGoogle,
                onClearError = authViewModel::clearLoginError
            )
        }
        is AuthSessionState.NeedsProfileSetup -> {
            ProfileSetupScreen(
                uiState = setupState,
                onDisplayNameChange = authViewModel::onSetupDisplayNameChange,
                onUsernameChange = authViewModel::onSetupUsernameChange,
                onBioChange = authViewModel::onSetupBioChange,
                onAvatarPicked = authViewModel::onSetupAvatarPicked,
                onSubmit = authViewModel::completeProfileSetup,
                onSignOut = { authViewModel.signOut(context) }
            )
        }
        is AuthSessionState.Authenticated -> {
            val uid = session.userProfile.userId
            key(uid) {
                AuthenticatedSnrApp(
                    uid = uid,
                    container = container,
                    isDarkTheme = isDarkTheme,
                    initialTargetType = initialTargetType,
                    initialTargetId = initialTargetId
                )
            }
        }
    }
}

@Composable
private fun AuthenticatedSnrApp(
    uid: String,
    container: SnrContainer,
    isDarkTheme: Boolean,
    initialTargetType: String?,
    initialTargetId: String?
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* handled gracefully */ }

    val callAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            container.callController.acceptIncomingCall()
        } else {
            container.callController.declineIncomingCall()
        }
    }

    val activeCall by container.callController.activeCall.collectAsStateWithLifecycle()
    val callNotice by container.callController.callNotice.collectAsStateWithLifecycle()
    val callSnackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(callNotice) {
        val msg = callNotice ?: return@LaunchedEffect
        callSnackbarHost.showSnackbar(msg)
        container.callController.clearNotice()
    }

    LaunchedEffect(uid) {
        withFrameNanos { }
        delay(300)
        container.callController.startObservingUser(uid)
    }

    DisposableEffect(uid) {
        onDispose {
            container.callController.stopObservingUser()
        }
    }

    LaunchedEffect(uid) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                withFrameNanos { }
                withFrameNanos { }
                delay(1000)
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val chatsViewModel: ChatsViewModel = viewModel(
        key = "chats_$uid",
        factory = viewModelFactory {
            initializer {
                ChatsViewModel(
                    currentUserId = uid,
                    chatRepository = container.chatRepository,
                    userRepository = container.userRepository,
                    contactsRepository = container.contactsRepository,
                    groupRepository = container.groupRepository,
                    notificationRepository = container.notificationRepository,
                    notificationHelper = container.notificationHelper
                )
            }
        }
    )

    val contactsViewModel: ContactsViewModel = viewModel(
        key = "contacts_$uid",
        factory = viewModelFactory {
            initializer {
                ContactsViewModel(
                    currentUserId = uid,
                    userRepository = container.userRepository,
                    contactsRepository = container.contactsRepository,
                    chatRepository = container.chatRepository
                )
            }
        }
    )

    val groupsViewModel: GroupsViewModel = viewModel(
        key = "groups_$uid",
        factory = viewModelFactory {
            initializer {
                GroupsViewModel(
                    currentUserId = uid,
                    groupRepository = container.groupRepository,
                    contactsRepository = container.contactsRepository,
                    userRepository = container.userRepository
                )
            }
        }
    )

    val profileViewModel: ProfileViewModel = viewModel(
        key = "profile_$uid",
        factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    currentUserId = uid,
                    userRepository = container.userRepository,
                    authRepository = container.authRepository,
                    themePreferenceStore = container.themePreferenceStore
                )
            }
        }
    )

    // Handle deep link from notification tap
    LaunchedEffect(initialTargetType, initialTargetId) {
        if (!initialTargetType.isNullOrBlank() && !initialTargetId.isNullOrBlank()) {
            when (NotificationType.fromRaw(initialTargetType)) {
                NotificationType.PERSONAL_MESSAGE,
                NotificationType.GROUP_MESSAGE -> navController.navigate(SnrRoutes.chatRoom(initialTargetId))
                NotificationType.FRIEND_REQUEST -> {
                    contactsViewModel.selectTab(ContactsTab.REQUESTS)
                    navController.navigate(SnrRoutes.CONTACTS)
                }
                NotificationType.FRIEND_ACCEPTED -> navController.navigate(SnrRoutes.CONTACTS)
                NotificationType.GROUP_INVITE -> navController.navigate(SnrRoutes.GROUPS)
            }
        }
    }

    val chatsState by chatsViewModel.conversationsState.collectAsStateWithLifecycle()
    val incomingFriendRequests by contactsViewModel.incomingRequests.collectAsStateWithLifecycle()
    val incomingGroupInvites by groupsViewModel.incomingInvitations.collectAsStateWithLifecycle()

    val unreadMessagesCount = (chatsState as? UiState.Success)?.data
        ?.sumOf { it.unreadCount }?.toInt() ?: 0

    val showMainNav = currentRoute in MainTab.entries.map { it.route }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showMainNav && !useRail) {
                    Column {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            MainTab.entries.forEach { tab ->
                                val selected = currentRoute == tab.route
                                val badgeCount = when (tab) {
                                    MainTab.CHATS -> unreadMessagesCount
                                    MainTab.CONTACTS -> incomingFriendRequests.size
                                    MainTab.GROUPS -> incomingGroupInvites.size
                                    MainTab.PROFILE -> 0
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (badgeCount > 0) {
                                                    Badge(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                                    ) {
                                                        Text(
                                                            text = badgeCount.coerceAtMost(99).toString(),
                                                            fontFamily = CyberMonoFontFamily
                                                        )
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontFamily = CyberMonoFontFamily
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showMainNav && useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        MainTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            val badgeCount = when (tab) {
                                MainTab.CHATS -> unreadMessagesCount
                                MainTab.CONTACTS -> incomingFriendRequests.size
                                MainTab.GROUPS -> incomingGroupInvites.size
                                MainTab.PROFILE -> 0
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                ) {
                                                    Text(
                                                        text = badgeCount.coerceAtMost(99).toString(),
                                                        fontFamily = CyberMonoFontFamily
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        fontFamily = CyberMonoFontFamily
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    VerticalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = SnrRoutes.CHATS
                    ) {
                        composable(SnrRoutes.CHATS) {
                            ChatsScreen(
                                viewModel = chatsViewModel,
                                onOpenConversation = { convId ->
                                    navController.navigate(SnrRoutes.chatRoom(convId))
                                },
                                onGoToContacts = { navigateToTab(SnrRoutes.CONTACTS) },
                                onNotificationTarget = { type, targetId ->
                                    when (type) {
                                        NotificationType.PERSONAL_MESSAGE,
                                        NotificationType.GROUP_MESSAGE -> {
                                            if (targetId.isNotBlank()) {
                                                navController.navigate(SnrRoutes.chatRoom(targetId))
                                            }
                                        }
                                        NotificationType.FRIEND_REQUEST -> {
                                            contactsViewModel.selectTab(ContactsTab.REQUESTS)
                                            navigateToTab(SnrRoutes.CONTACTS)
                                        }
                                        NotificationType.FRIEND_ACCEPTED -> navigateToTab(SnrRoutes.CONTACTS)
                                        NotificationType.GROUP_INVITE -> navigateToTab(SnrRoutes.GROUPS)
                                    }
                                }
                            )
                        }

                        composable(SnrRoutes.CONTACTS) {
                            ContactsScreen(
                                viewModel = contactsViewModel,
                                onOpenConversation = { convId ->
                                    navController.navigate(SnrRoutes.chatRoom(convId))
                                }
                            )
                        }

                        composable(SnrRoutes.GROUPS) {
                            GroupsScreen(
                                viewModel = groupsViewModel,
                                currentUserId = uid,
                                onOpenGroupChat = { convId ->
                                    navController.navigate(SnrRoutes.chatRoom(convId))
                                },
                                onOpenGroupManage = { groupId ->
                                    navController.navigate(SnrRoutes.groupDetail(groupId))
                                }
                            )
                        }

                        composable(SnrRoutes.PROFILE) {
                            ProfileScreen(viewModel = profileViewModel)
                        }

                        composable(
                            route = SnrRoutes.CHAT_ROOM,
                            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                        ) { entry ->
                            val conversationId = entry.arguments?.getString("conversationId") ?: return@composable
                            val roomViewModel: ChatRoomViewModel = viewModel(
                                key = "room_${uid}_$conversationId",
                                factory = viewModelFactory {
                                    initializer {
                                        ChatRoomViewModel(
                                            conversationId = conversationId,
                                            currentUserId = uid,
                                            chatRepository = container.chatRepository,
                                            userRepository = container.userRepository,
                                            voiceRecorder = container.createVoiceRecorder(),
                                            voicePlayer = container.voicePlayer
                                        )
                                    }
                                }
                            )
                            ChatRoomScreen(
                                viewModel = roomViewModel,
                                currentUserId = uid,
                                isDarkTheme = isDarkTheme,
                                onBack = { navController.popBackStack() },
                                onOpenGroupDetail = { groupId ->
                                    navController.navigate(SnrRoutes.groupDetail(groupId))
                                },
                                onStartVoiceCall = { conv, caller, callee ->
                                    container.callController.startOutgoingCall(conv, caller, callee)
                                }
                            )
                        }

                        composable(
                            route = SnrRoutes.GROUP_DETAIL,
                            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                        ) { entry ->
                            val groupId = entry.arguments?.getString("groupId") ?: return@composable
                            val detailViewModel: GroupDetailViewModel = viewModel(
                                key = "group_${uid}_$groupId",
                                factory = viewModelFactory {
                                    initializer {
                                        GroupDetailViewModel(
                                            groupId = groupId,
                                            currentUserId = uid,
                                            groupRepository = container.groupRepository,
                                            contactsRepository = container.contactsRepository,
                                            userRepository = container.userRepository
                                        )
                                    }
                                }
                            )
                            GroupDetailScreen(
                                viewModel = detailViewModel,
                                currentUserId = uid,
                                onBack = { navController.popBackStack() },
                                onOpenChat = { convId ->
                                    navController.navigate(SnrRoutes.chatRoom(convId)) {
                                        launchSingleTop = true
                                    }
                                },
                                onLeftGroup = {
                                    navController.popBackStack(SnrRoutes.GROUPS, inclusive = false)
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = callSnackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )

        activeCall?.let { callState ->
            CallOverlayScreen(
                callState = callState,
                onAcceptIncoming = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        container.callController.acceptIncomingCall()
                    } else {
                        callAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onDeclineIncoming = { container.callController.declineIncomingCall() },
                onHangUp = { container.callController.hangUp() },
                onToggleMute = { container.callController.toggleMute() },
                onToggleSpeaker = { container.callController.toggleSpeaker() }
            )
        }
    }
}
