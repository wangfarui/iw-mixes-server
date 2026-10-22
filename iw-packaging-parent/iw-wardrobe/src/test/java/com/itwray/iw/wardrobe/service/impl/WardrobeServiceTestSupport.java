package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.dictionary.WardrobeSnapshotService;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Existing policy/ranking unit tests isolate the new snapshot boundary; MySQL tests verify it separately. */
final class WardrobeServiceTestSupport {
    static void snapshotBoundary(Object target) {
        WardrobeSnapshotService snapshots = mock(WardrobeSnapshotService.class);
        when(snapshots.prepareItem(any(), any(), any(), any(), any())).thenReturn(new WardrobeSnapshotService.Prepared(Map.of()));
        when(snapshots.prepareHead(any(), anyBoolean(), any(), any(), any(), anyBoolean())).thenReturn(new WardrobeSnapshotService.Prepared(Map.of()));
        when(snapshots.currentContext(any())).thenReturn(Map.of("contextToken", "unit-test-boundary"));
        doCallRealMethod().when(snapshots).copyScalar(any(), any());
        when(snapshots.lockItemForWrite(any(), any(), any(), any())).thenAnswer(call -> {
            WardrobeItemEntity row = new WardrobeItemEntity(); row.setId(call.getArgument(3)); row.setUserId(call.getArgument(1)); return row;
        });
        ReflectionTestUtils.setField(target, "snapshots", snapshots);
    }
}
