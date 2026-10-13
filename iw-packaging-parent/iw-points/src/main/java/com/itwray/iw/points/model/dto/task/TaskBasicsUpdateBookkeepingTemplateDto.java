package com.itwray.iw.points.model.dto.task;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingTemplateDto;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class TaskBasicsUpdateBookkeepingTemplateDto {
    @NotNull private Integer id;
    @NotNull private TaskBookkeepingTemplateDto bookkeepingTemplate;
}
