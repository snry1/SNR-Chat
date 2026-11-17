package com.example.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ConversationType
import com.example.core.UiState
import com.example.core.toFriendlyMessage
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.GroupRepository
import com.example.data.repository.NotificationRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.AppNotification
import com.example.domain.model.Conversation
import com.example.domain.model.GroupInfo
import com.example.domain.model.UserProfile
import com.example.notifications.SnrNotificationHelper
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConversationFilter(val label: String) {
    ALL("All"),
    DIRECT("Personal"),
    GROUPS("Groups")
}

data class ConversationDisplayItem(
    val conversation: Conversation,
    val title: String,
    val avatarData: String,
    val isOnline: Boolean,
    val unreadCount: Long,
    val isTyping: Boolean,
    val subtitle: String,
    val isGroupOwner: Boolean = false
)

class ChatsViewModel(
    private val currentUserId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val groupRepository: GroupRepository,
    private val notificationRepository: NotificationRepository,
    private val notificationHelper: SnrNotificationHelper
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(ConversationFilter.ALL)
    val filter: StateFlow<ConversationFilter> = _filter.asStateFlow()

    private val _peerProfiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _friends = MutableStateFlow<List<UserProfile>>(emptyList())
    val friends: StateFlow<List<UserProfile>> = _friends.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val currentUserProfile = userRepository.observeUser(currentUserId)
        .catch { /* handled in AuthViewModel */ }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val notifications: StateFlow<List<AppNotification>> =
        notificationRepository.observeNotifications(currentUserId)
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var initialNotifSyncDone = false

    val conversationsState: StateFlow<UiState<List<ConversationDisplayItem>>> = combine(
        chatRepository.observeConversations(currentUserId)
            .map<List<Conversation>, UiState<List<Conversation>>> { UiState.Success(it) }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                val root = fsError ?: error
                emit(UiState.Error(root.toFriendlyMessage("Failed to load chats.")))
            },
        _searchQuery,
        _filter,
        _peerProfiles,
        _blockedIds
    ) { state, query, filter, peers, blocked ->
        when (state) {
            is UiState.Loading -> UiState.Loading
            is UiState.Error -> state
            is UiState.Success -> {
                val items = state.data
                    .filter { conv ->
                        if (conv.typeEnum() == ConversationType.DIRECT) {
                            val peerId = conv.peerIdForDirect(currentUserId)
                            peerId !in blocked
                        } else {
                            true
                        }
                    }
                    .filter { conv ->
                        when (filter) {
                            ConversationFilter.ALL -> true
                            ConversationFilter.DIRECT -> conv.typeEnum() == ConversationType.DIRECT
                            ConversationFilter.GROUPS -> conv.typeEnum() == ConversationType.GROUP
                        }
                    }
                    .map { conv ->
                        val isGroup = conv.typeEnum() == ConversationType.GROUP
                        val peerId = conv.peerIdForDirect(currentUserId)
                        val peer = peers[peerId]
                        val title = if (isGroup) {
                            conv.title.ifBlank { "Group Chat" }
                        } else {
                            peer?.displayName ?: "Dayunan User"
                        }
                        val avatar = if (isGroup) conv.avatarData else peer?.avatarData.orEmpty()
                        val isOnline = !isGroup && (peer?.online == true && peer.showOnlineStatus)
                        val typingOthers = conv.typingUids.filter { it != currentUserId }
                        val isTyping = typingOthers.isNotEmpty()
                        val subtitle = when {
                            isTyping -> if (isGroup) "Someone is typing..." else "Typing..."
                            conv.lastMessageText.isNotBlank() -> {
                                val prefix = if (conv.lastMessageSenderId == currentUserId && isGroup) "You: " else ""
                                prefix + conv.lastMessageText
                            }
                            else -> if (isGroup) "${conv.participantUids.size} members" else "Tap to start chatting"
                        }
                        ConversationDisplayItem(
                            conversation = conv,
                            title = title,
                            avatarData = avatar,
                            isOnline = isOnline,
                            unreadCount = conv.unreadFor(currentUserId),
                            isTyping = isTyping,
                            subtitle = subtitle,
                            isGroupOwner = isGroup && conv.ownerId == currentUserId
                        )
                    }
                    .filter { item ->
                        query.isBlank() ||
                            item.title.contains(query, ignoreCase = true) ||
                            item.subtitle.contains(query, ignoreCase = true)
                    }
                UiState.Success(items)
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    init {
        observePeersAndBlocks()
        observeLiveNotifications()
    }

    private fun observePeersAndBlocks() {
        viewModelScope.launch {
            userRepository.observeBlockedUsers(currentUserId)
                .catch { /* ignore on sign out */ }
                .collect { blocked ->
                    _blockedIds.value = blocked.map { it.blockedId }.toSet()
                }
        }

        viewModelScope.launch {
            chatRepository.observeConversations(currentUserId)
                .catch { /* handled by conversationsState */ }
                .collect { convs ->
                    val peerIds = convs
                        .filter { it.typeEnum() == ConversationType.DIRECT }
                        .map { it.peerIdForDirect(currentUserId) }
                        .distinct()
                    fetchMissingProfiles(peerIds)
                }
        }

        viewModelScope.launch {
            contactsRepository.observeFriendships(currentUserId)
                .catch { /* ignore on sign out */ }
                .collect { friendships ->
                    val friendIds = friendships.map { it.peerId(currentUserId) }.distinct()
                    fetchMissingProfiles(friendIds)
                    val loaded = friendIds.mapNotNull { id -> _peerProfiles.value[id] }
                    _friends.value = loaded.sortedBy { it.displayNameLower }
                }
        }
    }

    private fun observeLiveNotifications() {
        viewModelScope.launch {
            combine(notifications, currentUserProfile) { list, profile -> list to profile }
                .collect { (list, profile) ->
                    if (!initialNotifSyncDone) {
                        initialNotifSyncDone = true
                        return@collect
                    }
                    val enabled = profile?.notificationsEnabled ?: true
                    val showPreview = profile?.showMessagePreview ?: true
                    list.filter { !it.read && it.senderId != currentUserId }
                        .take(3)
                        .forEach { notif ->
                            notificationHelper.showNotification(notif, enabled, showPreview)
                        }
                }
        }
    }

    private suspend fun fetchMissingProfiles(userIds: List<String>) {
        val currentMap = _peerProfiles.value
        val missing = userIds.filter { it.isNotBlank() && it !in currentMap }
        if (missing.isEmpty()) return
        val fetched = coroutineScope {
            missing.map { uid ->
                async(Dispatchers.IO) {
                    uid to userRepository.getUser(uid).getOrNull()
                }
            }.awaitAll()
        }
        val additions = fetched.mapNotNull { (uid, profile) ->
            if (profile != null) uid to profile else null
        }
        if (additions.isNotEmpty()) {
            _peerProfiles.update { it + additions }
        }
    }

    fun onSearchQueryChange(value: String) {
        _searchQuery.value = value
    }

    fun onFilterChange(value: ConversationFilter) {
        _filter.value = value
    }

    fun startDirectChat(peer: UserProfile, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectConversation(peer)
                .onSuccess { convId -> onOpened(convId) }
                .onFailure { err ->
                    _actionError.value = err.toFriendlyMessage("Could not start conversation.")
                }
        }
    }

    fun removeConversation(item: ConversationDisplayItem) {
        val conv = item.conversation
        viewModelScope.launch {
            if (conv.typeEnum() == ConversationType.DIRECT) {
                chatRepository.deleteDirectConversation(conv.id)
                    .onSuccess { _statusMessage.value = "Removed chat with ${item.title}." }
                    .onFailure { err ->
                        _actionError.value = err.toFriendlyMessage("Could not remove chat.")
                    }
            } else {
                val group = GroupInfo(
                    id = conv.id,
                    name = conv.title,
                    description = conv.description,
                    avatarData = conv.avatarData,
                    ownerId = conv.ownerId,
                    adminUids = conv.adminUids,
                    memberUids = conv.participantUids,
                    conversationId = conv.id
                )
                if (conv.ownerId == currentUserId) {
                    groupRepository.deleteGroup(group)
                        .onSuccess { _statusMessage.value = "Deleted group \"${item.title}\"." }
                        .onFailure { err ->
                            _actionError.value = err.toFriendlyMessage("Could not delete group.")
                        }
                } else {
                    groupRepository.leaveGroup(group)
                        .onSuccess { _statusMessage.value = "Left group \"${item.title}\"." }
                        .onFailure { err ->
                            _actionError.value = err.toFriendlyMessage("Could not leave group.")
                        }
                }
            }
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.markNotificationRead(notificationId)
        }
    }

    fun clearError() {
        _actionError.value = null
        _statusMessage.value = null
    }
}
