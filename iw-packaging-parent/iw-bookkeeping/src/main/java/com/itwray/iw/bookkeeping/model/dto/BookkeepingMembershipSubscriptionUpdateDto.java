package com.itwray.iw.bookkeeping.model.dto;

import com.itwray.iw.web.model.dto.UpdateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会员订阅记录表 更新DTO
 *
 * @author wray
 * @since 2025/11/5
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "会员订阅记录表 更新DTO")
public class BookkeepingMembershipSubscriptionUpdateDto extends BookkeepingMembershipSubscriptionFields implements UpdateDto {

    @NotNull(message = "id不能为空")
    @Schema(title = "id")
    private Integer id;

    @Valid
    @lombok.Setter(lombok.AccessLevel.NONE)
    private TypeChange membershipTypeChange;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean membershipTypeChangePresent;

    public void setMembershipTypeChange(TypeChange value) {
        membershipTypeChangePresent=true;
        membershipTypeChange=value;
    }

    @Data
    public static class TypeChange {
        @NotNull(message = "请选择会员类型")
        @Positive(message = "请选择有效会员类型")
        private Integer sourceId;
    }
}
