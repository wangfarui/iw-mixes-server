package com.itwray.iw.external.remoteshare;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Opaque fallback text queue. Entries are ciphertext supplied by the browser and are bounded per room.
 * The queue is intentionally short-lived and is cleared with the pairing session.
 */
@Service
public class RemoteShareTextStore {

    private static final int MAX_PENDING_BYTES_PER_ROOM = 256 * 1024;
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local added = tonumber(ARGV[1])
            if current + added > tonumber(ARGV[2]) then return -1 end
            redis.call('RPUSH', KEYS[2], ARGV[3])
            redis.call('SET', KEYS[1], current + added, 'EX', ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return current + added
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RemoteShareTextStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String roomId, RemoteShareSessionService.DeviceSlot sender,
                        String ciphertext, Instant expiresAt) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Encrypted text is required");
        }
        int bytes = ciphertext.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PENDING_BYTES_PER_ROOM) {
            throw new TextQuotaExceededException();
        }
        PendingText entry = new PendingText(UUID.randomUUID().toString(), sender, ciphertext, expiresAt);
        String serialized = serialize(entry);
        long ttl = Math.max(1, java.time.Duration.between(Instant.now(), expiresAt).toSeconds());
        Long result = redis.execute(ENQUEUE_SCRIPT, List.of(bytesKey(roomId), queueKey(roomId)),
                String.valueOf(bytes), String.valueOf(MAX_PENDING_BYTES_PER_ROOM), serialized, String.valueOf(ttl));
        if (result == null || result < 0) throw new TextQuotaExceededException();
    }

    public synchronized List<PendingText> claimFor(String roomId, RemoteShareSessionService.DeviceSlot receiver, Instant now) {
        String key = queueKey(roomId);
        List<String> rawEntries = redis.opsForList().range(key, 0, -1);
        if (rawEntries == null) return List.of();
        List<PendingText> entries = rawEntries.stream().map(this::deserialize).toList();
        List<PendingText> claimed = entries.stream()
                .filter(entry -> entry.expiresAt.isAfter(now))
                .filter(entry -> entry.sender != receiver)
                .toList();
        rawEntries.forEach(raw -> { PendingText entry = deserialize(raw); if (!entry.expiresAt.isAfter(now) || entry.sender != receiver) redis.opsForList().remove(key, 1, raw); });
        int remaining = entries.stream().filter(entry -> entry.expiresAt.isAfter(now) && entry.sender == receiver).mapToInt(entry -> entry.ciphertext.getBytes(StandardCharsets.UTF_8).length).sum();
        redis.opsForValue().set(bytesKey(roomId), String.valueOf(remaining));
        return claimed;
    }

    public synchronized void clear(String roomId) {
        redis.delete(List.of(queueKey(roomId), bytesKey(roomId)));
    }

    private String queueKey(String roomId) { return "external:remote-share:text:" + roomId; }
    private String bytesKey(String roomId) { return queueKey(roomId) + ":bytes"; }
    private String serialize(PendingText entry) { try { return objectMapper.writeValueAsString(entry); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private PendingText deserialize(String value) { try { return objectMapper.readValue(value, PendingText.class); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }

    public record PendingText(String id, RemoteShareSessionService.DeviceSlot sender, String ciphertext,
                              Instant expiresAt) { }

    public static class TextQuotaExceededException extends RuntimeException { }
}
