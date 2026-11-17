package com.example.contacts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatters
import com.example.domain.model.FriendRequest
import com.example.domain.model.UserProfile
import com.example.presentation.components.SnrAvatar
import com.example.presentation.components.SnrEmptyState
import com.example.presentation.components.SnrErrorBanner
import com.example.presentation.components.SnrLoadingState
import com.example.presentation.components.SnrSearchBar
import com.example.ui.theme.CyberMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenConversation: (String) -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val incoming by viewModel.incomingRequests.collectAsStateWithLifecycle()
    val outgoing by viewModel.outgoingRequests.collectAsStateWithLifecycle()
    val discoverUsers by viewModel.discoverUsers.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var inspectedUser by remember { mutableStateOf<UserProfile?>(null) }
    var friendToRemove by remember { mutableStateOf<UserProfile?>(null) }
    var reportTarget by remember { mutableStateOf<UserProfile?>(null) }
    var reportReason by remember { mutableStateOf("Spam or harassment") }
    var reportDetails by remember { mutableStateOf("") }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            snackbarHostState.showSnackbar(statusMessage!!)
            viewModel.clearFeedback()
        }
    }

    val friendIds = remember(friends) { friends.map { it.userId }.toSet() }
    val outgoingTargetIds = remember(outgoing) { outgoing.map { it.toUserId }.toSet() }
    val incomingSenderIds = remember(incoming) { incoming.map { it.fromUserId }.toSet() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Contacts",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "[TRUSTED_PEERS // ${friends.size}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CyberMonoFontFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        if (selectedTab != ContactsTab.DISCOVER) {
                            IconButton(
                                onClick = { viewModel.selectTab(ContactsTab.DISCOVER) },
                                modifier = Modifier.testTag("add_friend_top_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = "Add contact",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                ContactsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tab.label,
                                    fontFamily = CyberMonoFontFamily
                                )
                                if (tab == ContactsTab.REQUESTS && incoming.isNotEmpty()) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(
                                            text = incoming.size.toString(),
                                            fontFamily = CyberMonoFontFamily
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("contacts_tab_${tab.name.lowercase()}")
                    )
                }
            }

            if (errorMessage != null) {
                SnrErrorBanner(
                    message = errorMessage!!,
                    onDismiss = viewModel::clearFeedback,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when (selectedTab) {
                ContactsTab.FRIENDS -> {
                    val filteredFriends = remember(friends, searchQuery) {
                        if (searchQuery.isBlank()) friends
                        else friends.filter {
                            it.displayName.contains(searchQuery, true) ||
                                it.username.contains(searchQuery, true)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        SnrSearchBar(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            placeholder = "filter contacts...",
                            testTag = "friends_search_input"
                        )
                        Spacer(Modifier.height(12.dp))
                        if (filteredFriends.isEmpty()) {
                            SnrEmptyState(
                                icon = Icons.Default.PeopleOutline,
                                title = "No contacts linked",
                                subtitle = "Search operators by @username or display name in Discover to connect.",
                                actionLabel = "Discover Users",
                                onAction = { viewModel.selectTab(ContactsTab.DISCOVER) }
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(filteredFriends, key = { it.userId }) { friend ->
                                    var showFriendMenu by remember(friend.userId) { mutableStateOf(false) }
                                    UserRowCard(
                                        user = friend,
                                        onClick = { inspectedUser = friend },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        viewModel.startChatWith(friend, onOpenConversation)
                                                    },
                                                    modifier = Modifier.testTag("message_contact_button_${friend.userId}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ChatBubble,
                                                        contentDescription = "Message ${friend.displayName}",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Box {
                                                    IconButton(
                                                        onClick = { showFriendMenu = true },
                                                        modifier = Modifier.testTag("contact_more_button_${friend.userId}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.MoreVert,
                                                            contentDescription = "Contact options for ${friend.displayName}",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    DropdownMenu(
                                                        expanded = showFriendMenu,
                                                        onDismissRequest = { showFriendMenu = false }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = "Remove Contact",
                                                                    color = MaterialTheme.colorScheme.error
                                                                )
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    imageVector = Icons.Default.PersonRemove,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.error
                                                                )
                                                            },
                                                            onClick = {
                                                                showFriendMenu = false
                                                                friendToRemove = friend
                                                            },
                                                            modifier = Modifier.testTag("remove_contact_button_${friend.userId}")
                                                        )
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = "Block User",
                                                                    color = MaterialTheme.colorScheme.error
                                                                )
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    imageVector = Icons.Default.Block,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.error
                                                                )
                                                            },
                                                            onClick = {
                                                                showFriendMenu = false
                                                                viewModel.blockUser(friend)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                ContactsTab.REQUESTS -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (incoming.isEmpty() && outgoing.isEmpty()) {
                            item {
                                SnrEmptyState(
                                    icon = Icons.Default.PersonAdd,
                                    title = "No pending requests",
                                    subtitle = "Incoming and outgoing connection requests appear here."
                                )
                            }
                        } else {
                            if (incoming.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "INCOMING_REQUESTS (${incoming.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(incoming, key = { it.id }) { req ->
                                    IncomingRequestCard(
                                        request = req,
                                        onAccept = { viewModel.acceptRequest(req) },
                                        onReject = { viewModel.rejectOrCancelRequest(req.id, false) }
                                    )
                                }
                            }

                            if (outgoing.isNotEmpty()) {
                                item {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "SENT_REQUESTS (${outgoing.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                items(outgoing, key = { it.id }) { req ->
                                    OutgoingRequestCard(
                                        request = req,
                                        onCancel = { viewModel.rejectOrCancelRequest(req.id, true) }
                                    )
                                }
                            }
                        }
                    }
                }

                ContactsTab.DISCOVER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        SnrSearchBar(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            placeholder = "lookup @username or name...",
                            testTag = "discover_search_input"
                        )
                        Spacer(Modifier.height(12.dp))
                        when {
                            isSearching -> SnrLoadingState("SCANNING_DIRECTORY...")
                            discoverUsers.isEmpty() -> SnrEmptyState(
                                icon = Icons.Default.Search,
                                title = "No users found",
                                subtitle = "Try searching an exact @username or display name."
                            )
                            else -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp)
                                ) {
                                    items(discoverUsers, key = { it.userId }) { user ->
                                        val isFriend = user.userId in friendIds
                                        val isSent = user.userId in outgoingTargetIds
                                        val isIncoming = user.userId in incomingSenderIds
                                        UserRowCard(
                                            user = user,
                                            onClick = { inspectedUser = user },
                                            trailingContent = {
                                                when {
                                                    isFriend -> FilledTonalButton(
                                                        onClick = {
                                                            viewModel.startChatWith(user, onOpenConversation)
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("Chat")
                                                    }
                                                    isSent -> OutlinedButton(
                                                        onClick = {
                                                            val req = outgoing.firstOrNull { it.toUserId == user.userId }
                                                            if (req != null) viewModel.rejectOrCancelRequest(req.id, true)
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("Pending")
                                                    }
                                                    isIncoming -> Button(
                                                        onClick = {
                                                            val req = incoming.firstOrNull { it.fromUserId == user.userId }
                                                            if (req != null) viewModel.acceptRequest(req)
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("Accept")
                                                    }
                                                    else -> Button(
                                                        onClick = { viewModel.sendFriendRequest(user) },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("Add")
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    friendToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Remove Contact?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Remove ${target.displayName} (@${target.username}) from your contacts? You can send a friend request again later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFriend(target)
                        friendToRemove = null
                    },
                    modifier = Modifier.testTag("confirm_remove_contact_button")
                ) {
                    Text(
                        text = "Remove Contact",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { friendToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    inspectedUser?.let { user ->
        val isFriend = user.userId in friendIds
        val isSent = user.userId in outgoingTargetIds
        val incomingReq = incoming.firstOrNull { it.fromUserId == user.userId }
        val outgoingReq = outgoing.firstOrNull { it.toUserId == user.userId }

        ModalBottomSheet(
            onDismissRequest = { inspectedUser = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SnrAvatar(
                    name = user.displayName,
                    avatarData = user.avatarData,
                    size = 84.dp,
                    showOnlineDot = user.showOnlineStatus,
                    isOnline = user.online
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = CyberMonoFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
                if (user.bio.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = user.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                val presence = when {
                    user.online && user.showOnlineStatus -> "ONLINE"
                    user.showLastSeen -> TimeFormatters.formatLastSeen(user.lastSeenAt)
                    else -> TimeFormatters.formatMemberSince(user.createdAt)
                }
                Text(
                    text = "$presence // ${TimeFormatters.formatMemberSince(user.createdAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CyberMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isFriend) {
                        Button(
                            onClick = {
                                inspectedUser = null
                                viewModel.startChatWith(user, onOpenConversation)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ChatBubble, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Message")
                        }
                        OutlinedButton(
                            onClick = {
                                inspectedUser = null
                                friendToRemove = user
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sheet_remove_contact_button")
                        ) {
                            Icon(
                                Icons.Default.PersonRemove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    } else if (incomingReq != null) {
                        Button(
                            onClick = {
                                viewModel.acceptRequest(incomingReq)
                                inspectedUser = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Accept Request")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.rejectOrCancelRequest(incomingReq.id, false)
                                inspectedUser = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Decline")
                        }
                    } else if (isSent && outgoingReq != null) {
                        OutlinedButton(
                            onClick = {
                                viewModel.rejectOrCancelRequest(outgoingReq.id, true)
                                inspectedUser = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel Friend Request")
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.sendFriendRequest(user)
                                inspectedUser = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Send Friend Request")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = {
                            viewModel.blockUser(user)
                            inspectedUser = null
                        }
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            reportTarget = user
                            inspectedUser = null
                        }
                    ) {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Report")
                    }
                }
            }
        }
    }

    reportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Report @${target.username}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it.take(80) },
                        label = { Text("Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it.take(500) },
                        label = { Text("Details") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.reportUser(target.userId, reportReason, reportDetails)
                        reportTarget = null
                        reportDetails = ""
                    }
                ) {
                    Text("Submit")
                }
            },
            dismissButton = {
                TextButton(onClick = { reportTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun UserRowCard(
    user: UserProfile,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = user.displayName,
                avatarData = user.avatarData,
                size = 48.dp,
                showOnlineDot = user.showOnlineStatus,
                isOnline = user.online
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "@${user.username}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = CyberMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (user.online && user.showOnlineStatus) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "[ONLINE]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = CyberMonoFontFamily,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (user.bio.isNotBlank()) {
                    Text(
                        text = user.bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
private fun IncomingRequestCard(
    request: FriendRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = request.fromDisplayName,
                avatarData = request.fromAvatarData,
                size = 46.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(request.fromDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "@${request.fromUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CyberMonoFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onReject) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Decline request",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onAccept) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Accept request",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun OutgoingRequestCard(
    request: FriendRequest,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = request.toDisplayName,
                avatarData = request.toAvatarData,
                size = 46.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(request.toDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "@${request.toUsername} // PENDING",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CyberMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}
