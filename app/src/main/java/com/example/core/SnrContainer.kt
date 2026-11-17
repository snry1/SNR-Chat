package com.example.core

import android.content.Context
import com.example.R
import com.example.calls.CallController
import com.example.calls.CallRepository
import com.example.data.repository.AuthRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.GroupRepository
import com.example.data.repository.NotificationRepository
import com.example.data.repository.ThemePreferenceStore
import com.example.data.repository.UserRepository
import com.example.notifications.SnrNotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class SnrContainer(context: Context) {
    private val appContext = context.applicationContext

    val databaseId: String by lazy { appContext.getString(R.string.firestore_database_id) }
    val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(databaseId) }
    val auth: FirebaseAuth by lazy { Firebase.auth }

    val themePreferenceStore: ThemePreferenceStore by lazy { ThemePreferenceStore(appContext) }
    val notificationHelper: SnrNotificationHelper by lazy { SnrNotificationHelper(appContext) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer(appContext) }

    val authRepository: AuthRepository by lazy { AuthRepository(auth) }
    val userRepository: UserRepository by lazy { UserRepository(firestore, auth) }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(firestore, auth) }
    val chatRepository: ChatRepository by lazy { ChatRepository(firestore, auth) }
    val groupRepository: GroupRepository by lazy { GroupRepository(firestore, auth) }
    val notificationRepository: NotificationRepository by lazy { NotificationRepository(firestore, auth) }
    val callRepository: CallRepository by lazy { CallRepository(firestore, auth) }
    val callController: CallController by lazy {
        CallController(appContext, callRepository, chatRepository, userRepository, voicePlayer)
    }

    fun createVoiceRecorder(): VoiceRecorder = VoiceRecorder(appContext)

    suspend fun warmUp() {
        themePreferenceStore.preload()
        auth
        firestore
        notificationHelper.ensureChannelsCreated()
    }
}

