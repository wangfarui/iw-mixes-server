package com.itwray.iw.auth.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新用户注册邀请码状态 VO
 *
 * @author wray
 * @since 2026/7/2
 */
@Data
@Schema(name = "新用户注册邀请码状态VO")
public class RegisterInviteStatusVo {

    @Schema(title = "是否开启邀请码注册")
    private Boolean enabled;

    @Schema(title = "是否存在邀请码")
    private Boolean hasInvite;

    @Schema(title = "邀请码")
    private String inviteCode;

    @Schema(title = "生成时间")
    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime createTime;

    @Schema(title = "过期时间")
    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime expireTime;
}
