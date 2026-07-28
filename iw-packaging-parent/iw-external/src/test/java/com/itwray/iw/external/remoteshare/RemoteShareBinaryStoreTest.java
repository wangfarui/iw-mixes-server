package com.itwray.iw.external.remoteshare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteShareBinaryStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deliversCompletedCiphertextOnlyToTheOtherDeviceAndDeletesItAfterClaim() throws Exception {
        RemoteShareSessionService sessions = new RemoteShareSessionService(Clock.systemUTC(), 1800);
        RemoteShareSessionService.JoinedDevice sender = sessions.create("binary-room", "token");
        RemoteShareSessionService.JoinedDevice receiver = sessions.join("binary-room", "token");
        RemoteShareBinaryStore store = new RemoteShareBinaryStore(temporaryDirectory, sessions, 10 * 1024 * 1024L, 30 * 1024 * 1024L, 1024L * 1024 * 1024);

        store.begin("binary-room", sender.capability(), "item-1", 6, 2, "encrypted-manifest");
        store.appendChunk("binary-room", sender.capability(), "item-1", 0, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
        store.appendChunk("binary-room", sender.capability(), "item-1", 1, new ByteArrayInputStream(new byte[]{4, 5, 6}), 3);
        store.complete("binary-room", sender.capability(), "item-1");

        assertEquals(List.of("item-1"), store.pendingFor("binary-room", receiver.capability()).stream().map(RemoteShareBinaryStore.PendingBinary::itemId).toList());
        assertThrows(RemoteShareBinaryStore.NotReadyException.class, () -> store.pendingFor("binary-room", sender.capability()));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), store.claim("binary-room", receiver.capability(), "item-1").stream().map(Byte::intValue).toList());
        assertEquals(0, store.pendingFor("binary-room", receiver.capability()).size());
    }

    @Test
    void rejectsItemsBeyondPerFileLimitBeforeWriting() {
        RemoteShareSessionService sessions = new RemoteShareSessionService(Clock.systemUTC(), 1800);
        RemoteShareSessionService.JoinedDevice sender = sessions.create("limit-room", "token");
        RemoteShareBinaryStore store = new RemoteShareBinaryStore(temporaryDirectory, sessions, 10, 30, 100);

        assertThrows(RemoteShareBinaryStore.ItemLimitExceededException.class,
                () -> store.begin("limit-room", sender.capability(), "too-large", 11, 1, "manifest"));
    }
}
