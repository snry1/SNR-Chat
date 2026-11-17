package com.example.calls

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.CallStatus
import com.example.presentation.components.SnrAvatar
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberMonoFontFamily
import com.example.ui.theme.CyberVoid

@Composable
fun CallOverlayScreen(
    callState: ActiveCallUiState,
    onAcceptIncoming: () -> Unit,
    onDeclineIncoming: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "call_pulse")
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        CyberVoid,
                        Color(0xFF071E24),
                        CyberVoid
                    )
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .testTag("call_overlay_screen")
    ) {
        Surface(
            color = CyberEmerald.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = CyberEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "VOICE_LINK // ENCRYPTED",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CyberMonoFontFamily,
                    color = CyberCyan,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (callState.status == CallStatus.RINGING || callState.status == CallStatus.CONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(134.dp)
                            .scale(haloScale)
                            .background(
                                color = if (callState.status == CallStatus.CONNECTED) {
                                    CyberEmerald.copy(alpha = 0.22f)
                                } else {
                                    CyberCyan.copy(alpha = 0.22f)
                                },
                                shape = CircleShape
                            )
                    )
                }
                SnrAvatar(
                    name = callState.peerName,
                    avatarData = callState.peerAvatarData,
                    size = 112.dp
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = callState.peerName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = callState.statusSubtitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = CyberMonoFontFamily,
                color = if (callState.status == CallStatus.CONNECTED) {
                    CyberEmerald
                } else {
                    CyberCyan
                },
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (callState.isIncoming && callState.status == CallStatus.RINGING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallCircleAction(
                        icon = Icons.Default.CallEnd,
                        label = "Decline",
                        containerColor = Color(0xFFD9264E),
                        iconTint = Color.White,
                        sizeDp = 68,
                        testTag = "decline_call_button",
                        onClick = onDeclineIncoming
                    )
                    CallCircleAction(
                        icon = Icons.Default.Call,
                        label = "Accept",
                        containerColor = CyberEmerald,
                        iconTint = Color(0xFF021C12),
                        sizeDp = 68,
                        testTag = "accept_call_button",
                        onClick = onAcceptIncoming
                    )
                }
            } else {
                Surface(
                    color = Color(0xFF0F1926).copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, Color(0xFF1E374B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallCircleAction(
                            icon = if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (callState.isMuted) "Unmute" else "Mute",
                            containerColor = if (callState.isMuted) {
                                CyberEmerald
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            iconTint = if (callState.isMuted) Color(0xFF021C12) else Color.White,
                            sizeDp = 56,
                            testTag = "call_mute_button",
                            onClick = onToggleMute
                        )

                        CallCircleAction(
                            icon = Icons.Default.CallEnd,
                            label = "End",
                            containerColor = Color(0xFFD9264E),
                            iconTint = Color.White,
                            sizeDp = 66,
                            testTag = "call_end_button",
                            onClick = onHangUp
                        )

                        CallCircleAction(
                            icon = if (callState.isSpeakerOn) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.Default.Hearing
                            },
                            label = if (callState.isSpeakerOn) "Speaker On" else "Speaker",
                            containerColor = if (callState.isSpeakerOn) {
                                CyberCyan
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            iconTint = if (callState.isSpeakerOn) Color(0xFF021A24) else Color.White,
                            sizeDp = 56,
                            testTag = "call_speaker_button",
                            onClick = onToggleSpeaker
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallCircleAction(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    iconTint: Color,
    sizeDp: Int,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            modifier = Modifier.size(sizeDp.dp)
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(testTag)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size((sizeDp * 0.44f).dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = CyberMonoFontFamily,
            color = Color.White.copy(alpha = 0.88f)
        )
    }
}
