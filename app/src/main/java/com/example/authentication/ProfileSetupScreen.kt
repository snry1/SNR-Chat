package com.example.authentication

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.MediaUtils
import com.example.core.SnrConstants
import com.example.presentation.components.SnrAvatar
import com.example.presentation.components.SnrBrandLogo
import com.example.presentation.components.SnrErrorBanner
import com.example.ui.theme.CyberMonoFontFamily
import com.example.ui.theme.SnrOnlineGreen
import kotlinx.coroutines.launch

@Composable
fun ProfileSetupScreen(
    uiState: ProfileSetupUiState,
    onDisplayNameChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onAvatarPicked: (String) -> Unit,
    onSubmit: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                MediaUtils.encodeImageUriToDataUri(
                    context = context,
                    uri = uri,
                    maxDimension = 512,
                    maxStringLen = SnrConstants.MAX_AVATAR_DATA_LEN
                ).onSuccess { encoded ->
                    onAvatarPicked(encoded)
                }
            }
        }
    }

    val cyberFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SnrBrandLogo(size = 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "DayunanChat",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "[INIT_OPERATOR_ID]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = CyberMonoFontFamily,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onSignOut) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Sign out"
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Register your identity",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Assign a unique @username handle so peers can verify and message you on DayunanChat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .testTag("setup_avatar_picker")
            ) {
                SnrAvatar(
                    name = uiState.displayName.ifBlank { "DC" },
                    avatarData = uiState.avatarData,
                    size = 96.dp
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Upload avatar",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Display Name *") },
                placeholder = { Text("e.g. Sinar Public") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = cyberFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_display_name_input")
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text("Unique Handle *") },
                placeholder = { Text("sinar23") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    when {
                        uiState.isCheckingUsername -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        uiState.isUsernameAvailable == true -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Available",
                                tint = SnrOnlineGreen
                            )
                        }
                        uiState.isUsernameAvailable == false -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Unavailable",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                supportingText = {
                    val hint = uiState.usernameHint
                        ?: "3-24 chars: lowercase letters, numbers, or _"
                    Text(
                        text = hint,
                        fontFamily = CyberMonoFontFamily,
                        color = when (uiState.isUsernameAvailable) {
                            true -> SnrOnlineGreen
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = cyberFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_username_input")
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = uiState.bio,
                onValueChange = onBioChange,
                label = { Text("Operator Bio (Optional)") },
                placeholder = { Text("Building secure systems & staying connected") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(10.dp),
                colors = cyberFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_bio_input")
            )

            if (uiState.errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                SnrErrorBanner(message = uiState.errorMessage)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting &&
                    uiState.displayName.isNotBlank() &&
                    uiState.isUsernameAvailable != false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("complete_profile_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("PROVISIONING_NODE...", fontFamily = CyberMonoFontFamily)
                } else {
                    Text(
                        text = "Initialize Messaging",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
