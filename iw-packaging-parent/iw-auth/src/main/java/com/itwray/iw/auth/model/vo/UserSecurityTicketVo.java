package com.itwray.iw.auth.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(name = "账号敏感操作安全票据VO")
public class UserSecurityTicketVo {

    private String securityTicket;

    private Long expiresInSeconds;
}
