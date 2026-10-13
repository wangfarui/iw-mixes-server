package com.itwray.iw.points.bookkeeping;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** Structured input; system identity and dates never come from the client. */
@Data
public class TaskBookkeepingTemplateDto {
    private Integer recordCategory;
    private String recordSource;
    private BigDecimal amount;
    private Integer recordType;
    private String remark;
    private List<Integer> recordTags;
    private String recordIcon;
    private Integer isStatistics;
    private Integer isExcitationRecord;
    private Integer shared;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer schemaVersion;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer familyGroupId;
}
