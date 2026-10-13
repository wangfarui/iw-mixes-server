package com.itwray.iw.points.bookkeeping;

import com.itwray.iw.web.utils.UserUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source lookup and task locking, independent from task completion beans. */
@Component
public class TaskBookkeepingSourceQuery {
    private final JdbcTemplate jdbcTemplate;
    public TaskBookkeepingSourceQuery(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** Serialize bill edits/deletion with completion using task -> bill -> wallet lock order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTaskForRecord(Integer recordId) {
        List<Integer> taskIds = jdbcTemplate.queryForList(
                "select task_id from points_task_bookkeeping where bookkeeping_record_id = ? and user_id = ?",
                Integer.class, recordId, UserUtils.getUserId());
        if (!taskIds.isEmpty()) {
            // A removed task may still own a bill. Its absence must not prevent deleting that bill.
            jdbcTemplate.queryForList("select id from points_task_basics where id = ? and user_id = ? for update",
                    Integer.class, taskIds.get(0), UserUtils.getUserId());
        }
    }

    public TaskBookkeepingSourceVo query(Integer recordId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select b.user_id, b.task_id, b.task_name_snapshot, t.id current_task_id, t.task_name, t.deleted, t.task_status "
                        + "from points_task_bookkeeping b left join points_task_basics t on t.id = b.task_id where b.bookkeeping_record_id = ?", recordId);
        if (rows.isEmpty()) { return null; }
        Map<String, Object> row = rows.get(0);
        TaskBookkeepingSourceVo vo = new TaskBookkeepingSourceVo();
        if (!Objects.equals(((Number) row.get("user_id")).intValue(), UserUtils.getUserId())) {
            vo.setAvailability("INACCESSIBLE");
            return vo;
        }
        Object deleted = row.get("deleted");
        boolean unavailable = row.get("current_task_id") == null || Boolean.TRUE.equals(deleted)
                || deleted instanceof Number && ((Number) deleted).intValue() != 0
                || row.get("task_status") instanceof Number && ((Number) row.get("task_status")).intValue() == 3;
        vo.setAvailability(unavailable ? "DELETED" : "AVAILABLE");
        vo.setTaskName((String) (unavailable ? row.get("task_name_snapshot") : row.get("task_name")));
        if (!unavailable) { vo.setTaskId(((Number) row.get("task_id")).intValue()); }
        return vo;
    }
}
