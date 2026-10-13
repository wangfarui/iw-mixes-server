package com.itwray.iw.points.model.vo.task;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class TaskStatusResultVo {
    private Integer id;
    private Integer taskStatus;
    private String bookkeepingAction;
    private Integer bookkeepingRecordId;
    private BigDecimal recordedAmount;
    private LocalDate recordedDate;
}
