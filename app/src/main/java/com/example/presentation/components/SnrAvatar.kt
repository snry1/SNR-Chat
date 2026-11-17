package com.example.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.MediaUtils
import com.example.ui.theme.CyberMonoFontFamily
import com.example.ui.theme.SnrOnlineGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val avatarPalettes = listOf(
    listOf(Color(0xFF064E3B), Color(0xFF00B87C)),
    listOf(Color(0xFF0C4A6E), Color(0xFF00B4D8)),
    listOf(Color(0xFF312E81), Color(0xFF6366F1)),
    listOf(Color(0xFF134E4A), Color(0xFF14B8A6)),
    listOf(Color(0xFF1E293B), Color(0xFF0284C7))
)

@Composable
fun SnrAvatar(
    name: String,
    avatarData: String,
    size: Dp = 48.dp,
    showOnlineDot: Boolean = false,
    isOnline: Boolean = false,
    fallbackIcon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val initialCached = remember(avatarData) {
        MediaUtils.getCachedBase64Bitmap(avatarData, maxDimension = 256)
    }
    val decodedBitmap by produceState<Bitmap?>(initialValue = initialCached, key1 = avatarData) {
        value = initialCached ?: if (avatarData.startsWith("data:image")) {
            withContext(Dispatchers.Default) {
                MediaUtils.decodeBase64Bitmap(avatarData, maxDimension = 256)
            }
        } else {
            null
        }
    }
    val initials = remember(name) {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "DC"
        }
    }
    val palette = remember(name) {
        avatarPalettes[abs(name.hashCode()) % avatarPalettes.size]
    }
    val avatarShape = RoundedCornerShape((size * 0.24f).coerceIn(8.dp, 22.dp))
    val ringColor = if (showOnlineDot && isOnline) {
        SnrOnlineGreen.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)
    }

    val avatarBmp = decodedBitmap
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(avatarShape)
                .background(Brush.linearGradient(palette))
                .border(1.2.dp, ringColor, avatarShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                avatarBmp != null -> {
                    Image(
                        bitmap = avatarBmp.asImageBitmap(),
                        contentDescription = "$name avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                avatarData.startsWith("http") -> {
                    AsyncImage(
                        model = avatarData,
                        contentDescription = "$name avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                fallbackIcon != null -> {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = "$name icon",
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.5f)
                    )
                }
                else -> {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontFamily = CyberMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.34f).sp
                    )
                }
            }
        }

        if (showOnlineDot && isOnline) {
            val dotSize = (size * 0.26f).coerceIn(9.dp, 15.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(SnrOnlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}
