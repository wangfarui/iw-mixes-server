package com.itwray.iw.points.bookkeeping;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data
public class TaskBookkeepingRecordVo {
    private Integer id;
    private String availability;
    private BigDecimal amount;
    private LocalDate recordDate;
}
