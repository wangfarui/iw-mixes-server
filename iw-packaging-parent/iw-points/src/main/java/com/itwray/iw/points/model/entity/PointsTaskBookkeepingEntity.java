package com.itwray.iw.points.model.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.itwray.iw.web.model.entity.IdEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
/** Durable task snapshot and latest bill association; a deleted bill can be replaced. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("points_task_bookkeeping")
public class PointsTaskBookkeepingEntity extends IdEntity<Integer> {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer userId;
    private Integer taskId;
    private Integer sourcePlanId;
    private LocalDate sourcePlanDate;
    private String taskNameSnapshot;
    private String templateSnapshot;
    private Integer bookkeepingRecordId;
    private LocalDateTime recordedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
