package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "账号敏感操作身份验证DTO")
public class UserSecurityVerifyDto {

    @NotNull(message = "安全操作不能为空")
    private Integer operation;

    @NotNull(message = "验证方式不能为空")
    private Integer method;

    private String verificationCode;

    private String password;
}
