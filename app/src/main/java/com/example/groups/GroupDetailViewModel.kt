package com.example.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.toFriendlyMessage
import com.example.data.repository.ContactsRepository
import com.example.data.repository.GroupRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.GroupInfo
import com.example.domain.model.GroupMember
import com.example.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupDetailViewModel(
    private val groupId: String,
    private val currentUserId: String,
    private val groupRepository: GroupRepository,
    private val contactsRepository: ContactsRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    val group: StateFlow<GroupInfo?> = groupRepository.observeGroup(groupId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val members: StateFlow<List<GroupMember>> = groupRepository.observeGroupMembers(groupId, currentUserId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val currentUser = userRepository.observeUser(currentUserId)
        .catch { /* ignore */ }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _friends = MutableStateFlow<List<UserProfile>>(emptyList())
    val friends: StateFlow<List<UserProfile>> = _friends.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            contactsRepository.observeFriendships(currentUserId)
                .catch { emit(emptyList()) }
                .collect { friendships ->
                    _friends.value = friendships.mapNotNull {
                        userRepository.getUser(it.peerId(currentUserId)).getOrNull()
                    }.sortedBy { it.displayNameLower }
                }
        }
    }

    fun updateSettings(name: String, description: String, avatarData: String) {
        viewModelScope.launch {
            groupRepository.updateGroupSettings(groupId, name, description, avatarData)
                .onSuccess { _statusMessage.value = "Group settings updated." }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Update failed.") }
        }
    }

    fun addFriendDirectly(friend: UserProfile) {
        val g = group.value ?: return
        viewModelScope.launch {
            groupRepository.addFriendDirectlyToGroup(g, friend)
                .onSuccess { _statusMessage.value = "${friend.displayName} added to group." }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Failed to add member.") }
        }
    }

    fun inviteFriend(friend: UserProfile) {
        val g = group.value ?: return
        viewModelScope.launch {
            val me = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (me == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                return@launch
            }
            groupRepository.sendGroupInvitation(g.id, g.name, g.avatarData, me, friend.userId)
                .onSuccess { _statusMessage.value = "Invitation sent to ${friend.displayName}." }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Failed to send invite.") }
        }
    }

    fun toggleAdmin(member: GroupMember, promote: Boolean) {
        val g = group.value ?: return
        viewModelScope.launch {
            groupRepository.updateMemberRole(g, member, promote)
                .onSuccess {
                    _statusMessage.value = if (promote) {
                        "${member.displayName} promoted to Admin."
                    } else {
                        "${member.displayName} changed to Member."
                    }
                }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Role update failed.") }
        }
    }

    fun removeMember(member: GroupMember) {
        val g = group.value ?: return
        viewModelScope.launch {
            groupRepository.removeMember(g, member.userId)
                .onSuccess { _statusMessage.value = "Removed ${member.displayName}." }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Could not remove member.") }
        }
    }

    fun leaveGroup(onLeft: () -> Unit) {
        val g = group.value ?: return
        viewModelScope.launch {
            groupRepository.leaveGroup(g)
                .onSuccess { onLeft() }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Could not leave group.") }
        }
    }

    fun deleteGroup(onDeleted: () -> Unit) {
        val g = group.value ?: return
        viewModelScope.launch {
            groupRepository.deleteGroup(g)
                .onSuccess { onDeleted() }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Could not delete group.") }
        }
    }

    fun clearFeedback() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
