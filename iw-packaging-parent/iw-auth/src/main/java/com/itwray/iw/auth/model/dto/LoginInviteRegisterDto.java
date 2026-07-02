package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 验证码登录邀请码注册 DTO
 *
 * @author wray
 * @since 2026/7/2
 */
@Data
@Schema(name = "验证码登录邀请码注册DTO")
public class LoginInviteRegisterDto {

    @Schema(title = "注册票据")
    @NotBlank(message = "注册票据不能为空")
    private String registerTicket;

    @Schema(title = "邀请码")
    @NotBlank(message = "邀请码不能为空")
    @Length(min = 6, max = 6, message = "邀请码固定为6位")
    private String inviteCode;
}
