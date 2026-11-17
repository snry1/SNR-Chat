package com.example.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.GroupInvitationStatus
import com.example.core.UiState
import com.example.core.toFriendlyMessage
import com.example.data.repository.ContactsRepository
import com.example.data.repository.GroupRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.GroupInfo
import com.example.domain.model.GroupInvitation
import com.example.domain.model.UserProfile
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupsViewModel(
    private val currentUserId: String,
    private val groupRepository: GroupRepository,
    private val contactsRepository: ContactsRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _friends = MutableStateFlow<List<UserProfile>>(emptyList())
    val friends: StateFlow<List<UserProfile>> = _friends.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val currentUser = userRepository.observeUser(currentUserId)
        .catch { /* handled elsewhere */ }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val groupsState: StateFlow<UiState<List<GroupInfo>>> =
        groupRepository.observeMyGroups(currentUserId)
            .map<List<GroupInfo>, UiState<List<GroupInfo>>> { UiState.Success(it) }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                emit(UiState.Error((fsError ?: error).toFriendlyMessage("Failed to load groups.")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val incomingInvitations: StateFlow<List<GroupInvitation>> =
        groupRepository.observeInvitations(currentUserId)
            .map { list ->
                list.filter {
                    it.toUserId == currentUserId &&
                        it.statusEnum() == GroupInvitationStatus.PENDING
                }
            }
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            contactsRepository.observeFriendships(currentUserId)
                .catch { emit(emptyList()) }
                .collect { friendships ->
                    val profiles = withContext(Dispatchers.IO) {
                        coroutineScope {
                            friendships.map { fs ->
                                async { userRepository.getUser(fs.peerId(currentUserId)).getOrNull() }
                            }.awaitAll().filterNotNull()
                        }
                    }
                    _friends.value = profiles.sortedBy { it.displayNameLower }
                }
        }
    }

    fun createGroup(
        name: String,
        description: String,
        avatarData: String,
        selectedFriendIds: Set<String>,
        sendInvitesInsteadOfDirectAdd: Boolean,
        onCreated: (String) -> Unit
    ) {
        if (_isCreating.value) return
        val selectedFriends = _friends.value.filter { it.userId in selectedFriendIds }
        _isCreating.value = true
        viewModelScope.launch {
            val creator = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (creator == null) {
                _isCreating.value = false
                _errorMessage.value = "Your profile is still loading. Please try again."
                return@launch
            }
            groupRepository.createGroup(
                creator = creator,
                name = name,
                description = description,
                avatarData = avatarData,
                friendsToAdd = selectedFriends,
                sendInvitesInsteadOfDirectAdd = sendInvitesInsteadOfDirectAdd
            ).onSuccess { groupId ->
                _isCreating.value = false
                _statusMessage.value = "Group \"$name\" created."
                onCreated(groupId)
            }.onFailure { err ->
                _isCreating.value = false
                _errorMessage.value = err.toFriendlyMessage("Could not create group.")
            }
        }
    }

    fun respondToInvitation(invitation: GroupInvitation, accept: Boolean) {
        viewModelScope.launch {
            val me = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (me == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                return@launch
            }
            groupRepository.respondToGroupInvitation(invitation, accept, me)
                .onSuccess {
                    _statusMessage.value = if (accept) {
                        "Joined ${invitation.groupName}."
                    } else {
                        "Declined invitation to ${invitation.groupName}."
                    }
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Failed to respond to invitation.")
                }
        }
    }

    fun deleteGroup(group: GroupInfo) {
        viewModelScope.launch {
            groupRepository.deleteGroup(group)
                .onSuccess {
                    _statusMessage.value = "Group \"${group.name}\" deleted."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not delete group.")
                }
        }
    }

    fun leaveGroup(group: GroupInfo) {
        viewModelScope.launch {
            groupRepository.leaveGroup(group)
                .onSuccess {
                    _statusMessage.value = "Left \"${group.name}\"."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not leave group.")
                }
        }
    }

    fun clearFeedback() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
