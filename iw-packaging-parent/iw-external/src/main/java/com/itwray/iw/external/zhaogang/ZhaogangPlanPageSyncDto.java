package com.itwray.iw.external.zhaogang;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ZhaogangPlanPageSyncDto {

    @NotEmpty
    @Size(max = 50)
    private List<@Valid PlanRef> plans;

    private boolean force;

    @Data
    public static class PlanRef {

        @Min(1)
        private long projectId;

        @Min(1)
        private long jobId;
    }
}
