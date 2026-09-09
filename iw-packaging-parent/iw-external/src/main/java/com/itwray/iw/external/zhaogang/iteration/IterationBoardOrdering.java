package com.itwray.iw.external.zhaogang.iteration;

import java.util.List;

final class IterationBoardOrdering {

    private IterationBoardOrdering() {
    }

    static int insertionIndex(List<Long> orderedIds, Long previousIterationId, Long nextIterationId) {
        int previousIndex = indexOf(orderedIds, previousIterationId);
        int nextIndex = indexOf(orderedIds, nextIterationId);
        if (previousIterationId != null && nextIterationId != null && previousIndex >= nextIndex) {
            throw new TeamIterationException("目标位置已变化，请刷新后重试");
        }
        if (nextIterationId != null) return nextIndex;
        if (previousIterationId != null) return previousIndex + 1;
        return 0;
    }

    private static int indexOf(List<Long> orderedIds, Long iterationId) {
        if (iterationId == null) return -1;
        int index = orderedIds.indexOf(iterationId);
        if (index < 0) throw new TeamIterationException("目标位置已变化，请刷新后重试");
        return index;
    }
}
