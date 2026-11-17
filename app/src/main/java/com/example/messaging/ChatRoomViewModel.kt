package com.example.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ConversationType
import com.example.core.MessageType
import com.example.core.UiState
import com.example.core.VoicePlaybackState
import com.example.core.VoicePlayer
import com.example.core.VoiceRecorder
import com.example.core.toFriendlyMessage
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatRoomHeaderState(
    val conversation: Conversation? = null,
    val title: String = "Chat",
    val subtitle: String = "",
    val avatarData: String = "",
    val isOnline: Boolean = false,
    val isGroup: Boolean = false,
    val peerProfile: UserProfile? = null
)

class ChatRoomViewModel(
    private val conversationId: String,
    private val currentUserId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val voiceRecorder: VoiceRecorder,
    private val voicePlayer: VoicePlayer
) : ViewModel() {

    private val _messageInput = MutableStateFlow("")
    val messageInput: StateFlow<String> = _messageInput.asStateFlow()

    private val _pendingImageData = MutableStateFlow<String?>(null)
    val pendingImageData: StateFlow<String?> = _pendingImageData.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatMessage?>(null)
    val replyTarget: StateFlow<ChatMessage?> = _replyTarget.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _recordingAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val recordingAmplitudes: StateFlow<List<Float>> = _recordingAmplitudes.asStateFlow()

    val voicePlaybackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _peerProfile = MutableStateFlow<UserProfile?>(null)
    private var peerObserverJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var recordingTickerJob: Job? = null

    val currentUser: StateFlow<UserProfile?> = userRepository.observeUser(currentUserId)
        .catch { /* handled elsewhere */ }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversation: StateFlow<Conversation?> = chatRepository.observeConversation(conversationId)
        .catch { error ->
            val fsError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsError == null && error is CancellationException) throw error
            _errorMessage.value = (fsError ?: error).toFriendlyMessage("Failed to load conversation.")
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val messagesState: StateFlow<UiState<List<ChatMessage>>> =
        chatRepository.observeMessages(conversationId, currentUserId)
            .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                emit(UiState.Error((fsError ?: error).toFriendlyMessage("Unable to load messages.")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val headerState: StateFlow<ChatRoomHeaderState> =
        combine(conversation, _peerProfile) { conv, peer ->
            if (conv == null) {
                ChatRoomHeaderState()
            } else if (conv.typeEnum() == ConversationType.GROUP) {
                val typingCount = conv.typingUids.count { it != currentUserId }
                val sub = when {
                    typingCount > 0 -> "Someone is typing..."
                    else -> "${conv.participantUids.size} members"
                }
                ChatRoomHeaderState(
                    conversation = conv,
                    title = conv.title.ifBlank { "Group Chat" },
                    subtitle = sub,
                    avatarData = conv.avatarData,
                    isOnline = false,
                    isGroup = true,
                    peerProfile = null
                )
            } else {
                val isTyping = conv.typingUids.any { it != currentUserId }
                val isOnline = peer?.online == true && peer.showOnlineStatus
                val sub = when {
                    isTyping -> "Typing..."
                    isOnline -> "Online"
                    peer?.showLastSeen == true -> com.example.core.TimeFormatters.formatLastSeen(peer.lastSeenAt)
                    else -> "@${peer?.username.orEmpty()}"
                }
                ChatRoomHeaderState(
                    conversation = conv,
                    title = peer?.displayName ?: "Dayunan User",
                    subtitle = sub,
                    avatarData = peer?.avatarData.orEmpty(),
                    isOnline = isOnline,
                    isGroup = false,
                    peerProfile = peer
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ChatRoomHeaderState())

    init {
        viewModelScope.launch {
            conversation.collect { conv ->
                if (conv != null && conv.typeEnum() == ConversationType.DIRECT) {
                    val peerId = conv.peerIdForDirect(currentUserId)
                    if (_peerProfile.value?.userId != peerId && peerObserverJob == null) {
                        peerObserverJob = launch {
                            userRepository.observeUser(peerId)
                                .catch { /* ignore */ }
                                .collect { _peerProfile.value = it }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(conversation, messagesState, currentUser) { conv, msgsState, user ->
                Triple(conv, msgsState, user)
            }.collect { (conv, msgsState, user) ->
                if (conv != null && user != null && msgsState is UiState.Success) {
                    chatRepository.markConversationRead(conv, msgsState.data, user)
                }
            }
        }
    }

    fun onMessageInputChange(value: String) {
        _messageInput.value = value
        val conv = conversation.value ?: return
        viewModelScope.launch {
            if (value.isNotBlank()) {
                chatRepository.setTyping(conv, true)
                typingTimeoutJob?.cancel()
                typingTimeoutJob = launch {
                    delay(2500)
                    chatRepository.setTyping(conv, false)
                }
            } else {
                typingTimeoutJob?.cancel()
                chatRepository.setTyping(conv, false)
            }
        }
    }

    fun appendEmoji(emoji: String) {
        onMessageInputChange(_messageInput.value + emoji)
    }

    fun setReplyTarget(message: ChatMessage?) {
        _replyTarget.value = message
    }

    fun setPendingImage(dataUri: String?) {
        _pendingImageData.value = dataUri
    }

    fun sendComposerMessage() {
        val conv = conversation.value ?: return
        if (_isSending.value) return
        val stagedImage = _pendingImageData.value
        val text = _messageInput.value.trim()
        if (stagedImage == null && text.isEmpty()) return

        val reply = _replyTarget.value
        _isSending.value = true
        typingTimeoutJob?.cancel()

        viewModelScope.launch {
            val sender = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (sender == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                _isSending.value = false
                return@launch
            }

            _messageInput.value = ""
            _pendingImageData.value = null
            _replyTarget.value = null

            chatRepository.setTyping(conv, false)
            val result = if (stagedImage != null) {
                chatRepository.sendMessage(
                    conversation = conv,
                    sender = sender,
                    type = MessageType.IMAGE,
                    text = text,
                    mediaData = stagedImage,
                    replyTo = reply
                )
            } else {
                chatRepository.sendMessage(
                    conversation = conv,
                    sender = sender,
                    type = MessageType.TEXT,
                    text = text,
                    replyTo = reply
                )
            }
            result.onFailure { err ->
                if (stagedImage != null) {
                    _pendingImageData.value = stagedImage
                }
                if (text.isNotEmpty() && _messageInput.value.isEmpty()) {
                    _messageInput.value = text
                }
                _errorMessage.value = err.toFriendlyMessage(
                    if (stagedImage != null) "Failed to send image." else "Message failed to send."
                )
            }
            _isSending.value = false
        }
    }

    fun sendTextMessage() {
        sendComposerMessage()
    }

    fun sendImageMessage(dataUri: String, caption: String = "") {
        val conv = conversation.value ?: return
        val sender = currentUser.value ?: return
        val reply = _replyTarget.value
        _replyTarget.value = null
        _isSending.value = true

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversation = conv,
                sender = sender,
                type = MessageType.IMAGE,
                text = caption,
                mediaData = dataUri,
                replyTo = reply
            ).onFailure { err ->
                _errorMessage.value = err.toFriendlyMessage("Failed to send image.")
            }
            _isSending.value = false
        }
    }

    fun sendFileMessage(
        dataUri: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long
    ) {
        val conv = conversation.value ?: return
        val sender = currentUser.value ?: return
        val reply = _replyTarget.value
        _replyTarget.value = null
        _isSending.value = true

        viewModelScope.launch {
            chatRepository.sendMessage(
                conversation = conv,
                sender = sender,
                type = MessageType.FILE,
                text = "",
                mediaData = dataUri,
                fileName = fileName,
                fileMimeType = mimeType,
                fileSizeBytes = sizeBytes,
                replyTo = reply
            ).onFailure { err ->
                _errorMessage.value = err.toFriendlyMessage("Failed to send file.")
            }
            _isSending.value = false
        }
    }

    fun startVoiceRecording() {
        if (_isRecordingVoice.value || _isSending.value) return
        voicePlayer.stop()
        voiceRecorder.startRecording()
            .onSuccess {
                _isRecordingVoice.value = true
                _recordingDurationMs.value = 0L
                _recordingAmplitudes.value = List(16) { 0.2f }
                recordingTickerJob?.cancel()
                val startMs = System.currentTimeMillis()
                recordingTickerJob = viewModelScope.launch {
                    while (isActive && _isRecordingVoice.value) {
                        val elapsed = System.currentTimeMillis() - startMs
                        _recordingDurationMs.value = elapsed
                        val normAmp = (voiceRecorder.currentAmplitude().toFloat() / 16000f)
                            .coerceIn(0.15f, 1.0f)
                        _recordingAmplitudes.update { prev ->
                            (prev + normAmp).takeLast(20)
                        }
                        if (elapsed >= 115_000L) {
                            sendVoiceRecording()
                            break
                        }
                        delay(100L)
                    }
                }
            }
            .onFailure { err ->
                _errorMessage.value = err.message ?: "Unable to start microphone recording."
            }
    }

    fun cancelVoiceRecording() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _isRecordingVoice.value = false
        _recordingDurationMs.value = 0L
        _recordingAmplitudes.value = emptyList()
        voiceRecorder.cancelRecording()
    }

    fun sendVoiceRecording() {
        if (!_isRecordingVoice.value) return
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _isRecordingVoice.value = false
        _recordingDurationMs.value = 0L
        _recordingAmplitudes.value = emptyList()

        val conv = conversation.value
        if (conv == null) {
            voiceRecorder.cancelRecording()
            return
        }

        val reply = _replyTarget.value
        _isSending.value = true
        viewModelScope.launch {
            val encoded = voiceRecorder.stopAndEncode()
            val sender = currentUser.value ?: userRepository.getUser(currentUserId).getOrNull()
            if (sender == null) {
                _errorMessage.value = "Your profile is still loading. Please try again."
                _isSending.value = false
                return@launch
            }

            encoded.onSuccess { note ->
                _replyTarget.value = null
                chatRepository.sendMessage(
                    conversation = conv,
                    sender = sender,
                    type = MessageType.AUDIO,
                    text = "",
                    mediaData = note.dataUri,
                    fileName = note.fileName,
                    fileMimeType = note.mimeType,
                    fileSizeBytes = note.sizeBytes,
                    audioDurationMs = note.durationMs,
                    replyTo = reply
                ).onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Failed to send voice message.")
                }
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Could not save voice recording."
            }
            _isSending.value = false
        }
    }

    fun toggleVoicePlayback(message: ChatMessage) {
        if (message.mediaData.isBlank()) return
        voicePlayer.togglePlayPause(message.id, message.mediaData, message.audioDurationMs)
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.softDeleteMessage(conversationId, messageId)
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not delete message.")
                }
        }
    }

    fun blockPeer(onBlocked: () -> Unit) {
        val peer = _peerProfile.value ?: return
        viewModelScope.launch {
            userRepository.blockUser(peer)
                .onSuccess {
                    _statusMessage.value = "Blocked ${peer.displayName}."
                    onBlocked()
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Failed to block user.")
                }
        }
    }

    fun reportPeer(reason: String, details: String) {
        val peer = _peerProfile.value ?: return
        viewModelScope.launch {
            userRepository.reportUser(peer.userId, reason, details)
                .onSuccess {
                    _statusMessage.value = "Report submitted. Thank you for keeping DayunanChat safe."
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Failed to submit report.")
                }
        }
    }

    fun deleteConversation(onDeleted: () -> Unit) {
        val conv = conversation.value ?: return
        if (conv.typeEnum() != ConversationType.DIRECT) return
        viewModelScope.launch {
            chatRepository.deleteDirectConversation(conv.id)
                .onSuccess {
                    voicePlayer.stop()
                    onDeleted()
                }
                .onFailure { err ->
                    _errorMessage.value = err.toFriendlyMessage("Could not remove chat.")
                }
        }
    }

    fun clearFeedback() {
        _errorMessage.value = null
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        recordingTickerJob?.cancel()
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
        val conv = conversation.value
        if (conv != null && conv.typingUids.contains(currentUserId)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                chatRepository.setTyping(conv, false)
            }
        }
    }
}
