const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE = "alice_uid_123";
const BOB = "bob_uid_456";
const CHARLIE = "charlie_uid_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validUserPayload(uid, username, displayName) {
  const ts = new Date(Date.now() - 2000);
  return {
    userId: uid,
    username,
    displayName,
    displayNameLower: displayName.toLowerCase(),
    bio: "Connect Beyond Distance",
    avatarData: "",
    online: true,
    lastSeenAt: ts,
    friendRequestPolicy: "EVERYONE",
    showOnlineStatus: true,
    showLastSeen: true,
    showReadReceipts: true,
    showMessagePreview: true,
    notificationsEnabled: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users are denied across all collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").get());
  await assertFails(unauth.collection("conversations").get());
  await assertFails(unauth.collection("friend_requests").get());
  await assertFails(unauth.collection("groups").get());
  await assertFails(unauth.collection("notifications").get());
  await assertFails(
    unauth.collection("usernames").doc("alice").set({
      userId: ALICE,
      createdAt: new Date(Date.now() - 1000),
    })
  );
});

test("User onboarding creates atomic username + profile + private account", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const ts = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.runTransaction(async (tx) => {
      tx.set(aliceDb.collection("usernames").doc("alice_23"), {
        userId: ALICE,
        createdAt: ts,
      });
      tx.set(
        aliceDb.collection("users").doc(ALICE),
        validUserPayload(ALICE, "alice_23", "Alice Public")
      );
      tx.set(
        aliceDb
          .collection("users")
          .doc(ALICE)
          .collection("private")
          .doc("account"),
        {
          userId: ALICE,
          email: "alice@example.com",
          darkModePreference: "SYSTEM",
          createdAt: ts,
          updatedAt: ts,
        }
      );
    })
  );

  // Bob cannot claim Alice's username or read Alice's private account
  const bobDb = testEnv.authenticatedContext(BOB).firestore();
  await assertFails(
    bobDb.collection("usernames").doc("alice_23").set({
      userId: BOB,
      createdAt: ts,
    })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE)
      .collection("private")
      .doc("account")
      .get()
  );
  // Bob cannot list usernames
  await assertFails(bobDb.collection("usernames").get());

  // Alice can update profile & privacy settings; cannot mutate createdAt or userId
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE).update({
      displayName: "Alice S",
      displayNameLower: "alice s",
      bio: "Updated bio",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Friend request respects privacy policy, block list, and acceptance creates friendship", async () => {
  const ts = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("usernames").doc("alice").set({ userId: ALICE, createdAt: ts });
    await db.collection("usernames").doc("bob").set({ userId: BOB, createdAt: ts });
    await db.collection("users").doc(ALICE).set(validUserPayload(ALICE, "alice", "Alice"));
    await db.collection("users").doc(BOB).set(validUserPayload(BOB, "bob", "Bob"));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();
  const reqId = `${ALICE}_${BOB}`;

  // Self friend request fails
  await assertFails(
    aliceDb.collection("friend_requests").doc(`${ALICE}_${ALICE}`).set({
      fromUserId: ALICE,
      toUserId: ALICE,
      fromDisplayName: "Alice",
      fromUsername: "alice",
      fromAvatarData: "",
      toDisplayName: "Alice",
      toUsername: "alice",
      toAvatarData: "",
      participantUids: [ALICE, ALICE],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid friend request succeeds
  await assertSucceeds(
    aliceDb.collection("friend_requests").doc(reqId).set({
      fromUserId: ALICE,
      toUserId: BOB,
      fromDisplayName: "Alice",
      fromUsername: "alice",
      fromAvatarData: "",
      toDisplayName: "Bob",
      toUsername: "bob",
      toAvatarData: "",
      participantUids: [ALICE, BOB],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Scoped list query succeeds; unfiltered query fails
  await assertSucceeds(
    bobDb
      .collection("friend_requests")
      .where("participantUids", "array-contains", BOB)
      .get()
  );
  await assertFails(bobDb.collection("friend_requests").get());

  // Bob accepts and creates friendship
  await assertSucceeds(
    bobDb.collection("friend_requests").doc(reqId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  const [a, b] = [ALICE, BOB].sort();
  await assertSucceeds(
    bobDb.collection("friendships").doc(`${a}_${b}`).set({
      userAId: a,
      userBId: b,
      participantUids: [a, b],
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // Charlie cannot read Bob/Alice friendship
  const charlieDb = testEnv.authenticatedContext(CHARLIE).firestore();
  await assertFails(charlieDb.collection("friendships").doc(`${a}_${b}`).get());
});

test("Personal chat messages & read receipts enforce participant isolation", async () => {
  const ts = new Date(Date.now() - 5000);
  const convId = "dm_alice_bob";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("conversations").doc(convId).set({
      type: "DIRECT",
      participantUids: [ALICE, BOB],
      title: "",
      description: "",
      avatarData: "",
      ownerId: ALICE,
      adminUids: [],
      lastMessageText: "",
      lastMessageType: "TEXT",
      lastMessageSenderId: "",
      typingUids: [],
      unreadCounts: { [ALICE]: 0, [BOB]: 0 },
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE).firestore();

  const msgId = "msg_001";
  await assertSucceeds(
    aliceDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc(msgId)
      .set({
        conversationId: convId,
        senderId: ALICE,
        senderName: "Alice",
        senderAvatarData: "",
        participantUids: [ALICE, BOB],
        type: "TEXT",
        text: "Hello Bob!",
        mediaData: "",
        fileName: "",
        fileMimeType: "",
        fileSizeBytes: 0,
        replyToMessageId: "",
        replyToSenderName: "",
        replyToSnippet: "",
        deliveredTo: [ALICE],
        readBy: [ALICE],
        deleted: false,
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Bob can query & mark read; Charlie cannot read or write
  await assertSucceeds(
    bobDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .where("participantUids", "array-contains", BOB)
      .get()
  );
  await assertSucceeds(
    bobDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc(msgId)
      .update({
        deliveredTo: [ALICE, BOB],
        readBy: [ALICE, BOB],
        updatedAt: new Date(Date.now() - 1000),
      })
  );
  // Bob cannot edit Alice's text
  await assertFails(
    bobDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc(msgId)
      .update({
        text: "Tampered",
        updatedAt: new Date(Date.now() - 1000),
      })
  );
  await assertFails(
    charlieDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc(msgId)
      .get()
  );
});

test("Group roles enforce admin-only settings and invitation acceptance", async () => {
  const ts = new Date(Date.now() - 5000);
  const groupId = "grp_team_snr";
  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();

  // Alice creates group + conversation + owner member record
  await assertSucceeds(
    aliceDb.runTransaction(async (tx) => {
      tx.set(aliceDb.collection("groups").doc(groupId), {
        name: "SNR Core",
        description: "Engineering squad",
        avatarData: "",
        ownerId: ALICE,
        adminUids: [ALICE],
        memberUids: [ALICE],
        conversationId: groupId,
        createdAt: ts,
        updatedAt: ts,
      });
      tx.set(aliceDb.collection("conversations").doc(groupId), {
        type: "GROUP",
        participantUids: [ALICE],
        title: "SNR Core",
        description: "Engineering squad",
        avatarData: "",
        ownerId: ALICE,
        adminUids: [ALICE],
        lastMessageText: "Group created",
        lastMessageType: "SYSTEM",
        lastMessageSenderId: ALICE,
        typingUids: [],
        unreadCounts: { [ALICE]: 0 },
        createdAt: ts,
        updatedAt: ts,
      });
      tx.set(aliceDb.collection("group_members").doc(`${groupId}_${ALICE}`), {
        groupId,
        userId: ALICE,
        displayName: "Alice",
        username: "alice",
        avatarData: "",
        role: "OWNER",
        memberUids: [ALICE],
        joinedAt: ts,
        updatedAt: ts,
      });
    })
  );

  // Alice invites Bob
  await assertSucceeds(
    aliceDb.collection("group_invitations").doc(`${groupId}_${BOB}`).set({
      groupId,
      groupName: "SNR Core",
      groupAvatarData: "",
      fromUserId: ALICE,
      fromDisplayName: "Alice",
      toUserId: BOB,
      participantUids: [ALICE, BOB],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob accepts invitation and joins group
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const now = new Date(Date.now() - 1000);
      tx.update(bobDb.collection("group_invitations").doc(`${groupId}_${BOB}`), {
        status: "ACCEPTED",
        updatedAt: now,
      });
      tx.update(bobDb.collection("groups").doc(groupId), {
        memberUids: [ALICE, BOB],
        updatedAt: now,
      });
      tx.update(bobDb.collection("conversations").doc(groupId), {
        participantUids: [ALICE, BOB],
        updatedAt: now,
      });
      tx.set(bobDb.collection("group_members").doc(`${groupId}_${BOB}`), {
        groupId,
        userId: BOB,
        displayName: "Bob",
        username: "bob",
        avatarData: "",
        role: "MEMBER",
        memberUids: [ALICE, BOB],
        joinedAt: now,
        updatedAt: now,
      });
    })
  );

  // Non-admin Bob cannot rename group or promote himself
  await assertFails(
    bobDb.collection("groups").doc(groupId).update({
      name: "Hijacked Name",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("groups").doc(groupId).update({
      adminUids: [ALICE, BOB],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Admin Alice can rename group and update conversation title
  await assertSucceeds(
    aliceDb.runTransaction(async (tx) => {
      const now = new Date(Date.now() - 400);
      tx.update(aliceDb.collection("groups").doc(groupId), {
        name: "SNR Core v2",
        description: "Updated squad",
        avatarData: "",
        updatedAt: now,
      });
      tx.update(aliceDb.collection("conversations").doc(groupId), {
        title: "SNR Core v2",
        description: "Updated squad",
        avatarData: "",
        updatedAt: now,
      });
    })
  );
});

test("Pre-create existence get checks succeed for non-existent documents", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  await assertSucceeds(aliceDb.collection("conversations").doc("dm_missing").get());
  await assertSucceeds(aliceDb.collection("friendships").doc("missing_pair").get());
  await assertSucceeds(aliceDb.collection("friend_requests").doc("missing_req").get());
  await assertSucceeds(aliceDb.collection("group_invitations").doc("missing_inv").get());
  await assertSucceeds(aliceDb.collection("user_devices").doc("missing_dev").get());
});

test("Friend requests handle missing friendRequestPolicy, NO_ONE policy, blocks, and re-sends", async () => {
  const ts = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE).set(validUserPayload(ALICE, "alice", "Alice"));
    // Bob doc intentionally omits friendRequestPolicy to verify rules fallback
    const bobDoc = validUserPayload(BOB, "bob", "Bob");
    delete bobDoc.friendRequestPolicy;
    await db.collection("users").doc(BOB).set(bobDoc);

    // Charlie rejects all friend requests
    const charlieDoc = validUserPayload(CHARLIE, "charlie", "Charlie");
    charlieDoc.friendRequestPolicy = "NO_ONE";
    await db.collection("users").doc(CHARLIE).set(charlieDoc);
  });

  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();

  // Scoped list queries succeed even when collections are empty
  await assertSucceeds(
    aliceDb.collection("friendships").where("participantUids", "array-contains", ALICE).get()
  );
  await assertSucceeds(
    aliceDb.collection("friend_requests").where("participantUids", "array-contains", ALICE).get()
  );
  await assertSucceeds(
    aliceDb.collection("blocked_users").where("blockerId", "==", ALICE).get()
  );

  // Alice -> Charlie denied when NO_ONE
  await assertFails(
    aliceDb.collection("friend_requests").doc(`${ALICE}_${CHARLIE}`).set({
      fromUserId: ALICE,
      toUserId: CHARLIE,
      fromDisplayName: "Alice",
      fromUsername: "alice",
      fromAvatarData: "",
      toDisplayName: "Charlie",
      toUsername: "charlie",
      toAvatarData: "",
      participantUids: [ALICE, CHARLIE],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice -> Bob succeeds even when friendRequestPolicy is absent
  const reqId = `${ALICE}_${BOB}`;
  await assertSucceeds(
    aliceDb.collection("friend_requests").doc(reqId).set({
      fromUserId: ALICE,
      toUserId: BOB,
      fromDisplayName: "Alice",
      fromUsername: "alice",
      fromAvatarData: "",
      toDisplayName: "Bob",
      toUsername: "bob",
      toAvatarData: "",
      participantUids: [ALICE, BOB],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob declines, then Alice re-sends updating metadata
  await assertSucceeds(
    bobDb.collection("friend_requests").doc(reqId).update({
      status: "REJECTED",
      updatedAt: new Date(Date.now() - 2000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("friend_requests").doc(reqId).update({
      status: "PENDING",
      fromDisplayName: "Alice Updated",
      fromUsername: "alice",
      fromAvatarData: "",
      toDisplayName: "Bob Updated",
      toUsername: "bob",
      toAvatarData: "",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Voice messages (AUDIO) and WebRTC call signaling enforce participant security", async () => {
  const ts = new Date(Date.now() - 5000);
  const convId = "dm_alice_bob";
  const callId = "call_001";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("conversations").doc(convId).set({
      type: "DIRECT",
      participantUids: [ALICE, BOB],
      title: "",
      description: "",
      avatarData: "",
      ownerId: ALICE,
      adminUids: [],
      lastMessageText: "",
      lastMessageType: "TEXT",
      lastMessageSenderId: "",
      typingUids: [],
      unreadCounts: { [ALICE]: 0, [BOB]: 0 },
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE).firestore();

  // Audio message succeeds with audioDurationMs
  await assertSucceeds(
    aliceDb
      .collection("conversations")
      .doc(convId)
      .collection("messages")
      .doc("voice_1")
      .set({
        conversationId: convId,
        senderId: ALICE,
        senderName: "Alice",
        senderAvatarData: "",
        participantUids: [ALICE, BOB],
        type: "AUDIO",
        text: "",
        mediaData: "data:audio/mp4;base64,AAAA",
        fileName: "voice_note.m4a",
        fileMimeType: "audio/mp4",
        fileSizeBytes: 1280,
        audioDurationMs: 4200,
        replyToMessageId: "",
        replyToSenderName: "",
        replyToSnippet: "",
        deliveredTo: [ALICE],
        readBy: [ALICE],
        deleted: false,
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Alice starts call
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).set({
      callerId: ALICE,
      callerName: "Alice",
      callerAvatarData: "",
      calleeId: BOB,
      calleeName: "Bob",
      calleeAvatarData: "",
      conversationId: convId,
      participantUids: [ALICE, BOB],
      status: "RINGING",
      offerSdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n",
      answerSdp: "",
      endedBy: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Charlie cannot read call or add ICE candidates
  await assertFails(charlieDb.collection("calls").doc(callId).get());
  await assertFails(
    charlieDb
      .collection("calls")
      .doc(callId)
      .collection("candidates")
      .doc("c_evil")
      .set({
        callId,
        senderId: CHARLIE,
        participantUids: [CHARLIE, BOB],
        sdpMid: "0",
        sdpMLineIndex: 0,
        sdp: "candidate:1 1 UDP 2122252543 127.0.0.1 50000 typ host",
        createdAt: ts,
      })
  );

  // Alice & Bob exchange ICE candidates
  await assertSucceeds(
    aliceDb
      .collection("calls")
      .doc(callId)
      .collection("candidates")
      .doc("c_alice_1")
      .set({
        callId,
        senderId: ALICE,
        participantUids: [ALICE, BOB],
        sdpMid: "0",
        sdpMLineIndex: 0,
        sdp: "candidate:1 1 UDP 2122252543 127.0.0.1 50000 typ host",
        createdAt: ts,
      })
  );

  // Bob answers call and ends it later
  await assertSucceeds(
    bobDb.collection("calls").doc(callId).update({
      status: "CONNECTED",
      answerSdp: "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n",
      connectedAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).update({
      status: "ENDED",
      endedBy: ALICE,
      endedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Remove direct chat, remove contact (friendship), and owner-only remove group", async () => {
  const ts = new Date(Date.now() - 5000);
  const convId = "dm_alice_bob";
  const groupId = "grp_delete_test";
  const [a, b] = [ALICE, BOB].sort();
  const friendshipId = `${a}_${b}`;

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("friendships").doc(friendshipId).set({
      userAId: a,
      userBId: b,
      participantUids: [a, b],
      createdAt: ts,
    });
    await db.collection("conversations").doc(convId).set({
      type: "DIRECT",
      participantUids: [ALICE, BOB],
      title: "",
      description: "",
      avatarData: "",
      ownerId: ALICE,
      adminUids: [],
      lastMessageText: "hi",
      lastMessageType: "TEXT",
      lastMessageSenderId: ALICE,
      typingUids: [],
      unreadCounts: { [ALICE]: 0, [BOB]: 0 },
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("conversations").doc(convId).collection("messages").doc("m1").set({
      conversationId: convId,
      senderId: ALICE,
      senderName: "Alice",
      senderAvatarData: "",
      participantUids: [ALICE, BOB],
      type: "TEXT",
      text: "Hello",
      mediaData: "",
      fileName: "",
      fileMimeType: "",
      fileSizeBytes: 0,
      replyToMessageId: "",
      replyToSenderName: "",
      replyToSnippet: "",
      deliveredTo: [ALICE],
      readBy: [ALICE],
      deleted: false,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("groups").doc(groupId).set({
      name: "SecOps",
      description: "Test delete",
      avatarData: "",
      ownerId: ALICE,
      adminUids: [ALICE],
      memberUids: [ALICE, BOB],
      conversationId: groupId,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("conversations").doc(groupId).set({
      type: "GROUP",
      participantUids: [ALICE, BOB],
      title: "SecOps",
      description: "Test delete",
      avatarData: "",
      ownerId: ALICE,
      adminUids: [ALICE],
      lastMessageText: "Created",
      lastMessageType: "SYSTEM",
      lastMessageSenderId: ALICE,
      typingUids: [],
      unreadCounts: { [ALICE]: 0, [BOB]: 0 },
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("conversations").doc(groupId).collection("messages").doc("gm1").set({
      conversationId: groupId,
      senderId: BOB,
      senderName: "Bob",
      senderAvatarData: "",
      participantUids: [ALICE, BOB],
      type: "TEXT",
      text: "Bob message",
      mediaData: "",
      fileName: "",
      fileMimeType: "",
      fileSizeBytes: 0,
      replyToMessageId: "",
      replyToSenderName: "",
      replyToSnippet: "",
      deliveredTo: [BOB],
      readBy: [BOB],
      deleted: false,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("group_members").doc(`${groupId}_${ALICE}`).set({
      groupId,
      userId: ALICE,
      displayName: "Alice",
      username: "alice",
      avatarData: "",
      role: "OWNER",
      memberUids: [ALICE, BOB],
      joinedAt: ts,
      updatedAt: ts,
    });
    await db.collection("group_members").doc(`${groupId}_${BOB}`).set({
      groupId,
      userId: BOB,
      displayName: "Bob",
      username: "bob",
      avatarData: "",
      role: "MEMBER",
      memberUids: [ALICE, BOB],
      joinedAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
  const bobDb = testEnv.authenticatedContext(BOB).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE).firestore();

  // Outsider Charlie cannot delete direct chat, friendship, or group
  await assertFails(charlieDb.collection("conversations").doc(convId).delete());
  await assertFails(charlieDb.collection("friendships").doc(friendshipId).delete());
  await assertFails(charlieDb.collection("groups").doc(groupId).delete());

  // Non-owner participant Bob CAN delete direct messages + direct chat
  await assertSucceeds(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").delete());
  await assertSucceeds(bobDb.collection("conversations").doc(convId).delete());

  // Either friend CAN remove contact
  await assertSucceeds(bobDb.collection("friendships").doc(friendshipId).delete());

  // Non-owner Bob CANNOT delete the group
  await assertFails(bobDb.collection("groups").doc(groupId).delete());
  await assertFails(bobDb.collection("conversations").doc(groupId).delete());

  // Owner Alice CAN delete group messages, member docs, group conversation, and group
  await assertSucceeds(aliceDb.collection("conversations").doc(groupId).collection("messages").doc("gm1").delete());
  await assertSucceeds(aliceDb.collection("group_members").doc(`${groupId}_${BOB}`).delete());
  await assertSucceeds(aliceDb.collection("group_members").doc(`${groupId}_${ALICE}`).delete());
  await assertSucceeds(aliceDb.collection("conversations").doc(groupId).delete());
  await assertSucceeds(aliceDb.collection("groups").doc(groupId).delete());
});



