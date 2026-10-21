package com.itwray.iw.bookkeeping.model.dto;

import com.itwray.iw.web.model.dto.AddDto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BookkeepingMembershipSubscriptionAddDto extends BookkeepingMembershipSubscriptionFields implements AddDto {
    @NotNull(message = "请更新客户端，从记账设置选择会员类型")
    @Positive(message = "请选择有效会员类型")
    private Integer membershipTypeId;
}
