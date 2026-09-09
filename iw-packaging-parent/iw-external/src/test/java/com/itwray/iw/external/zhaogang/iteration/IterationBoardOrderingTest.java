package com.itwray.iw.external.zhaogang.iteration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IterationBoardOrderingTest {

    @Test
    void insertsBeforeNextVisibleAnchorAndKeepsHiddenItemsAheadOfIt() {
        assertThat(IterationBoardOrdering.insertionIndex(List.of(10L, 20L, 30L), 10L, 30L)).isEqualTo(2);
    }

    @Test
    void insertsAfterPreviousAnchorWhenDroppingAtLoadedListBottom() {
        assertThat(IterationBoardOrdering.insertionIndex(List.of(10L, 20L, 30L), 20L, null)).isEqualTo(2);
    }

    @Test
    void insertsAtBoardTopWhenNoVisibleAnchorExists() {
        assertThat(IterationBoardOrdering.insertionIndex(List.of(10L, 20L), null, null)).isZero();
    }

    @Test
    void rejectsMissingOrReversedAnchors() {
        assertThatThrownBy(() -> IterationBoardOrdering.insertionIndex(List.of(10L, 20L), 99L, null))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("目标位置已变化");
        assertThatThrownBy(() -> IterationBoardOrdering.insertionIndex(List.of(10L, 20L), 20L, 10L))
                .isInstanceOf(TeamIterationException.class)
                .hasMessageContaining("目标位置已变化");
    }
}
