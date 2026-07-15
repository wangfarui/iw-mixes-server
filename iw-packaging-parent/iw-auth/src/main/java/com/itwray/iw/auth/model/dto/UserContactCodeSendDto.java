package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "新联系方式验证码发送DTO")
public class UserContactCodeSendDto {

    @NotBlank(message = "安全票据不能为空")
    private String securityTicket;

    @NotNull(message = "联系方式类型不能为空")
    private Integer contactType;

    @NotBlank(message = "联系方式不能为空")
    private String contact;
}
