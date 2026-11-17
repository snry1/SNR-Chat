package com.example.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.DarkModePreference
import com.example.core.FriendRequestPolicy
import com.example.core.toFriendlyMessage
import com.example.data.repository.AuthRepository
import com.example.data.repository.ThemePreferenceStore
import com.example.data.repository.UserRepository
import com.example.domain.model.BlockedUser
import com.example.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val currentUserId: String,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val themePreferenceStore: ThemePreferenceStore
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = userRepository.observeUser(currentUserId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val blockedUsers: StateFlow<List<BlockedUser>> = userRepository.observeBlockedUsers(currentUserId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val darkModePreference: StateFlow<DarkModePreference> = themePreferenceStore.darkModePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), themePreferenceStore.cachedPreference)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun updateProfile(
        displayName: String,
        newUsername: String,
        bio: String,
        avatarData: String,
        onDone: () -> Unit
    ) {
        val current = userProfile.value ?: return
        _isSaving.value = true
        viewModelScope.launch {
            userRepository.updateProfile(
                currentUsername = current.username,
                displayName = displayName,
                rawNewUsername = newUsername,
                bio = bio,
                avatarData = avatarData
            ).onSuccess {
                _isSaving.value = false
                _statusMessage.value = "Profile updated."
                onDone()
            }.onFailure { err ->
                _isSaving.value = false
                _errorMessage.value = err.toFriendlyMessage("Failed to update profile.")
            }
        }
    }

    fun updateAvatarOnly(avatarData: String) {
        val current = userProfile.value ?: return
        viewModelScope.launch {
            userRepository.updateProfile(
                currentUsername = current.username,
                displayName = current.displayName,
                rawNewUsername = current.username,
                bio = current.bio,
                avatarData = avatarData
            ).onSuccess {
                _statusMessage.value = "Profile picture updated."
            }.onFailure { err ->
                _errorMessage.value = err.toFriendlyMessage("Could not update picture.")
            }
        }
    }

    fun updateSettings(
        friendRequestPolicy: FriendRequestPolicy? = null,
        showOnlineStatus: Boolean? = null,
        showLastSeen: Boolean? = null,
        showReadReceipts: Boolean? = null,
        showMessagePreview: Boolean? = null,
        notificationsEnabled: Boolean? = null
    ) {
        val current = userProfile.value ?: return
        viewModelScope.launch {
            userRepository.updatePrivacyAndNotifications(
                friendRequestPolicy = friendRequestPolicy ?: current.requestPolicyEnum(),
                showOnlineStatus = showOnlineStatus ?: current.showOnlineStatus,
                showLastSeen = showLastSeen ?: current.showLastSeen,
                showReadReceipts = showReadReceipts ?: current.showReadReceipts,
                showMessagePreview = showMessagePreview ?: current.showMessagePreview,
                notificationsEnabled = notificationsEnabled ?: current.notificationsEnabled
            ).onFailure { err ->
                _errorMessage.value = err.toFriendlyMessage("Could not save settings.")
            }
        }
    }

    fun setDarkMode(pref: DarkModePreference) {
        viewModelScope.launch {
            themePreferenceStore.setDarkModePreference(pref)
            userRepository.updateDarkModePreference(pref)
        }
    }

    fun unblockUser(blockedUserId: String) {
        viewModelScope.launch {
            userRepository.unblockUser(blockedUserId)
                .onSuccess { _statusMessage.value = "User unblocked." }
                .onFailure { err -> _errorMessage.value = err.toFriendlyMessage("Failed to unblock user.") }
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            authRepository.signOut(context) {
                userRepository.updatePresence(false)
            }
        }
    }

    fun clearFeedback() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
