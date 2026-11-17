package com.example.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.FriendRequestStatus
import com.example.core.toFriendlyMessage
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.FriendRequest
import com.example.domain.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ContactsTab(val label: String) {
    FRIENDS("Friends"),
    REQUESTS("Requests"),
    DISCOVER("Discover")
}

class ContactsViewModel(
    private val currentUserId: String,
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(ContactsTab.FRIENDS)
    val selectedTab: StateFlow<ContactsTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _discoverUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    val discoverUsers: StateFlow<List<UserProfile>> = _discoverUsers.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _friends = MutableStateFlow<List<UserProfile>>(emptyList())
    val friends: StateFlow<List<UserProfile>> = _friends.asStateFlow()

    private val _blockedIds = MutableStateFlow<Set<String>>(emptySet())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val currentUser = userRepository.observeUser(currentUserId)
        .catch { /* handled elsewhere */ }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val allRequests: StateFlow<List<FriendRequest>> =
        contactsRepository.observeFriendRequests(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val incomingRequests: StateFlow<List<FriendRequest>> = allRequests
        .combine(_blockedIds) { reqs, blocked ->
            reqs.filter {
                it.toUserId == currentUserId &&
                    it.statusEnum() == FriendRequestStatus.PENDING &&
                    it.fromUserId !in blocked
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val outgoingRequests: StateFlow<List<FriendRequest>> = allRequests
        .combine(_blockedIds) { reqs, blocked ->
            reqs.filter {
                it.fromUserId == currentUserId &&
                    it.statusEnum() == FriendRequestStatus.PENDING &&
                    it.toUserId !in blocked
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            userRepository.observeBlockedUsers(currentUserId)
                .catch { emit(emptyList()) }
                .collect { blocked ->
                    _blockedIds.value = blocked.map { it.blockedId }.toSet()
                    if (_selectedTab.value == ContactsTab.DISCOVER) {
                        runSearch(_searchQuery.value)
                    }
                }
        }

        viewModelScope.launch {
            combine(
                contactsRepository.observeFriendships(currentUserId).catch { emit(emptyList()) },
                _blockedIds
            ) { friendships, blocked ->
                friendships.map { it.peerId(currentUserId) }.filter { it !in blocked }
            }.collect { peerIds ->
                val profiles = withContext(Dispatchers.IO) {
                    coroutineScope {
                        peerIds.map { id ->
                            async { userRepository.getUser(id).getOrNull() }
                        }.awaitAll().filterNotNull()
                    }
                }
                _friends.value = profiles.sortedBy { it.displayNameLower }
            }
        }
    }

    fun selectTab(tab: ContactsTab) {
        _selectedTab.value = tab
        if (tab == ContactsTab.DISCOVER) {
            refreshDiscover()
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(280)
            runSearch(query)
        }
    }

    fun refreshDiscover() {
        viewModelScope.launch {
            runSearch(_searchQuery.value)
        }
    }

    private suspend fun runSearch(query: String) {
        _isSearching.value = true
        userRepository.searchUsers(query, _blockedIds.value)
            .onSuccess { list -> _discoverUsers.value = list }
            .onFailure { err ->
                _errorMessage.value = err.toFriendlyMessage("Search failed.")
            }
        _isSearching.value = false
    }

    fun sendFriendRequest(target: UserProfile) {
        viewModelScope.launch {
            val me = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (me == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                return@launch
            }
            contactsRepository.sendFriendRequest(me, target)
                .onSuccess {
                    _statusMessage.value = "Friend request sent to @${target.username}."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not send request.")
                }
        }
    }

    fun acceptRequest(request: FriendRequest) {
        viewModelScope.launch {
            val me = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (me == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                return@launch
            }
            contactsRepository.acceptFriendRequest(request, me)
                .onSuccess {
                    _statusMessage.value = "${request.fromDisplayName} added to your friends."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Failed to accept request.")
                }
        }
    }

    fun rejectOrCancelRequest(requestId: String, cancelled: Boolean) {
        viewModelScope.launch {
            contactsRepository.rejectOrCancelRequest(requestId)
                .onSuccess {
                    _statusMessage.value = if (cancelled) "Request cancelled." else "Request declined."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Action failed.")
                }
        }
    }

    fun removeFriend(peer: UserProfile) {
        viewModelScope.launch {
            contactsRepository.removeFriend(peer.userId)
                .onSuccess {
                    _statusMessage.value = "Removed ${peer.displayName} from friends."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not remove friend.")
                }
        }
    }

    fun startChatWith(peer: UserProfile, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectConversation(peer)
                .onSuccess(onOpened)
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Unable to open chat.")
                }
        }
    }

    fun blockUser(target: UserProfile) {
        viewModelScope.launch {
            userRepository.blockUser(target)
                .onSuccess {
                    _statusMessage.value = "Blocked ${target.displayName}."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not block user.")
                }
        }
    }

    fun reportUser(targetUserId: String, reason: String, details: String) {
        viewModelScope.launch {
            userRepository.reportUser(targetUserId, reason, details)
                .onSuccess {
                    _statusMessage.value = "Report submitted."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not submit report.")
                }
        }
    }

    fun clearFeedback() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
