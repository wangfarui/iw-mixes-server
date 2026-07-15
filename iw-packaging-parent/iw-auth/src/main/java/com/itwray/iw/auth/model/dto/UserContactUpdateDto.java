package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
@Schema(name = "用户联系方式绑定或换绑DTO")
public class UserContactUpdateDto {

    @NotBlank(message = "安全票据不能为空")
    private String securityTicket;

    @NotNull(message = "联系方式类型不能为空")
    private Integer contactType;

    @NotBlank(message = "联系方式不能为空")
    private String contact;

    @NotBlank(message = "验证码不能为空")
    @Length(min = 6, max = 6, message = "验证码固定为6位数字")
    private String verificationCode;
}
