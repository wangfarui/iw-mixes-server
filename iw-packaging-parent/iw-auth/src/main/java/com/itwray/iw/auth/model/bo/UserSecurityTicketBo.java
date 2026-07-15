package com.itwray.iw.auth.model.bo;

import lombok.Data;

/**
 * 敏感操作身份验证通过后的短期票据。
 */
@Data
public class UserSecurityTicketBo {

    private Integer userId;

    private String token;

    private Integer operation;
}
