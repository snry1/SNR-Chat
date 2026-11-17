package com.example.authentication

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.SnrConstants
import com.example.core.toFriendlyMessage
import com.example.data.repository.AuthRepository
import com.example.data.repository.GoogleSignInResult
import com.example.data.repository.UserRepository
import com.example.domain.model.UserProfile
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AuthSessionState {
    data object Checking : AuthSessionState
    data object Unauthenticated : AuthSessionState
    data class NeedsProfileSetup(val firebaseUser: FirebaseUser) : AuthSessionState
    data class Authenticated(val userProfile: UserProfile) : AuthSessionState
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class ProfileSetupUiState(
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val avatarData: String = "",
    val isCheckingUsername: Boolean = false,
    val isUsernameAvailable: Boolean? = null,
    val usernameHint: String? = null,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _sessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Checking)
    val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _setupState = MutableStateFlow(ProfileSetupUiState())
    val setupState: StateFlow<ProfileSetupUiState> = _setupState.asStateFlow()

    private var profileObserverJob: Job? = null
    private var usernameCheckJob: Job? = null
    private var initializedDefaultsForUid: String? = null

    init {
        viewModelScope.launch {
            authRepository.authStateFlow().collect { firebaseUser ->
                profileObserverJob?.cancel()
                if (firebaseUser == null) {
                    initializedDefaultsForUid = null
                    _sessionState.value = AuthSessionState.Unauthenticated
                } else {
                    observeAuthenticatedProfile(firebaseUser)
                }
            }
        }
    }

    private fun observeAuthenticatedProfile(firebaseUser: FirebaseUser) {
        if (initializedDefaultsForUid != firebaseUser.uid) {
            initializedDefaultsForUid = firebaseUser.uid
            val suggestedName = firebaseUser.displayName?.take(50).orEmpty()
            val suggestedHandle = suggestedName
                .lowercase()
                .replace(Regex("[^a-z0-9_]"), "")
                .take(18)
            _setupState.update {
                it.copy(
                    displayName = if (it.displayName.isBlank()) suggestedName else it.displayName,
                    username = if (it.username.isBlank()) suggestedHandle else it.username
                )
            }
            if (suggestedHandle.length >= 3) {
                checkUsernameAvailability(suggestedHandle)
            }
        }

        profileObserverJob = viewModelScope.launch {
            try {
                userRepository.observeUser(firebaseUser.uid).collect { profile ->
                    if (profile == null) {
                        _sessionState.value = AuthSessionState.NeedsProfileSetup(firebaseUser)
                    } else {
                        _sessionState.value = AuthSessionState.Authenticated(profile)
                    }
                }
            } catch (e: Exception) {
                _sessionState.value = AuthSessionState.NeedsProfileSetup(firebaseUser)
            }
        }
    }

    fun attemptAutoSignIn(context: Context) {
        viewModelScope.launch {
            if (authRepository.currentUser != null) return@launch
            val signedIn = authRepository.attemptSilentSignIn(context)
            if (!signedIn && authRepository.currentUser == null) {
                _sessionState.value = AuthSessionState.Unauthenticated
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        if (_loginState.value.isLoading) return
        _loginState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(activity)) {
                is GoogleSignInResult.Success -> {
                    _loginState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                GoogleSignInResult.Cancelled -> {
                    _loginState.update { it.copy(isLoading = false) }
                }
                is GoogleSignInResult.Error -> {
                    _loginState.update {
                        it.copy(isLoading = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun clearLoginError() {
        _loginState.update { it.copy(errorMessage = null) }
    }

    fun onSetupDisplayNameChange(value: String) {
        _setupState.update {
            it.copy(
                displayName = value.take(SnrConstants.MAX_DISPLAY_NAME_LEN),
                errorMessage = null
            )
        }
    }

    fun onSetupUsernameChange(value: String) {
        val cleaned = value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .take(24)
        _setupState.update {
            it.copy(
                username = cleaned,
                isUsernameAvailable = null,
                usernameHint = null,
                errorMessage = null
            )
        }
        checkUsernameAvailability(cleaned)
    }

    private fun checkUsernameAvailability(cleaned: String) {
        usernameCheckJob?.cancel()
        if (cleaned.length < 3) {
            _setupState.update {
                it.copy(
                    isCheckingUsername = false,
                    isUsernameAvailable = false,
                    usernameHint = "At least 3 characters (a-z, 0-9, _)"
                )
            }
            return
        }
        usernameCheckJob = viewModelScope.launch {
            _setupState.update { it.copy(isCheckingUsername = true) }
            delay(300)
            userRepository.isUsernameAvailable(cleaned)
                .onSuccess { available ->
                    _setupState.update {
                        it.copy(
                            isCheckingUsername = false,
                            isUsernameAvailable = available,
                            usernameHint = if (available) "@$cleaned is available" else "@$cleaned is already taken"
                        )
                    }
                }
                .onFailure { err ->
                    _setupState.update {
                        it.copy(
                            isCheckingUsername = false,
                            isUsernameAvailable = null,
                            usernameHint = err.toFriendlyMessage("Could not verify username")
                        )
                    }
                }
        }
    }

    fun onSetupBioChange(value: String) {
        _setupState.update {
            it.copy(bio = value.take(SnrConstants.MAX_BIO_LEN), errorMessage = null)
        }
    }

    fun onSetupAvatarPicked(dataUri: String) {
        _setupState.update { it.copy(avatarData = dataUri, errorMessage = null) }
    }

    fun completeProfileSetup() {
        val current = _setupState.value
        if (current.isSubmitting) return
        if (current.displayName.isBlank()) {
            _setupState.update { it.copy(errorMessage = "Please enter your display name.") }
            return
        }
        if (!SnrConstants.USERNAME_REGEX.matches(current.username)) {
            _setupState.update {
                it.copy(errorMessage = "Choose a valid username (3-24 chars: a-z, 0-9, _).")
            }
            return
        }

        _setupState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            userRepository.createInitialProfile(
                displayName = current.displayName,
                rawUsername = current.username,
                bio = current.bio,
                avatarData = current.avatarData
            ).onSuccess {
                _setupState.update { it.copy(isSubmitting = false) }
            }.onFailure { err ->
                _setupState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.toFriendlyMessage("Could not create your profile.")
                    )
                }
            }
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            authRepository.signOut(context) {
                userRepository.updatePresence(false)
            }
        }
    }
}
