package com.itwray.iw.external.remoteshare;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded encrypted binary fallback. It deliberately accepts opaque bytes only and uses streaming
 * file I/O, so a 10 MB item never becomes a Java heap object.
 */
@Service
public class RemoteShareBinaryStore {

    private final Path root;
    private final RemoteShareSessionService sessions;
    private final long itemLimit;
    private final long roomLimit;
    private final long totalLimit;
    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong reservedBytes = new AtomicLong();

    public RemoteShareBinaryStore(RemoteShareSessionService sessions) {
        this(Path.of(System.getProperty("java.io.tmpdir"), "iw-remote-share"), sessions,
                10L * 1024 * 1024, 30L * 1024 * 1024, 1024L * 1024 * 1024);
    }

    RemoteShareBinaryStore(Path root, RemoteShareSessionService sessions, long itemLimit, long roomLimit, long totalLimit) {
        this.root = root;
        this.sessions = sessions;
        this.itemLimit = itemLimit;
        this.roomLimit = roomLimit;
        this.totalLimit = totalLimit;
    }

    public synchronized void begin(String roomId, String capability, String itemId, long totalBytes, int chunks, String encryptedManifest) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        if (state.slot() == null) throw new ForbiddenException();
        if (!itemId.matches("[A-Za-z0-9_-]{1,80}") || chunks < 1 || chunks > 128 || totalBytes < 1) throw new IllegalArgumentException("Invalid item");
        if (totalBytes > itemLimit) throw new ItemLimitExceededException();
        if (items.containsKey(itemId)) throw new IllegalStateException("Item already exists");
        long roomReserved = items.values().stream().filter(item -> item.roomId.equals(roomId)).mapToLong(item -> item.totalBytes).sum();
        if (roomReserved + totalBytes > roomLimit) throw new RoomLimitExceededException();
        if (items.values().stream().filter(item -> item.roomId.equals(roomId) && !item.completed).count() >= 1
                || items.values().stream().filter(item -> !item.completed).count() >= 4) throw new UploadBusyException();
        if (reservedBytes.get() + totalBytes > totalLimit) throw new TotalLimitExceededException();
        try {
            Files.createDirectories(root.resolve(itemId));
        } catch (IOException exception) {
            throw new StorageUnavailableException(exception);
        }
        reservedBytes.addAndGet(totalBytes);
        items.put(itemId, new Item(roomId, state.slot(), itemId, totalBytes, chunks, encryptedManifest, state.expiresAt(), root.resolve(itemId)));
    }

    public synchronized void appendChunk(String roomId, String capability, String itemId, int index, InputStream input, long declaredLength) {
        Item item = senderItem(roomId, capability, itemId);
        if (item.completed || index < 0 || index >= item.chunks || declaredLength < 0 || declaredLength > 1024 * 1024 + 64) throw new NotReadyException();
        Path chunk = item.directory.resolve(String.format("%04d.part", index));
        long copied;
        try (input) {
            copied = Files.copy(input, chunk, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new StorageUnavailableException(exception);
        }
        if (copied != declaredLength || item.receivedBytes + copied > item.totalBytes) {
            deleteQuietly(chunk);
            throw new IllegalArgumentException("Invalid encrypted chunk length");
        }
        item.receivedBytes += copied;
        item.receivedChunks.add(index);
    }

    public synchronized void complete(String roomId, String capability, String itemId) {
        Item item = senderItem(roomId, capability, itemId);
        if (item.receivedChunks.size() != item.chunks || item.receivedBytes != item.totalBytes) throw new NotReadyException();
        item.completed = true;
    }

    public synchronized List<PendingBinary> pendingFor(String roomId, String capability) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        List<PendingBinary> result = items.values().stream()
                .filter(item -> item.roomId.equals(roomId) && item.completed && item.expiresAt.isAfter(Instant.now()))
                .filter(item -> item.sender != state.slot())
                .map(item -> new PendingBinary(item.itemId, item.totalBytes, item.chunks, item.encryptedManifest))
                .toList();
        if (state.slot() == RemoteShareSessionService.DeviceSlot.A && result.isEmpty()
                && items.values().stream().anyMatch(item -> item.roomId.equals(roomId) && item.sender == state.slot())) {
            throw new NotReadyException();
        }
        return result;
    }

    public synchronized List<Byte> claim(String roomId, String capability, String itemId) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        Item item = items.get(itemId);
        if (item == null || !item.roomId.equals(roomId) || !item.completed || item.sender == state.slot()) throw new NotReadyException();
        List<Byte> content = new ArrayList<>();
        try {
            for (int index = 0; index < item.chunks; index++) {
                byte[] bytes = Files.readAllBytes(item.directory.resolve(String.format("%04d.part", index)));
                for (byte value : bytes) content.add(value);
            }
        } catch (IOException exception) {
            throw new StorageUnavailableException(exception);
        }
        remove(item);
        return content;
    }

    public synchronized Path receiverChunk(String roomId, String capability, String itemId, int index) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        Item item = items.get(itemId);
        if (item == null || !item.roomId.equals(roomId) || !item.completed || item.sender == state.slot() || index < 0 || index >= item.chunks) throw new NotReadyException();
        return item.directory.resolve(String.format("%04d.part", index));
    }

    public synchronized void receipt(String roomId, String capability, String itemId) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        Item item = items.get(itemId);
        if (item == null || !item.roomId.equals(roomId) || item.sender == state.slot()) throw new NotReadyException();
        remove(item);
    }

    public synchronized void clearRoom(String roomId) {
        items.values().stream().filter(item -> item.roomId.equals(roomId)).toList().forEach(this::remove);
    }

    private Item senderItem(String roomId, String capability, String itemId) {
        RemoteShareSessionService.SessionState state = sessions.state(roomId, capability);
        Item item = items.get(itemId);
        if (item == null || !item.roomId.equals(roomId) || item.sender != state.slot()) throw new ForbiddenException();
        return item;
    }

    private void remove(Item item) {
        if (items.remove(item.itemId, item)) {
            reservedBytes.addAndGet(-item.totalBytes);
            try {
                Files.walk(item.directory).sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
            } catch (IOException ignored) { }
        }
    }

    private void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }

    public record PendingBinary(String itemId, long totalBytes, int chunks, String encryptedManifest) { }
    private static final class Item {
        private final String roomId; private final RemoteShareSessionService.DeviceSlot sender; private final String itemId;
        private final long totalBytes; private final int chunks; private final String encryptedManifest; private final Instant expiresAt; private final Path directory;
        private final java.util.Set<Integer> receivedChunks = new java.util.HashSet<>(); private long receivedBytes; private boolean completed;
        private Item(String roomId, RemoteShareSessionService.DeviceSlot sender, String itemId, long totalBytes, int chunks, String encryptedManifest, Instant expiresAt, Path directory) {
            this.roomId = roomId; this.sender = sender; this.itemId = itemId; this.totalBytes = totalBytes; this.chunks = chunks; this.encryptedManifest = encryptedManifest; this.expiresAt = expiresAt; this.directory = directory;
        }
    }
    public static class ItemLimitExceededException extends RuntimeException { }
    public static class RoomLimitExceededException extends RuntimeException { }
    public static class TotalLimitExceededException extends RuntimeException { }
    public static class UploadBusyException extends RuntimeException { }
    public static class NotReadyException extends RuntimeException { }
    public static class ForbiddenException extends RuntimeException { }
    public static class StorageUnavailableException extends RuntimeException { StorageUnavailableException(Throwable cause) { super(cause); } }
}
