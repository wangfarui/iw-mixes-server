package com.itwray.iw.external.remoteshare;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteShareSessionServiceTest {

    @Test
    void pairsExactlyTwoDevicesAndRejectsTheThird() {
        RemoteShareSessionService service = new RemoteShareSessionService(
                Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC), 30 * 60);

        RemoteShareSessionService.JoinedDevice creator = service.create("room-1", "access-token");
        RemoteShareSessionService.JoinedDevice receiver = service.join("room-1", "access-token");

        assertEquals(RemoteShareSessionService.DeviceSlot.A, creator.slot());
        assertEquals(RemoteShareSessionService.DeviceSlot.B, receiver.slot());
        assertNotEquals(creator.capability(), receiver.capability());
        assertThrows(RemoteShareSessionService.SessionFullException.class,
                () -> service.join("room-1", "access-token"));
    }

    @Test
    void onlyCreatorCanResetSecondDeviceSlot() {
        RemoteShareSessionService service = new RemoteShareSessionService(Clock.systemUTC(), 30 * 60);
        RemoteShareSessionService.JoinedDevice creator = service.create("room-2", "access-token");
        RemoteShareSessionService.JoinedDevice receiver = service.join("room-2", "access-token");

        assertThrows(RemoteShareSessionService.ForbiddenException.class,
                () -> service.resetSecondSlot("room-2", receiver.capability()));

        service.resetSecondSlot("room-2", creator.capability());
        RemoteShareSessionService.JoinedDevice replacement = service.join("room-2", "access-token");

        assertEquals(RemoteShareSessionService.DeviceSlot.B, replacement.slot());
        assertNotEquals(receiver.capability(), replacement.capability());
    }

    @Test
    void rejectsExpiredSessionBeforeJoining() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
        RemoteShareSessionService service = new RemoteShareSessionService(clock, 60);
        service.create("room-3", "access-token");
        clock.advanceSeconds(61);

        assertThrows(RemoteShareSessionService.SessionExpiredException.class,
                () -> service.join("room-3", "access-token"));
    }

    @Test
    void joinsByFourDigitCodeAndReturnsTheTemporarySessionSecret() {
        RemoteShareSessionService service = new RemoteShareSessionService(Clock.systemUTC(), 30 * 60);
        RemoteShareSessionService.JoinedDevice creator = service.create("room-4", "access-token", "browser-session-secret");

        RemoteShareSessionService.CodeJoinedDevice receiver = service.joinByCode(creator.joinCode());

        assertEquals(RemoteShareSessionService.DeviceSlot.B, receiver.slot());
        assertEquals("browser-session-secret", receiver.sessionSecret());
        assertNotEquals(creator.capability(), receiver.capability());
        assertThrows(RemoteShareSessionService.SessionFullException.class,
                () -> service.joinByCode(creator.joinCode()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
