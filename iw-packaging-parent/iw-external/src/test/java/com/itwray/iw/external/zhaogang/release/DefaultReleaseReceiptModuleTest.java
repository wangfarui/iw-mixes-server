package com.itwray.iw.external.zhaogang.release;

import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Context;
import com.itwray.iw.external.zhaogang.release.ReleaseReceiptModels.Receipt;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultReleaseReceiptModuleTest {

    private final Context user = new Context(10L, 20L);

    @Test
    void returnsUnreadWhenNoReceiptExists() {
        ReleaseReceiptRepository repository = mock(ReleaseReceiptRepository.class);
        when(repository.findReadAt(10L, 20L, "zg-1")).thenReturn(Optional.empty());

        Receipt receipt = new DefaultReleaseReceiptModule(repository).receipt(user, "zg-1");

        assertThat(receipt.read()).isFalse();
        assertThat(receipt.readAt()).isNull();
    }

    @Test
    void acknowledgesIdempotentlyAndKeepsUserTeamReleaseIsolation() {
        ReleaseReceiptRepository repository = mock(ReleaseReceiptRepository.class);
        LocalDateTime readAt = LocalDateTime.of(2026, 9, 2, 18, 0);
        when(repository.markRead(10L, 20L, "zg-1")).thenReturn(readAt);

        Receipt first = new DefaultReleaseReceiptModule(repository).acknowledge(user, "zg-1");
        Receipt second = new DefaultReleaseReceiptModule(repository).acknowledge(user, "zg-1");

        assertThat(first).isEqualTo(new Receipt(true, readAt));
        assertThat(second).isEqualTo(first);
        verify(repository, org.mockito.Mockito.times(2)).markRead(10L, 20L, "zg-1");
    }

    @Test
    void rejectsInvalidReleaseIdAndIncompleteSession() {
        ReleaseReceiptRepository repository = mock(ReleaseReceiptRepository.class);
        DefaultReleaseReceiptModule module = new DefaultReleaseReceiptModule(repository);

        assertThatThrownBy(() -> module.receipt(user, "bad id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> module.receipt(new Context(0L, 20L), "zg-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> module.receipt(user, "a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesTeamUserAndReleaseAsTheIsolationKey() {
        ReleaseReceiptRepository repository = mock(ReleaseReceiptRepository.class);
        when(repository.findReadAt(11L, 21L, "zg-2")).thenReturn(Optional.empty());
        when(repository.findReadAt(10L, 20L, "zg-1")).thenReturn(Optional.of(LocalDateTime.of(2026, 9, 2, 18, 0)));

        assertThat(new DefaultReleaseReceiptModule(repository).receipt(user, "zg-1").read()).isTrue();
        assertThat(new DefaultReleaseReceiptModule(repository).receipt(user, "zg-2").read()).isFalse();
        new DefaultReleaseReceiptModule(repository).receipt(new Context(11L, 21L), "zg-2");

        verify(repository).findReadAt(10L, 20L, "zg-1");
        verify(repository).findReadAt(10L, 20L, "zg-2");
        verify(repository).findReadAt(11L, 21L, "zg-2");
    }
}
