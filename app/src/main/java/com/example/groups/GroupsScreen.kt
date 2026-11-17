package com.example.groups

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.MediaUtils
import com.example.core.SnrConstants
import com.example.core.UiState
import com.example.domain.model.GroupInfo
import com.example.domain.model.GroupInvitation
import com.example.presentation.components.SnrAvatar
import com.example.presentation.components.SnrEmptyState
import com.example.presentation.components.SnrErrorBanner
import com.example.presentation.components.SnrLoadingState
import com.example.ui.theme.CyberMonoFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    viewModel: GroupsViewModel,
    currentUserId: String,
    onOpenGroupChat: (String) -> Unit,
    onOpenGroupManage: (String) -> Unit
) {
    val groupsState by viewModel.groupsState.collectAsStateWithLifecycle()
    val invitations by viewModel.incomingInvitations.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCreateSheet by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var groupDesc by remember { mutableStateOf("") }
    var groupAvatar by remember { mutableStateOf("") }
    var selectedFriendIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sendInvites by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<GroupInfo?>(null) }
    var groupToLeave by remember { mutableStateOf<GroupInfo?>(null) }

    val groupPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                MediaUtils.encodeImageUriToDataUri(
                    context = context,
                    uri = uri,
                    maxDimension = 512,
                    maxStringLen = SnrConstants.MAX_AVATAR_DATA_LEN
                ).onSuccess { encoded -> groupAvatar = encoded }
            }
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            snackbarHostState.showSnackbar(statusMessage!!)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Groups",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "[CLUSTER_ROOMS // ACTIVE]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CyberMonoFontFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
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
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("create_group_fab")
            ) {
                Icon(Icons.Default.GroupAdd, contentDescription = "Create Group")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                SnrErrorBanner(
                    message = errorMessage!!,
                    onDismiss = viewModel::clearFeedback,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when (val state = groupsState) {
                UiState.Loading -> SnrLoadingState("SYNCING_GROUPS...")
                is UiState.Error -> SnrErrorBanner(message = state.message)
                is UiState.Success -> {
                    val groups = state.data
                    if (groups.isEmpty() && invitations.isEmpty()) {
                        SnrEmptyState(
                            icon = Icons.Default.Group,
                            title = "No groups yet",
                            subtitle = "Create a cluster space to chat with friends and manage admins.",
                            actionLabel = "Create Group",
                            onAction = { showCreateSheet = true }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (invitations.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "GROUP_INVITATIONS (${invitations.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(invitations, key = { it.id }) { invite ->
                                    GroupInvitationCard(
                                        invitation = invite,
                                        onAccept = { viewModel.respondToInvitation(invite, true) },
                                        onDecline = { viewModel.respondToInvitation(invite, false) }
                                    )
                                }
                            }

                            if (groups.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "MY_GROUPS (${groups.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                items(groups, key = { it.id }) { group ->
                                    GroupRowCard(
                                        group = group,
                                        currentUserId = currentUserId,
                                        onOpenChat = { onOpenGroupChat(group.conversationId) },
                                        onOpenManage = { onOpenGroupManage(group.id) },
                                        onRequestDelete = { groupToDelete = group },
                                        onRequestLeave = { groupToLeave = group }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Delete Group?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Permanently delete \"${group.name}\" and all messages for all ${group.memberUids.size} members? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup(group)
                        groupToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_group_button")
                ) {
                    Text(
                        text = "Delete Group",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    groupToLeave?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToLeave = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Leave Group?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("You will stop receiving messages from \"${group.name}\" unless invited again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.leaveGroup(group)
                        groupToLeave = null
                    },
                    modifier = Modifier.testTag("confirm_leave_group_button")
                ) {
                    Text(
                        text = "Leave Group",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToLeave = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCreateSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Create New Group",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable {
                            groupPhotoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                ) {
                    SnrAvatar(
                        name = groupName.ifBlank { "Group" },
                        avatarData = groupAvatar,
                        size = 76.dp,
                        fallbackIcon = Icons.Default.Group
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Group photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(SnrConstants.MAX_GROUP_NAME_LEN) },
                    label = { Text("Group Name *") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_group_name_input")
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = groupDesc,
                    onValueChange = { groupDesc = it.take(SnrConstants.MAX_GROUP_DESC_LEN) },
                    label = { Text("Description (Optional)") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_group_desc_input")
                )

                if (friends.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Send invitations instead", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "When off, selected friends join immediately",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = sendInvites, onCheckedChange = { sendInvites = it })
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "SELECT_MEMBERS (${selectedFriendIds.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        friends.forEach { friend ->
                            val checked = friend.userId in selectedFriendIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedFriendIds = if (checked) {
                                            selectedFriendIds - friend.userId
                                        } else {
                                            selectedFriendIds + friend.userId
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedFriendIds = if (checked) {
                                            selectedFriendIds - friend.userId
                                        } else {
                                            selectedFriendIds + friend.userId
                                        }
                                    }
                                )
                                SnrAvatar(
                                    name = friend.displayName,
                                    avatarData = friend.avatarData,
                                    size = 36.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(friend.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "@${friend.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = CyberMonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = {
                        viewModel.createGroup(
                            name = groupName,
                            description = groupDesc,
                            avatarData = groupAvatar,
                            selectedFriendIds = selectedFriendIds,
                            sendInvitesInsteadOfDirectAdd = sendInvites
                        ) { convId ->
                            showCreateSheet = false
                            groupName = ""
                            groupDesc = ""
                            groupAvatar = ""
                            selectedFriendIds = emptySet()
                            onOpenGroupChat(convId)
                        }
                    },
                    enabled = groupName.isNotBlank() && !isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("submit_create_group_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create Group")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRowCard(
    group: GroupInfo,
    currentUserId: String,
    onOpenChat: () -> Unit,
    onOpenManage: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestLeave: () -> Unit
) {
    val isOwner = group.ownerId == currentUserId
    val roleLabel = when {
        isOwner -> "[OWNER]"
        currentUserId in group.adminUids -> "[ADMIN]"
        else -> "[MEMBER]"
    }
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenChat)
            .testTag("group_card_${group.id}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = group.name,
                avatarData = group.avatarData,
                size = 50.dp,
                fallbackIcon = Icons.Default.Group
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = roleLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = CyberMonoFontFamily,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = group.description.ifBlank { "${group.memberUids.size} members" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onOpenManage,
                modifier = Modifier.testTag("group_settings_button_${group.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Manage ${group.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("group_more_button_${group.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions for ${group.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open Chat") },
                        leadingIcon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onOpenChat()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Group Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onOpenManage()
                        }
                    )
                    if (isOwner) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Delete Group",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRequestDelete()
                            },
                            modifier = Modifier.testTag("delete_group_menu_item")
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Leave Group",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRequestLeave()
                            },
                            modifier = Modifier.testTag("leave_group_menu_item")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupInvitationCard(
    invitation: GroupInvitation,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnrAvatar(
                name = invitation.groupName,
                avatarData = invitation.groupAvatarData,
                size = 46.dp,
                fallbackIcon = Icons.Default.Group
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(invitation.groupName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Invited by ${invitation.fromDisplayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDecline) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Decline",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onAccept) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Accept",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
