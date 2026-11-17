package com.example.messaging

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.example.core.ConversationType
import com.example.core.NotificationType
import com.example.core.TimeFormatters
import com.example.core.UiState
import com.example.domain.model.AppNotification
import com.example.domain.model.UserProfile
import com.example.presentation.components.SnrAvatar
import com.example.presentation.components.SnrBrandLogo
import com.example.presentation.components.SnrEmptyState
import com.example.presentation.components.SnrErrorBanner
import com.example.presentation.components.SnrLoadingState
import com.example.presentation.components.SnrSearchBar
import com.example.ui.theme.CyberMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onOpenConversation: (String) -> Unit,
    onGoToContacts: () -> Unit,
    onNotificationTarget: (NotificationType, String) -> Unit
) {
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var pendingRemoveItem by remember { mutableStateOf<ConversationDisplayItem?>(null) }
    val unreadNotifCount = remember(notifications) { notifications.count { !it.read } }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SnrBrandLogo(size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "DayunanChat",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "[SECURED_CHANNELS // ACTIVE]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = CyberMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showNotificationsSheet = true },
                            modifier = Modifier.testTag("notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadNotifCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ) {
                                            Text(
                                                text = unreadNotifCount.coerceAtMost(99).toString(),
                                                fontFamily = CyberMonoFontFamily
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "Notifications"
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("new_chat_fab")
            ) {
                Icon(Icons.Default.AddComment, contentDescription = "New Chat")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            SnrSearchBar(
                query = searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                placeholder = "grep conversations...",
                testTag = "chats_search_input"
            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationFilter.entries.forEach { item ->
                    val selected = filter == item
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onFilterChange(item) },
                        label = {
                            Text(
                                text = item.label,
                                fontFamily = CyberMonoFontFamily
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            if (actionError != null) {
                Spacer(Modifier.height(8.dp))
                SnrErrorBanner(message = actionError!!, onDismiss = viewModel::clearError)
            }

            Spacer(Modifier.height(8.dp))

            when (val state = conversationsState) {
                UiState.Loading -> SnrLoadingState("DECRYPTING_CHANNELS...")
                is UiState.Error -> SnrErrorBanner(message = state.message)
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        SnrEmptyState(
                            icon = Icons.Default.ChatBubbleOutline,
                            title = if (searchQuery.isNotBlank()) "No matching chats" else "No active channels",
                            subtitle = if (searchQuery.isNotBlank()) {
                                "Try another handle or keyword."
                            } else {
                                "Add friends in Contacts or tap the button below to initialize an encrypted chat."
                            },
                            actionLabel = if (searchQuery.isBlank()) "Find Friends" else null,
                            onAction = if (searchQuery.isBlank()) onGoToContacts else null
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.data, key = { it.conversation.id }) { item ->
                                ConversationRow(
                                    item = item,
                                    onClick = { onOpenConversation(item.conversation.id) },
                                    onRequestRemove = { pendingRemoveItem = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRemoveItem?.let { item ->
        val isGroup = item.conversation.typeEnum() == ConversationType.GROUP
        val titleText = when {
            !isGroup -> "Remove Chat?"
            item.isGroupOwner -> "Delete Group?"
            else -> "Leave Group?"
        }
        val bodyText = when {
            !isGroup -> "This will permanently delete your conversation and message history with ${item.title}."
            item.isGroupOwner -> "You are the Owner of \"${item.title}\". Deleting this group will permanently remove the group and all messages for every member."
            else -> "You will leave \"${item.title}\" and stop receiving its messages."
        }
        val confirmLabel = when {
            !isGroup -> "Remove Chat"
            item.isGroupOwner -> "Delete Group"
            else -> "Leave Group"
        }

        AlertDialog(
            onDismissRequest = { pendingRemoveItem = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = titleText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(bodyText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveItem = null
                        viewModel.removeConversation(item)
                    },
                    modifier = Modifier.testTag("confirm_remove_chat_button")
                ) {
                    Text(
                        text = confirmLabel,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Initialize Direct Link",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Select a verified contact to open a channel.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CyberMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                if (friends.isEmpty()) {
                    Text(
                        text = "No verified contacts yet. Discover operators by @username in Contacts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            showNewChatSheet = false
                            onGoToContacts()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Discover Users")
                    }
                } else {
                    friends.forEach { friend: UserProfile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showNewChatSheet = false
                                    viewModel.startDirectChat(friend, onOpenConversation)
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SnrAvatar(
                                name = friend.displayName,
                                avatarData = friend.avatarData,
                                size = 44.dp,
                                showOnlineDot = friend.showOnlineStatus,
                                isOnline = friend.online
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = friend.displayName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "@${friend.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = CyberMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotificationsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNotificationsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Security & Activity Logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                if (notifications.isEmpty()) {
                    Text(
                        text = "No unread system events.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = CyberMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    notifications.take(25).forEach { notif: AppNotification ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.markNotificationRead(notif.id)
                                    showNotificationsSheet = false
                                    onNotificationTarget(notif.typeEnum(), notif.targetId)
                                },
                            color = if (notif.read) {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (notif.read) {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                }
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = notif.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (notif.read) FontWeight.Medium else FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = notif.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                if (!notif.read) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxSize()
                                        ) {}
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = "Read",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationDisplayItem,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit
) {
    val isGroup = item.conversation.typeEnum() == ConversationType.GROUP
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRequestRemove
            )
            .testTag("conversation_item_${item.conversation.id}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.unreadCount > 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = item.title,
                avatarData = item.avatarData,
                size = 50.dp,
                showOnlineDot = !isGroup,
                isOnline = item.isOnline,
                fallbackIcon = if (isGroup) Icons.Default.Group else null
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isGroup) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "GRP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = CyberMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = TimeFormatters.formatConversationTime(
                            item.conversation.lastMessageAt ?: item.conversation.updatedAt
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = CyberMonoFontFamily,
                        color = if (item.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.isTyping) {
                            MaterialTheme.colorScheme.primary
                        } else if (item.unreadCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (item.unreadCount > 0 || item.isTyping) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (item.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = item.unreadCount.coerceAtMost(99).toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CyberMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("chat_more_button_${item.conversation.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Conversation actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    val removeLabel = when {
                        !isGroup -> "Remove Chat"
                        item.isGroupOwner -> "Delete Group"
                        else -> "Leave Group"
                    }
                    val removeIcon = if (!isGroup || item.isGroupOwner) {
                        Icons.Default.Delete
                    } else {
                        Icons.AutoMirrored.Filled.ExitToApp
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = removeLabel,
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = removeIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRequestRemove()
                        },
                        modifier = Modifier.testTag("remove_chat_button_${item.conversation.id}")
                    )
                }
            }
        }
    }
}
