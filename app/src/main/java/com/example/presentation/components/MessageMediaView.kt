package com.example.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.core.MediaUtils
import com.example.core.TimeFormatters
import com.example.core.VoicePlaybackState
import com.example.ui.theme.CyberMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageImageAttachment(
    mediaData: String,
    modifier: Modifier = Modifier
) {
    var showFullscreen by remember { mutableStateOf(false) }
    val initialCached = remember(mediaData) {
        MediaUtils.getCachedBase64Bitmap(mediaData, maxDimension = 1024)
    }
    val bitmap by produceState<Bitmap?>(initialValue = initialCached, key1 = mediaData) {
        value = initialCached ?: if (mediaData.startsWith("data:image")) {
            withContext(Dispatchers.Default) {
                MediaUtils.decodeBase64Bitmap(mediaData, maxDimension = 1024)
            }
        } else {
            null
        }
    }

    val bmp = bitmap
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { showFullscreen = true }
    ) {
        when {
            bmp != null -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Attached photo",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            }
            mediaData.startsWith("http") -> {
                AsyncImage(
                    model = mediaData,
                    contentDescription = "Attached photo",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }

    if (showFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Full photo preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                } else if (mediaData.startsWith("http")) {
                    AsyncImage(
                        model = mediaData,
                        contentDescription = "Full photo preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                IconButton(
                    onClick = { showFullscreen = false },
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

@Composable
fun MessageFileAttachment(
    fileName: String,
    fileSizeBytes: Long,
    isOutgoing: Boolean,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isOutgoing) {
        Color.White.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenClick),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = "File icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = fileName.ifBlank { "Attachment" },
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = TimeFormatters.formatFileSize(fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = CyberMonoFontFamily,
                        color = contentColor.copy(alpha = 0.78f)
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Open file",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val voiceWaveHeights = listOf(
    0.35f, 0.65f, 0.90f, 0.50f, 0.78f, 1.0f, 0.62f, 0.44f, 0.85f,
    0.95f, 0.55f, 0.72f, 0.40f, 0.88f, 0.68f, 0.48f, 0.75f, 0.38f
)

@Composable
fun MessageAudioAttachment(
    messageId: String,
    durationMs: Long,
    isOutgoing: Boolean,
    playbackState: VoicePlaybackState,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isThisMessage = playbackState.playingMessageId == messageId
    val isPlaying = isThisMessage && playbackState.isPlaying
    val effectiveDuration = if (isThisMessage && playbackState.durationMs > 0L) {
        playbackState.durationMs
    } else {
        durationMs.coerceAtLeast(1000L)
    }
    val progress = if (isThisMessage && effectiveDuration > 0L) {
        (playbackState.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val containerColor = if (isOutgoing) {
        Color.Black.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    }
    val contentColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val activeBarColor = MaterialTheme.colorScheme.primary
    val inactiveBarColor = contentColor.copy(alpha = 0.28f)
    val borderColor = if (isPlaying) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.testTag("voice_play_button_$messageId")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    voiceWaveHeights.forEachIndexed { idx, ratio ->
                        val threshold = idx.toFloat() / voiceWaveHeights.size.toFloat()
                        val filled = isThisMessage && progress > threshold
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((6 + 16 * ratio).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (filled) activeBarColor else inactiveBarColor)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                val timeLabel = if (isThisMessage && playbackState.positionMs > 0L) {
                    "${TimeFormatters.formatAudioDuration(playbackState.positionMs)} / ${TimeFormatters.formatAudioDuration(effectiveDuration)}"
                } else {
                    TimeFormatters.formatAudioDuration(effectiveDuration)
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = CyberMonoFontFamily,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}
