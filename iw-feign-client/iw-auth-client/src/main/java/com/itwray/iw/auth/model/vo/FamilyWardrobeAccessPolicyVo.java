package com.itwray.iw.auth.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 衣橱读取和归属分配所需的当前家庭访问上下文。
 */
@Data
public class FamilyWardrobeAccessPolicyVo {

    private Integer currentGroupId;

    /** 当前用户在家庭中的角色编码；个人模式为 null。 */
    private Integer currentUserRole;

    /** 当前家庭内状态正常的成员用户 ID，个人模式仅包含当前用户。 */
    private List<Integer> memberUserIds;
}
