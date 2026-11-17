package com.example.messaging

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ConversationType
import com.example.core.MediaUtils
import com.example.core.MessageType
import com.example.core.SnrConstants
import com.example.core.TimeFormatters
import com.example.core.UiState
import com.example.core.VoicePlaybackState
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.UserProfile
import com.example.presentation.components.MessageAudioAttachment
import com.example.presentation.components.MessageFileAttachment
import com.example.presentation.components.MessageImageAttachment
import com.example.presentation.components.SnrAvatar
import com.example.presentation.components.SnrEmptyState
import com.example.presentation.components.SnrErrorBanner
import com.example.presentation.components.SnrLoadingState
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberMonoFontFamily
import com.example.ui.theme.SnrIncomingBubbleDark
import com.example.ui.theme.SnrIncomingBubbleLight
import com.example.ui.theme.SnrOutgoingBubbleDark
import com.example.ui.theme.SnrOutgoingBubbleLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val quickEmojis = listOf("😀", "😂", "❤️", "👍", "🔥", "🎉", "🙏", "😎", "😮", "👏", "💯", "✨")

private enum class PendingAudioAction {
    NONE, RECORD_VOICE, START_CALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    viewModel: ChatRoomViewModel,
    currentUserId: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onOpenGroupDetail: (String) -> Unit,
    onStartVoiceCall: (Conversation, UserProfile, UserProfile) -> Unit
) {
    val header by viewModel.headerState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val input by viewModel.messageInput.collectAsStateWithLifecycle()
    val pendingImageData by viewModel.pendingImageData.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingAmplitudes by viewModel.recordingAmplitudes.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showEmojiBar by remember { mutableStateOf(false) }
    var selectedMessageForActions by remember { mutableStateOf<ChatMessage?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("Spam or harassment") }
    var reportDetails by remember { mutableStateOf("") }
    var localMediaError by remember { mutableStateOf<String?>(null) }
    var isPreparingImage by remember { mutableStateOf(false) }
    var showPendingImagePreview by remember { mutableStateOf(false) }
    var pendingAudioAction by remember { mutableStateOf(PendingAudioAction.NONE) }

    fun triggerStartCall() {
        val conv = header.conversation ?: return
        val caller = currentUser ?: return
        val callee = header.peerProfile ?: return
        onStartVoiceCall(conv, caller, callee)
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAudioAction
        pendingAudioAction = PendingAudioAction.NONE
        if (granted) {
            when (action) {
                PendingAudioAction.RECORD_VOICE -> viewModel.startVoiceRecording()
                PendingAudioAction.START_CALL -> triggerStartCall()
                PendingAudioAction.NONE -> Unit
            }
        } else {
            localMediaError = "Microphone permission is required for voice messages and calls."
        }
    }

    fun requestMicThen(action: PendingAudioAction) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            when (action) {
                PendingAudioAction.RECORD_VOICE -> viewModel.startVoiceRecording()
                PendingAudioAction.START_CALL -> triggerStartCall()
                PendingAudioAction.NONE -> Unit
            }
        } else {
            pendingAudioAction = action
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val pendingImageBitmap by produceState<Bitmap?>(
        initialValue = pendingImageData?.let { MediaUtils.getCachedBase64Bitmap(it, 1024) },
        key1 = pendingImageData
    ) {
        val data = pendingImageData
        value = if (data != null && data.startsWith("data:image")) {
            MediaUtils.getCachedBase64Bitmap(data, 1024) ?: withContext(Dispatchers.Default) {
                MediaUtils.decodeBase64Bitmap(data, maxDimension = 1024)
            }
        } else {
            null
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isPreparingImage = true
                MediaUtils.encodeImageUriToDataUri(
                    context = context,
                    uri = uri,
                    maxDimension = 1024,
                    maxStringLen = SnrConstants.MAX_MEDIA_DATA_LEN
                ).onSuccess { encoded ->
                    viewModel.setPendingImage(encoded)
                }.onFailure { err ->
                    localMediaError = err.message ?: "Could not attach image."
                }
                isPreparingImage = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                MediaUtils.encodeFileUriToDataUri(context, uri)
                    .onSuccess { att ->
                        viewModel.sendFileMessage(
                            dataUri = att.dataUri,
                            fileName = att.fileName,
                            mimeType = att.mimeType,
                            sizeBytes = att.sizeBytes
                        )
                    }.onFailure { err ->
                        localMediaError = err.message ?: "Could not attach file."
                    }
            }
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            snackbarHostState.showSnackbar(statusMessage!!)
            viewModel.clearFeedback()
        }
    }

    LaunchedEffect(messagesState) {
        val list = (messagesState as? UiState.Success)?.data
        if (!list.isNullOrEmpty()) {
            listState.animateScrollToItem(list.size - 1)
        }
    }

    val isDirectChat = header.conversation?.typeEnum() == ConversationType.DIRECT

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = header.isGroup && header.conversation != null) {
                                    header.conversation?.id?.let(onOpenGroupDetail)
                                }
                        ) {
                            SnrAvatar(
                                name = header.title,
                                avatarData = header.avatarData,
                                size = 40.dp,
                                showOnlineDot = !header.isGroup,
                                isOnline = header.isOnline,
                                fallbackIcon = if (header.isGroup) Icons.Default.Group else null
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = header.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (header.subtitle.isNotBlank()) {
                                    Text(
                                        text = header.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = CyberMonoFontFamily,
                                        color = if (header.subtitle.contains("Typing", true) || header.subtitle == "Online") {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (header.isGroup && header.conversation != null) {
                            IconButton(
                                onClick = { onOpenGroupDetail(header.conversation!!.id) },
                                modifier = Modifier.testTag("group_info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Group Info",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (isDirectChat || header.peerProfile != null) {
                            if (header.peerProfile != null) {
                                IconButton(
                                    onClick = { requestMicThen(PendingAudioAction.START_CALL) },
                                    modifier = Modifier.testTag("voice_call_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = "Voice call",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.testTag("chat_room_more_button")
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    if (isDirectChat) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Remove Chat",
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                showDeleteChatDialog = true
                                            },
                                            modifier = Modifier.testTag("remove_chat_menu_item")
                                        )
                                    }
                                    if (header.peerProfile != null) {
                                        DropdownMenuItem(
                                            text = { Text("Block User") },
                                            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.blockPeer(onBack)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Report User") },
                                            leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                showReportDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
                )

                AnimatedVisibility(visible = replyTarget != null) {
                    replyTarget?.let { target ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(30.dp)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "REPLY >> ${target.senderName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = CyberMonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = target.text.ifBlank {
                                            when (target.typeEnum()) {
                                                MessageType.IMAGE -> "Photo"
                                                MessageType.AUDIO -> "Voice message (${TimeFormatters.formatAudioDuration(target.audioDurationMs)})"
                                                else -> target.fileName
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.setReplyTarget(null) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel reply",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = isPreparingImage || pendingImageData != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("pending_image_preview_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isPreparingImage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "ENCODING_PHOTO...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = CyberMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                val thumbBmp = pendingImageBitmap
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { showPendingImagePreview = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbBmp != null) {
                                        Image(
                                            bitmap = thumbBmp.asImageBitmap(),
                                            contentDescription = "Attached photo preview",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = "Attached photo",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "[IMAGE_STAGED]",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = CyberMonoFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Add a caption below or tap Send",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.setPendingImage(null) },
                                    modifier = Modifier.testTag("remove_pending_image_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove attached photo",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showEmojiBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickEmojis.forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.appendEmoji(emoji) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                val canSend = (input.isNotBlank() || pendingImageData != null) && !isSending && !isPreparingImage

                if (isRecordingVoice) {
                    VoiceRecordingComposerBar(
                        durationMs = recordingDurationMs,
                        amplitudes = recordingAmplitudes,
                        onCancel = viewModel::cancelVoiceRecording,
                        onSend = viewModel::sendVoiceRecording
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = { showEmojiBar = !showEmojiBar },
                            modifier = Modifier.testTag("emoji_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "Emoji picker",
                                tint = if (showEmojiBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("attach_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "Attach image",
                                tint = if (pendingImageData != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("attach_file_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = input,
                            onValueChange = viewModel::onMessageInputChange,
                            placeholder = {
                                Text(
                                    text = if (pendingImageData != null) "caption..." else "> transmit message...",
                                    fontFamily = CyberMonoFontFamily
                                )
                            },
                            maxLines = 4,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input_field")
                        )

                        Spacer(Modifier.width(6.dp))

                        if (canSend || isSending) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                IconButton(
                                    onClick = viewModel::sendComposerMessage,
                                    enabled = canSend,
                                    modifier = Modifier.testTag("send_message_button")
                                ) {
                                    if (isSending) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send message",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                                modifier = Modifier.size(48.dp)
                            ) {
                                IconButton(
                                    onClick = { requestMicThen(PendingAudioAction.RECORD_VOICE) },
                                    modifier = Modifier.testTag("record_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Record voice message",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val activeErr = errorMessage ?: localMediaError
            if (activeErr != null) {
                SnrErrorBanner(
                    message = activeErr,
                    onDismiss = {
                        viewModel.clearFeedback()
                        localMediaError = null
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            when (val state = messagesState) {
                UiState.Loading -> SnrLoadingState("DECRYPTING_STREAM...")
                is UiState.Error -> SnrErrorBanner(
                    message = state.message,
                    modifier = Modifier.padding(16.dp)
                )
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        SnrEmptyState(
                            icon = Icons.Default.EmojiEmotions,
                            title = "Channel Initialized",
                            subtitle = "Messages synchronize in real time with encrypted metadata."
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.data, key = { it.id }) { msg ->
                                MessageBubbleItem(
                                    message = msg,
                                    isOutgoing = msg.senderId == currentUserId,
                                    isGroup = header.isGroup,
                                    isDarkTheme = isDarkTheme,
                                    playbackState = voicePlaybackState,
                                    onLongClick = { selectedMessageForActions = msg },
                                    onToggleAudio = { viewModel.toggleVoicePlayback(msg) },
                                    onOpenFile = {
                                        MediaUtils.shareOrOpenAttachment(
                                            context = context,
                                            dataUri = msg.mediaData,
                                            fileName = msg.fileName,
                                            mimeType = msg.fileMimeType
                                        ).onFailure { err ->
                                            localMediaError = err.message ?: "Unable to open file."
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

    selectedMessageForActions?.let { msg ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForActions = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                if (!msg.deleted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                viewModel.setReplyTarget(msg)
                                selectedMessageForActions = null
                            }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Text("Reply", style = MaterialTheme.typography.titleMedium)
                    }

                    if (msg.text.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(msg.text))
                                    selectedMessageForActions = null
                                }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(14.dp))
                            Text("Copy Text", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (msg.senderId == currentUserId) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.deleteMessage(msg.id)
                                    selectedMessageForActions = null
                                }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                "Delete Message",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Remove Chat?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("This will permanently delete your conversation and messages with ${header.title}.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteChatDialog = false
                        viewModel.deleteConversation(onBack)
                    },
                    modifier = Modifier.testTag("confirm_delete_chat_room_button")
                ) {
                    Text(
                        text = "Remove Chat",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPendingImagePreview && pendingImageBitmap != null) {
        val fullBmp = pendingImageBitmap
        if (fullBmp != null) {
            Dialog(
                onDismissRequest = { showPendingImagePreview = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = fullBmp.asImageBitmap(),
                        contentDescription = "Staged photo full preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = { showPendingImagePreview = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Report User") },
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
                        label = { Text("Details (Optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.reportPeer(reportReason, reportDetails)
                        showReportDialog = false
                        reportDetails = ""
                    }
                ) {
                    Text("Submit Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VoiceRecordingComposerBar(
    durationMs: Long,
    amplitudes: List<Float>,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "rec_pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("voice_recording_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(48.dp)
                .testTag("cancel_voice_recording_button")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Discard voice recording",
                tint = MaterialTheme.colorScheme.error
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4D6D).copy(alpha = dotAlpha))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "REC // ${TimeFormatters.formatAudioDuration(durationMs)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = CyberMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(12.dp))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val bars = if (amplitudes.isEmpty()) List(16) { 0.2f } else amplitudes.takeLast(18)
                    bars.forEach { ratio ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((4 + 16 * ratio).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(
                onClick = onSend,
                modifier = Modifier.testTag("send_voice_recording_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send voice message",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isOutgoing: Boolean,
    isGroup: Boolean,
    isDarkTheme: Boolean,
    playbackState: VoicePlaybackState,
    onLongClick: () -> Unit,
    onToggleAudio: () -> Unit,
    onOpenFile: () -> Unit
) {
    val bubbleColor = when {
        isOutgoing && isDarkTheme -> SnrOutgoingBubbleDark
        isOutgoing -> SnrOutgoingBubbleLight
        isDarkTheme -> SnrIncomingBubbleDark
        else -> SnrIncomingBubbleLight
    }
    val bubbleBorder = when {
        isOutgoing -> BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.42f))
        isDarkTheme -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f))
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
    }
    val contentColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (isOutgoing) 12.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 12.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOutgoing && isGroup) {
            SnrAvatar(
                name = message.senderName,
                avatarData = message.senderAvatarData,
                size = 30.dp
            )
            Spacer(Modifier.width(8.dp))
        }

        Surface(
            color = bubbleColor,
            border = bubbleBorder,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
                .testTag("message_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isOutgoing && isGroup) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = CyberMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                }

                if (message.replyToMessageId.isNotBlank() && !message.deleted) {
                    Surface(
                        color = if (isOutgoing) {
                            Color.White.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Text(
                                text = message.replyToSenderName.ifBlank { "Reply" },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CyberMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = if (isOutgoing) CyberCyan else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = message.replyToSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (message.deleted) {
                    Text(
                        text = "[REDACTED // MESSAGE_DELETED]",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = CyberMonoFontFamily,
                        fontStyle = FontStyle.Italic,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    when (message.typeEnum()) {
                        MessageType.IMAGE -> {
                            if (message.mediaData.isNotBlank()) {
                                MessageImageAttachment(mediaData = message.mediaData)
                                if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                            }
                            if (message.text.isNotBlank()) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor
                                )
                            }
                        }
                        MessageType.FILE -> {
                            MessageFileAttachment(
                                fileName = message.fileName,
                                fileSizeBytes = message.fileSizeBytes,
                                isOutgoing = isOutgoing,
                                onOpenClick = onOpenFile
                            )
                        }
                        MessageType.AUDIO -> {
                            MessageAudioAttachment(
                                messageId = message.id,
                                durationMs = message.audioDurationMs,
                                isOutgoing = isOutgoing,
                                playbackState = playbackState,
                                onTogglePlay = onToggleAudio
                            )
                        }
                        else -> {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = TimeFormatters.formatMessageTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = CyberMonoFontFamily,
                        color = contentColor.copy(alpha = 0.72f)
                    )
                    if (isOutgoing && !message.deleted) {
                        val readByOthers = message.readBy.any { it != message.senderId }
                        val deliveredToOthers = message.deliveredTo.any { it != message.senderId }
                        when {
                            readByOthers -> Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Read",
                                tint = CyberCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            deliveredToOthers -> Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered",
                                tint = contentColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                            else -> Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                tint = contentColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
