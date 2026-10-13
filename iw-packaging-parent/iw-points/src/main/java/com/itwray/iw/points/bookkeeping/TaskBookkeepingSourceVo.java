package com.itwray.iw.points.bookkeeping;
import lombok.Data;
/** Bill source information after task ownership filtering. */
@Data
public class TaskBookkeepingSourceVo {
    private Integer taskId;
    private String taskName;
    private String availability;
}
