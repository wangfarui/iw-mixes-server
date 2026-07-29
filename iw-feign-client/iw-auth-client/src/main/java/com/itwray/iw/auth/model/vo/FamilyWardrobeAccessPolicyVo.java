package com.itwray.iw.auth.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 衣橱读取和归属分配所需的当前家庭访问上下文。
 */
@Data
public class FamilyWardrobeAccessPolicyVo {

    private Integer currentGroupId;

    /** 儿童只能查看和维护自己的衣物。 */
    private boolean child;

    /** 群主和家长可维护家庭内其他成员的衣物。 */
    private boolean canManageFamilyWardrobe;

    /** 当前成员已保存的“仅看自己”查询偏好。 */
    private boolean queryOnlyMyself;

    /** 当前家庭内状态正常的成员用户 ID，个人模式仅包含当前用户。 */
    private List<Integer> memberUserIds;

    /** 当前家庭有效成员的展示信息。 */
    private List<FamilyWardrobeMemberVo> members;
}
