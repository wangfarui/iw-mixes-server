package com.itwray.iw.external.remoteshare;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-device remote-share session boundary. Content encryption and manifests stay in the browser;
 * this service only keeps opaque room and capability hashes needed to pair the two devices.
 */
@Service
public class RemoteShareSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlSeconds;

    public RemoteShareSessionService() {
        this(Clock.systemUTC(), 30 * 60L);
    }

    RemoteShareSessionService(Clock clock, long ttlSeconds) {
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
    }

    public synchronized JoinedDevice create(String roomId, String accessToken) {
        validateRoomAndToken(roomId, accessToken);
        discardIfExpired(roomId);
        if (sessions.containsKey(roomId)) {
            throw new SessionAlreadyExistsException();
        }
        String capability = newCapability();
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        sessions.put(roomId, new Session(hash(accessToken), hash(capability), null, expiresAt));
        return new JoinedDevice(DeviceSlot.A, capability, expiresAt);
    }

    public synchronized JoinedDevice join(String roomId, String accessToken) {
        Session session = activeSession(roomId);
        if (!session.accessTokenHash.equals(hash(accessToken))) {
            throw new ForbiddenException();
        }
        if (session.secondCapabilityHash != null) {
            throw new SessionFullException();
        }
        String capability = newCapability();
        session.secondCapabilityHash = hash(capability);
        return new JoinedDevice(DeviceSlot.B, capability, session.expiresAt);
    }

    public synchronized SessionState state(String roomId, String capability) {
        Session session = activeSession(roomId);
        DeviceSlot slot = slotFor(session, capability);
        return new SessionState(slot, session.secondCapabilityHash != null, session.expiresAt);
    }

    public synchronized void resetSecondSlot(String roomId, String creatorCapability) {
        Session session = activeSession(roomId);
        if (!session.firstCapabilityHash.equals(hash(creatorCapability))) {
            throw new ForbiddenException();
        }
        session.secondCapabilityHash = null;
    }

    public synchronized void close(String roomId, String capability) {
        Session session = activeSession(roomId);
        slotFor(session, capability);
        sessions.remove(roomId);
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanupExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private Session activeSession(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new SessionNotFoundException();
        }
        Session session = sessions.get(roomId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        if (!session.expiresAt.isAfter(clock.instant())) {
            sessions.remove(roomId);
            throw new SessionExpiredException();
        }
        return session;
    }

    private void discardIfExpired(String roomId) {
        Session current = sessions.get(roomId);
        if (current != null && !current.expiresAt.isAfter(clock.instant())) {
            sessions.remove(roomId);
        }
    }

    private DeviceSlot slotFor(Session session, String capability) {
        String capabilityHash = hash(capability);
        if (session.firstCapabilityHash.equals(capabilityHash)) {
            return DeviceSlot.A;
        }
        if (session.secondCapabilityHash != null && session.secondCapabilityHash.equals(capabilityHash)) {
            return DeviceSlot.B;
        }
        throw new ForbiddenException();
    }

    private void validateRoomAndToken(String roomId, String accessToken) {
        if (roomId == null || roomId.isBlank() || roomId.length() > 128 || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Invalid remote-share session identifier");
        }
    }

    private String newCapability() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new ForbiddenException();
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum DeviceSlot { A, B }

    public record JoinedDevice(DeviceSlot slot, String capability, Instant expiresAt) { }

    public record SessionState(DeviceSlot slot, boolean paired, Instant expiresAt) { }

    private static final class Session {
        private final String accessTokenHash;
        private final String firstCapabilityHash;
        private String secondCapabilityHash;
        private final Instant expiresAt;

        private Session(String accessTokenHash, String firstCapabilityHash, String secondCapabilityHash, Instant expiresAt) {
            this.accessTokenHash = accessTokenHash;
            this.firstCapabilityHash = firstCapabilityHash;
            this.secondCapabilityHash = secondCapabilityHash;
            this.expiresAt = expiresAt;
        }
    }

    public static class SessionAlreadyExistsException extends RuntimeException { }
    public static class SessionNotFoundException extends RuntimeException { }
    public static class SessionExpiredException extends RuntimeException { }
    public static class SessionFullException extends RuntimeException { }
    public static class ForbiddenException extends RuntimeException { }
}
