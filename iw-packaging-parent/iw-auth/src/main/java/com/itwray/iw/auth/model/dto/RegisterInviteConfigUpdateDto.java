package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新用户注册邀请码配置更新 DTO
 *
 * @author wray
 * @since 2026/7/2
 */
@Data
@Schema(name = "新用户注册邀请码配置更新DTO")
public class RegisterInviteConfigUpdateDto {

    @Schema(title = "是否开启邀请码注册")
    @NotNull(message = "是否开启邀请码注册不能为空")
    private Boolean enabled;
}
