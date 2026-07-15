package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户信息 修改用户名DTO
 *
 * @author wray
 * @since 2025/3/14
 */
@Data
@Schema(name = "用户信息 修改用户名DTO")
public class UserUsernameEditDto {

    @Schema(title = "安全票据")
    @NotBlank(message = "安全票据不能为空")
    private String securityTicket;

    @Schema(title = "用户名")
    @NotBlank(message = "新用户名不能为空")
    @Size(max = 64, message = "用户名不能超过64位")
    private String username;
}
