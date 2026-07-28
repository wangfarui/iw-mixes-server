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
    private final Map<String, String> roomByJoinCode = new ConcurrentHashMap<>();
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
        return create(roomId, accessToken, null);
    }

    public synchronized JoinedDevice create(String roomId, String accessToken, String sessionSecret) {
        validateRoomAndToken(roomId, accessToken);
        if (sessionSecret != null && sessionSecret.isBlank()) throw new IllegalArgumentException("Invalid remote-share session secret");
        discardIfExpired(roomId);
        if (sessions.containsKey(roomId)) {
            throw new SessionAlreadyExistsException();
        }
        String capability = newCapability();
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        String joinCode = sessionSecret == null ? null : newJoinCode();
        Session session = new Session(hash(accessToken), hash(capability), null, expiresAt, joinCode, sessionSecret);
        sessions.put(roomId, session);
        if (joinCode != null) roomByJoinCode.put(joinCode, roomId);
        return new JoinedDevice(DeviceSlot.A, capability, expiresAt, joinCode);
    }

    public synchronized JoinedDevice join(String roomId, String accessToken) {
        Session session = activeSession(roomId);
        if (!session.accessTokenHash.equals(hash(accessToken))) {
            throw new ForbiddenException();
        }
        return join(session);
    }

    public synchronized CodeJoinedDevice joinByCode(String joinCode) {
        if (joinCode == null || !joinCode.matches("\\d{4}")) throw new SessionNotFoundException();
        String roomId = roomByJoinCode.get(joinCode);
        if (roomId == null) throw new SessionNotFoundException();
        Session session = activeSession(roomId);
        if (!joinCode.equals(session.joinCode) || session.sessionSecret == null) throw new SessionNotFoundException();
        JoinedDevice device = join(session);
        return new CodeJoinedDevice(device.slot(), device.capability(), device.expiresAt(), session.sessionSecret, joinCode);
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
        removeSession(roomId, session);
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanupExpired() {
        Instant now = clock.instant();
        sessions.entrySet().stream().filter(entry -> !entry.getValue().expiresAt.isAfter(now))
                .toList().forEach(entry -> removeSession(entry.getKey(), entry.getValue()));
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
            removeSession(roomId, session);
            throw new SessionExpiredException();
        }
        return session;
    }

    private void discardIfExpired(String roomId) {
        Session current = sessions.get(roomId);
        if (current != null && !current.expiresAt.isAfter(clock.instant())) {
            removeSession(roomId, current);
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

    private JoinedDevice join(Session session) {
        if (session.secondCapabilityHash != null) throw new SessionFullException();
        String capability = newCapability();
        session.secondCapabilityHash = hash(capability);
        return new JoinedDevice(DeviceSlot.B, capability, session.expiresAt, session.joinCode);
    }

    private String newJoinCode() {
        for (int attempts = 0; attempts < 100; attempts++) {
            String code = String.format("%04d", RANDOM.nextInt(10_000));
            if (!roomByJoinCode.containsKey(code)) return code;
        }
        throw new IllegalStateException("Unable to allocate remote-share join code");
    }

    private void removeSession(String roomId, Session session) {
        if (sessions.remove(roomId, session) && session.joinCode != null) {
            roomByJoinCode.remove(session.joinCode, roomId);
        }
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

    public record JoinedDevice(DeviceSlot slot, String capability, Instant expiresAt, String joinCode) { }

    public record CodeJoinedDevice(DeviceSlot slot, String capability, Instant expiresAt,
                                   String sessionSecret, String joinCode) { }

    public record SessionState(DeviceSlot slot, boolean paired, Instant expiresAt) { }

    private static final class Session {
        private final String accessTokenHash;
        private final String firstCapabilityHash;
        private String secondCapabilityHash;
        private final Instant expiresAt;
        private final String joinCode;
        private final String sessionSecret;

        private Session(String accessTokenHash, String firstCapabilityHash, String secondCapabilityHash, Instant expiresAt,
                        String joinCode, String sessionSecret) {
            this.accessTokenHash = accessTokenHash;
            this.firstCapabilityHash = firstCapabilityHash;
            this.secondCapabilityHash = secondCapabilityHash;
            this.expiresAt = expiresAt;
            this.joinCode = joinCode;
            this.sessionSecret = sessionSecret;
        }
    }

    public static class SessionAlreadyExistsException extends RuntimeException { }
    public static class SessionNotFoundException extends RuntimeException { }
    public static class SessionExpiredException extends RuntimeException { }
    public static class SessionFullException extends RuntimeException { }
    public static class ForbiddenException extends RuntimeException { }
}
