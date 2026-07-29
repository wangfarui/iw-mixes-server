package com.itwray.iw.auth.model.vo;

import lombok.Data;

/**
 * 衣橱共享场景所需的最小家庭成员信息。
 */
@Data
public class FamilyWardrobeMemberVo {

    private Integer userId;

    private String name;

    private String avatar;
}
