package com.itwray.iw.points.bookkeeping;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
/** Implemented by iw-bookkeeping; all writes join the caller's database transaction. */
public interface TaskBookkeepingGateway {
    TaskBookkeepingTemplateDto validateAndBind(TaskBookkeepingTemplateDto template);
    TaskBookkeepingRecordVo create(TaskBookkeepingTemplateDto snapshot, LocalDateTime completedAt);
    Map<Integer, TaskBookkeepingRecordVo> queryRecords(Collection<Integer> recordIds);
}
