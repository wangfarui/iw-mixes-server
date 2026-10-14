package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.auth.model.enums.FamilyMemberRoleEnum;
import com.itwray.iw.auth.model.enums.ShareStateEnum;
import com.itwray.iw.auth.model.vo.FamilyMemberVo;
import com.itwray.iw.auth.model.vo.FamilySharedSavePolicyVo;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

@Component
public class BookkeepingPayerPolicy {
    private final AuthFamilyGroupService families;

    public BookkeepingPayerPolicy(AuthFamilyGroupService families) { this.families = families; }

    public void apply(BookkeepingRecordsEntity record, Integer requestedPayer, BookkeepingRecordsEntity before,
                      FamilySharedSavePolicyVo policy, boolean taskRecord) {
        Integer recorder = before == null ? UserUtils.getUserId() : before.getUserId();
        record.setUserId(recorder);
        if (before != null && ShareStateEnum.LEFT_GROUP.equals(before.getShareState())) {
            record.setShareState(ShareStateEnum.LEFT_GROUP);
        }
        if (!RecordCategoryEnum.CONSUME.equals(record.getRecordCategory())) {
            record.setExpenseUserId(null);
            return;
        }
        Integer original = before == null || before.getExpenseUserId() == null ? recorder : before.getExpenseUserId();
        Integer payer = requestedPayer == null ? original : requestedPayer;
        boolean changed = !Objects.equals(original, payer);
        if (payer == null || payer <= 0) throw new BusinessException("支出人无效");
        if (taskRecord && !Objects.equals(payer, original)) throw new BusinessException("任务账单不支持更换支出人");
        if (before != null && ShareStateEnum.LEFT_GROUP.equals(before.getShareState())) {
            if (changed) throw new BusinessException("已离组账单不支持更换支出人");
            record.setExpenseUserId(original);
            record.setShareState(ShareStateEnum.LEFT_GROUP);
            return;
        }
        if (!Objects.equals(payer, recorder)) {
            Integer groupId = policy.getCurrentGroupId();
            // Explicitly delegating a former personal payment may attach it to the current family.
            if (changed && Objects.equals(original, recorder) && groupId != null && groupId > 0) {
                record.setGroupId(groupId);
            }
            if (groupId == null || groupId <= 0 || !Objects.equals(record.getGroupId(), groupId)) {
                throw new BusinessException("只能为当前家庭有效成员代记账");
            }
            List<FamilyMemberVo> members = families.memberList(groupId);
            FamilyMemberVo current = members.stream().filter(m -> Objects.equals(m.getUserId(), recorder)).findFirst().orElse(null);
            if (current == null || FamilyMemberRoleEnum.CHILD.equals(current.getRole())) {
                throw new BusinessException("当前成员不能为家人代记账");
            }
            if (members.stream().noneMatch(m -> Objects.equals(m.getUserId(), payer))) {
                throw new BusinessException("支出人已不在当前家庭，请重新选择");
            }
            record.setShareState(ShareStateEnum.SHARED);
        }
        record.setExpenseUserId(payer);
    }
}
