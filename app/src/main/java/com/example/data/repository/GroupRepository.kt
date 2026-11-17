package com.example.data.repository

import com.example.core.ConversationType
import com.example.core.GroupInvitationStatus
import com.example.core.GroupRole
import com.example.core.MessageType
import com.example.core.NotificationType
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.handleFirestoreError
import com.example.domain.model.GroupInfo
import com.example.domain.model.GroupInvitation
import com.example.domain.model.GroupMember
import com.example.domain.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class GroupRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun observeMyGroups(userId: String): Flow<List<GroupInfo>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.GROUPS
        db.collection(path)
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(GroupInfo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    fun observeGroup(groupId: String): Flow<GroupInfo?> = flow {
        val uid = requireUserId()
        val path = "${SnrConstants.Collection.GROUPS}/$groupId"
        db.collection(SnrConstants.Collection.GROUPS)
            .document(groupId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(GroupInfo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.GET, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    fun observeGroupMembers(groupId: String, userId: String): Flow<List<GroupMember>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.GROUP_MEMBERS
        db.collection(path)
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(GroupMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.groupId == groupId }
                    .sortedBy {
                        when (it.roleEnum()) {
                            GroupRole.OWNER -> 0
                            GroupRole.ADMIN -> 1
                            GroupRole.MEMBER -> 2
                        }
                    }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    fun observeInvitations(userId: String): Flow<List<GroupInvitation>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.GROUP_INVITATIONS
        db.collection(path)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(GroupInvitation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    suspend fun createGroup(
        creator: UserProfile,
        name: String,
        description: String,
        avatarData: String,
        friendsToAdd: List<UserProfile>,
        sendInvitesInsteadOfDirectAdd: Boolean = false
    ): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val cleanName = name.trim().take(SnrConstants.MAX_GROUP_NAME_LEN)
        val cleanDesc = description.trim().take(SnrConstants.MAX_GROUP_DESC_LEN)
        val cleanAvatar = avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN)

        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Group name is required."))
        }

        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document()
        val groupId = groupRef.id
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(groupId)

        val initialMembers = if (sendInvitesInsteadOfDirectAdd) {
            listOf(creator)
        } else {
            (listOf(creator) + friendsToAdd).distinctBy { it.userId }.take(95)
        }
        val memberUids = initialMembers.map { it.userId }
        val unreadMap = memberUids.associateWith { 0L }

        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.set(
                    groupRef,
                    mapOf(
                        "name" to cleanName,
                        "description" to cleanDesc,
                        "avatarData" to cleanAvatar,
                        "ownerId" to uid,
                        "adminUids" to listOf(uid),
                        "memberUids" to memberUids,
                        "conversationId" to groupId,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
                batch.set(
                    convRef,
                    mapOf(
                        "type" to ConversationType.GROUP.name,
                        "participantUids" to memberUids,
                        "title" to cleanName,
                        "description" to cleanDesc,
                        "avatarData" to cleanAvatar,
                        "ownerId" to uid,
                        "adminUids" to listOf(uid),
                        "lastMessageText" to "${creator.displayName} created $cleanName",
                        "lastMessageType" to MessageType.SYSTEM.name,
                        "lastMessageSenderId" to uid,
                        "lastMessageAt" to now,
                        "typingUids" to emptyList<String>(),
                        "unreadCounts" to unreadMap,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
                initialMembers.forEach { member ->
                    val role = if (member.userId == uid) GroupRole.OWNER else GroupRole.MEMBER
                    val memberDocRef = db.collection(SnrConstants.Collection.GROUP_MEMBERS)
                        .document("${groupId}_${member.userId}")
                    batch.set(
                        memberDocRef,
                        mapOf(
                            "groupId" to groupId,
                            "userId" to member.userId,
                            "displayName" to member.displayName.take(50),
                            "username" to member.username.take(24),
                            "avatarData" to member.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                            "role" to role.name,
                            "memberUids" to memberUids,
                            "joinedAt" to now,
                            "updatedAt" to now
                        )
                    )
                }
            }.await()

            if (sendInvitesInsteadOfDirectAdd) {
                friendsToAdd.filter { it.userId != uid }.forEach { friend ->
                    sendGroupInvitation(
                        groupId = groupId,
                        groupName = cleanName,
                        groupAvatar = cleanAvatar,
                        inviter = creator,
                        targetUserId = friend.userId
                    )
                }
            }

            Result.success(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendGroupInvitation(
        groupId: String,
        groupName: String,
        groupAvatar: String,
        inviter: UserProfile,
        targetUserId: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val inviteId = "${groupId}_${targetUserId}"
        val inviteRef = db.collection(SnrConstants.Collection.GROUP_INVITATIONS).document(inviteId)

        val existing = try {
            db.collection(SnrConstants.Collection.GROUP_INVITATIONS)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
                .documents
                .firstOrNull { it.id == inviteId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.GROUP_INVITATIONS)
            return Result.failure(e)
        }

        val now = FieldValue.serverTimestamp()
        val opType = if (existing != null) OperationType.UPDATE else OperationType.CREATE
        try {
            if (existing != null) {
                inviteRef.update(
                    mapOf(
                        "status" to GroupInvitationStatus.PENDING.name,
                        "updatedAt" to now
                    )
                ).await()
            } else {
                inviteRef.set(
                    mapOf(
                        "groupId" to groupId,
                        "groupName" to groupName.trim().ifBlank { "SNR Group" }.take(60),
                        "groupAvatarData" to groupAvatar.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "fromUserId" to uid,
                        "fromDisplayName" to inviter.displayName.trim().ifBlank { "SNR User" }.take(50),
                        "toUserId" to targetUserId,
                        "participantUids" to listOf(uid, targetUserId),
                        "status" to GroupInvitationStatus.PENDING.name,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, opType, inviteRef.path)
            return Result.failure(e)
        }

        val notifId = "gi_${groupId}_${targetUserId}_${System.currentTimeMillis()}"
        runCatching {
            db.collection(SnrConstants.Collection.NOTIFICATIONS).document(notifId).set(
                mapOf(
                    "recipientId" to targetUserId,
                    "senderId" to uid,
                    "senderName" to inviter.displayName.trim().ifBlank { "SNR User" }.take(50),
                    "type" to NotificationType.GROUP_INVITE.name,
                    "title" to "Group Invitation",
                    "body" to "${inviter.displayName.ifBlank { "Someone" }} invited you to join $groupName.".take(300),
                    "targetId" to groupId,
                    "dedupeKey" to notifId,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        return Result.success(Unit)
    }

    suspend fun respondToGroupInvitation(
        invitation: GroupInvitation,
        accept: Boolean,
        currentUser: UserProfile
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val inviteRef = db.collection(SnrConstants.Collection.GROUP_INVITATIONS).document(invitation.id)
        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(invitation.groupId)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(invitation.groupId)

        return try {
            if (!accept) {
                inviteRef.update(
                    mapOf(
                        "status" to GroupInvitationStatus.DECLINED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                return Result.success(Unit)
            }

            val groupSnap = groupRef.get().await()
            val group = groupSnap.toObject(GroupInfo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Group no longer exists."))
            val alreadyMember = group.memberUids.contains(uid)
            val updatedMembers = (group.memberUids + uid).distinct().take(100)

            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(
                    inviteRef,
                    mapOf(
                        "status" to GroupInvitationStatus.ACCEPTED.name,
                        "updatedAt" to now
                    )
                )
                if (!alreadyMember) {
                    batch.update(
                        groupRef,
                        mapOf(
                            "memberUids" to updatedMembers,
                            "updatedAt" to now
                        )
                    )
                    batch.update(
                        convRef,
                        mapOf(
                            "participantUids" to updatedMembers,
                            "updatedAt" to now
                        )
                    )
                    batch.set(
                        db.collection(SnrConstants.Collection.GROUP_MEMBERS).document("${group.id}_$uid"),
                    mapOf(
                        "groupId" to group.id,
                        "userId" to uid,
                        "displayName" to currentUser.displayName.take(50),
                        "username" to currentUser.username.take(24),
                        "avatarData" to currentUser.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "role" to GroupRole.MEMBER.name,
                        "memberUids" to updatedMembers,
                            "joinedAt" to now,
                            "updatedAt" to now
                        )
                    )
                }
            }.await()

            // Sync memberUids across existing member records
            syncMemberUidsAcrossGroupMembers(group.id, updatedMembers)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, inviteRef.path)
            Result.failure(e)
        }
    }

    suspend fun addFriendDirectlyToGroup(
        group: GroupInfo,
        friend: UserProfile
    ): Result<Unit> {
        requireUserId()
        if (group.memberUids.contains(friend.userId)) {
            return Result.failure(IllegalStateException("${friend.displayName} is already in the group."))
        }
        val updatedMembers = (group.memberUids + friend.userId).distinct().take(100)
        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(group.id)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(group.id)
        val memberRef = db.collection(SnrConstants.Collection.GROUP_MEMBERS).document("${group.id}_${friend.userId}")

        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(
                    groupRef,
                    mapOf(
                        "memberUids" to updatedMembers,
                        "updatedAt" to now
                    )
                )
                batch.update(
                    convRef,
                    mapOf(
                        "participantUids" to updatedMembers,
                        "updatedAt" to now
                    )
                )
                batch.set(
                    memberRef,
                    mapOf(
                        "groupId" to group.id,
                        "userId" to friend.userId,
                        "displayName" to friend.displayName.take(50),
                        "username" to friend.username.take(24),
                        "avatarData" to friend.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "role" to GroupRole.MEMBER.name,
                        "memberUids" to updatedMembers,
                        "joinedAt" to now,
                        "updatedAt" to now
                    )
                )
            }.await()
            syncMemberUidsAcrossGroupMembers(group.id, updatedMembers)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(
        group: GroupInfo,
        targetMember: GroupMember,
        promoteToAdmin: Boolean
    ): Result<Unit> {
        requireUserId()
        if (targetMember.userId == group.ownerId) {
            return Result.failure(IllegalArgumentException("Cannot change the owner's role."))
        }
        val nextAdmins = if (promoteToAdmin) {
            (group.adminUids + targetMember.userId).distinct()
        } else {
            group.adminUids.filter { it != targetMember.userId }.ifEmpty { listOf(group.ownerId) }
        }
        val nextRole = if (promoteToAdmin) GroupRole.ADMIN else GroupRole.MEMBER

        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(group.id)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(group.id)
        val memberRef = db.collection(SnrConstants.Collection.GROUP_MEMBERS).document(targetMember.id)

        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(groupRef, mapOf("adminUids" to nextAdmins, "updatedAt" to now))
                batch.update(convRef, mapOf("adminUids" to nextAdmins, "updatedAt" to now))
                batch.update(memberRef, mapOf("role" to nextRole.name, "updatedAt" to now))
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeMember(group: GroupInfo, targetUserId: String): Result<Unit> {
        requireUserId()
        if (targetUserId == group.ownerId) {
            return Result.failure(IllegalArgumentException("Group owner cannot be removed."))
        }
        val nextMembers = group.memberUids.filter { it != targetUserId }
        val nextAdmins = group.adminUids.filter { it != targetUserId }.ifEmpty { listOf(group.ownerId) }

        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(group.id)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(group.id)
        val memberRef = db.collection(SnrConstants.Collection.GROUP_MEMBERS).document("${group.id}_$targetUserId")

        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(
                    groupRef,
                    mapOf("memberUids" to nextMembers, "adminUids" to nextAdmins, "updatedAt" to now)
                )
                batch.update(
                    convRef,
                    mapOf("participantUids" to nextMembers, "adminUids" to nextAdmins, "updatedAt" to now)
                )
                batch.delete(memberRef)
            }.await()
            syncMemberUidsAcrossGroupMembers(group.id, nextMembers)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun leaveGroup(group: GroupInfo): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val remainingMembers = group.memberUids.filter { it != uid }
        if (uid == group.ownerId && remainingMembers.isNotEmpty()) {
            return Result.failure(
                IllegalStateException("As Owner, remove other members or transfer group before leaving.")
            )
        }

        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(group.id)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(group.id)
        val memberRef = db.collection(SnrConstants.Collection.GROUP_MEMBERS).document("${group.id}_$uid")

        return try {
            if (remainingMembers.isEmpty()) {
                db.runBatch { batch ->
                    batch.delete(memberRef)
                    batch.delete(convRef)
                    batch.delete(groupRef)
                }.await()
            } else {
                val nextAdmins = group.adminUids.filter { it != uid }
                db.runBatch { batch ->
                    val now = FieldValue.serverTimestamp()
                    batch.update(
                        groupRef,
                        mapOf("memberUids" to remainingMembers, "adminUids" to nextAdmins, "updatedAt" to now)
                    )
                    batch.update(
                        convRef,
                        mapOf("participantUids" to remainingMembers, "adminUids" to nextAdmins, "updatedAt" to now)
                    )
                    batch.delete(memberRef)
                }.await()
                syncMemberUidsAcrossGroupMembers(group.id, remainingMembers)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateGroupSettings(
        groupId: String,
        name: String,
        description: String,
        avatarData: String
    ): Result<Unit> {
        requireUserId()
        val cleanName = name.trim().take(SnrConstants.MAX_GROUP_NAME_LEN)
        val cleanDesc = description.trim().take(SnrConstants.MAX_GROUP_DESC_LEN)
        val cleanAvatar = avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN)
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Group name cannot be empty."))
        }
        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(groupId)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(groupId)
        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(
                    groupRef,
                    mapOf(
                        "name" to cleanName,
                        "description" to cleanDesc,
                        "avatarData" to cleanAvatar,
                        "updatedAt" to now
                    )
                )
                batch.update(
                    convRef,
                    mapOf(
                        "title" to cleanName,
                        "description" to cleanDesc,
                        "avatarData" to cleanAvatar,
                        "updatedAt" to now
                    )
                )
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, groupRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteGroup(group: GroupInfo): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        if (group.ownerId != uid) {
            return Result.failure(IllegalStateException("Only the group owner can delete this group."))
        }
        val groupRef = db.collection(SnrConstants.Collection.GROUPS).document(group.id)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(group.conversationId)

        return try {
            // 1. Delete group messages while conversation still exists for rule check
            val msgSnap = convRef.collection(SnrConstants.Collection.MESSAGES)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
            msgSnap.documents.chunked(400).forEach { chunk ->
                db.runBatch { batch ->
                    chunk.forEach { doc -> batch.delete(doc.reference) }
                }.await()
            }

            // 2. Delete all group_members BEFORE deleting /groups/{groupId} (rules call get(/groups/{groupId}))
            val memberSnap = db.collection(SnrConstants.Collection.GROUP_MEMBERS)
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            val memberDocs = memberSnap.documents.filter { it.getString("groupId") == group.id }
            memberDocs.chunked(400).forEach { chunk ->
                db.runBatch { batch ->
                    chunk.forEach { doc -> batch.delete(doc.reference) }
                }.await()
            }

            // 3. Best-effort cleanup of invitations created for this group
            runCatching {
                val inviteSnap = db.collection(SnrConstants.Collection.GROUP_INVITATIONS)
                    .whereArrayContains("participantUids", uid)
                    .get()
                    .await()
                val invites = inviteSnap.documents.filter { it.getString("groupId") == group.id }
                if (invites.isNotEmpty()) {
                    db.runBatch { batch ->
                        invites.forEach { batch.delete(it.reference) }
                    }.await()
                }
            }

            // 4. Finally delete conversation and group documents
            db.runBatch { batch ->
                batch.delete(convRef)
                batch.delete(groupRef)
            }.await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, groupRef.path)
            Result.failure(e)
        }
    }

    private suspend fun syncMemberUidsAcrossGroupMembers(groupId: String, memberUids: List<String>) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val snap = db.collection(SnrConstants.Collection.GROUP_MEMBERS)
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            val docs = snap.documents.filter { it.getString("groupId") == groupId }
            if (docs.isNotEmpty()) {
                db.runBatch { batch ->
                    docs.forEach { doc ->
                        batch.update(
                            doc.reference,
                            mapOf(
                                "memberUids" to memberUids,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }.await()
            }
        }
    }
}
