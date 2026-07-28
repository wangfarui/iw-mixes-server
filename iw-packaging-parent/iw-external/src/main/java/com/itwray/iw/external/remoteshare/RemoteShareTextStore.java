package com.itwray.iw.external.remoteshare;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque fallback text queue. Entries are ciphertext supplied by the browser and are bounded per room.
 * The queue is intentionally short-lived and is cleared with the pairing session.
 */
@Service
public class RemoteShareTextStore {

    private static final int MAX_PENDING_BYTES_PER_ROOM = 256 * 1024;

    private final Map<String, List<PendingText>> messagesByRoom = new ConcurrentHashMap<>();

    public synchronized void enqueue(String roomId, RemoteShareSessionService.DeviceSlot sender,
                                     String ciphertext, Instant expiresAt) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Encrypted text is required");
        }
        int bytes = ciphertext.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PENDING_BYTES_PER_ROOM) {
            throw new TextQuotaExceededException();
        }
        List<PendingText> entries = messagesByRoom.computeIfAbsent(roomId, ignored -> new ArrayList<>());
        int queuedBytes = entries.stream().mapToInt(entry -> entry.ciphertext.getBytes(StandardCharsets.UTF_8).length).sum();
        if (queuedBytes + bytes > MAX_PENDING_BYTES_PER_ROOM) {
            throw new TextQuotaExceededException();
        }
        entries.add(new PendingText(UUID.randomUUID().toString(), sender, ciphertext, expiresAt));
    }

    public synchronized List<PendingText> claimFor(String roomId, RemoteShareSessionService.DeviceSlot receiver, Instant now) {
        List<PendingText> entries = messagesByRoom.get(roomId);
        if (entries == null) {
            return List.of();
        }
        List<PendingText> claimed = entries.stream()
                .filter(entry -> entry.expiresAt.isAfter(now))
                .filter(entry -> entry.sender != receiver)
                .toList();
        entries.removeIf(entry -> !entry.expiresAt.isAfter(now) || entry.sender != receiver);
        if (entries.isEmpty()) {
            messagesByRoom.remove(roomId);
        }
        return claimed;
    }

    public synchronized void clear(String roomId) {
        messagesByRoom.remove(roomId);
    }

    public record PendingText(String id, RemoteShareSessionService.DeviceSlot sender, String ciphertext,
                              Instant expiresAt) { }

    public static class TextQuotaExceededException extends RuntimeException { }
}
