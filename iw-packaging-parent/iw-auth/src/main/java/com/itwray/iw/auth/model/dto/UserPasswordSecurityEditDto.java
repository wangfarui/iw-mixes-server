package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "安全验证后设置密码DTO")
public class UserPasswordSecurityEditDto {

    @NotBlank(message = "安全票据不能为空")
    private String securityTicket;

    @NotBlank(message = "新密码不能为空")
    @Size(max = 64, message = "密码不能超过64位")
    private String newPassword;
}
