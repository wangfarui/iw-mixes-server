package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "用户账号注销DTO")
public class UserAccountDeletionDto {

    @NotBlank(message = "安全票据不能为空")
    private String securityTicket;
}
