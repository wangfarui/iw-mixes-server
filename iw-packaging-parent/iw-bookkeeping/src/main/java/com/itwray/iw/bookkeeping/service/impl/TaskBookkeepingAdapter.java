package com.itwray.iw.bookkeeping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.itwray.iw.auth.model.vo.FamilySharedSavePolicyVo;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.bookkeeping.dao.BookkeepingRecordsDao;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingRecordAddDto;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.bookkeeping.service.BookkeepingRecordsService;
import com.itwray.iw.points.bookkeeping.*;
import com.itwray.iw.web.dao.BaseDictDao;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.model.enums.DictTypeEnum;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** The points module owns task identity; this adapter owns accounting rules. */
@Component
public class TaskBookkeepingAdapter implements TaskBookkeepingGateway {
    private final BookkeepingRecordsService recordsService;
    private final BookkeepingRecordsDao recordsDao;
    private final BaseDictDao dictDao;
    private final AuthFamilyGroupService familyService;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess dictionaries;
    @org.springframework.beans.factory.annotation.Autowired private com.itwray.iw.bookkeeping.support.BookkeepingDictionarySnapshots snapshots;

    public TaskBookkeepingAdapter(BookkeepingRecordsService recordsService, BookkeepingRecordsDao recordsDao,
                                  BaseDictDao dictDao, AuthFamilyGroupService familyService, JdbcTemplate jdbc) {
        this.recordsService = recordsService;
        this.recordsDao = recordsDao;
        this.dictDao = dictDao;
        this.familyService = familyService;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public TaskBookkeepingTemplateDto validateAndBind(TaskBookkeepingTemplateDto input) {
        TaskBookkeepingTemplateDto template = validateFields(input);
        FamilySharedSavePolicyVo policy = familyService.querySharedSavePolicy(UserUtils.getUserId());
        int shared = Objects.equals(policy.getForceShared(), 1) ? 1 : template.getShared();
        Integer groupId = policy.getCurrentGroupId();
        if (shared == 1 && (groupId == null || groupId <= 0)) {
            throw new BusinessException("家庭共享配置已变化，请重新确认");
        }
        template.setShared(shared);
        template.setFamilyGroupId(shared == 1 ? groupId : 0);
        template.setSchemaVersion(2);
        return template;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskBookkeepingRecordVo create(TaskBookkeepingTemplateDto snapshot, LocalDateTime completedAt) {
        if (!Objects.equals(snapshot.getSchemaVersion(), 2)) {
            throw new BusinessException("请重新保存记账模板：旧模板尚未升级");
        }
        TaskBookkeepingTemplateDto resolved=BeanUtil.copyProperties(snapshot,TaskBookkeepingTemplateDto.class);
        if(resolved.getRecordTypeId()!=null)resolved.setRecordTypeId(dictionaries.resolveSuccessor(UserUtils.getUserId(),4002,resolved.getRecordTypeId()).getId());
        int tagType=Objects.equals(resolved.getRecordCategory(),1)?4001:4011;
        java.util.List<Integer> resolvedTags=new java.util.ArrayList<>();
        for(Integer id:Objects.requireNonNullElse(resolved.getRecordTags(),List.<Integer>of()))resolvedTags.add(dictionaries.resolveSuccessor(UserUtils.getUserId(),tagType,id).getId());
        resolved.setRecordTags(resolvedTags);resolved.setRecordType(null);
        TaskBookkeepingTemplateDto template = validateFields(resolved);
        FamilySharedSavePolicyVo policy = familyService.querySharedSavePolicy(UserUtils.getUserId());
        if (template.getShared() == 1 && (!Objects.equals(snapshot.getFamilyGroupId(), policy.getCurrentGroupId())
                || snapshot.getFamilyGroupId() == null || snapshot.getFamilyGroupId() <= 0)
                || template.getShared() == 0 && Objects.equals(policy.getForceShared(), 1)) {
            throw new BusinessException("家庭共享配置已变化，请进入任务详情重新确认本次记账模板");
        }
        BookkeepingRecordAddDto dto = BeanUtil.copyProperties(template, BookkeepingRecordAddDto.class, "recordCategory");
        dto.setRecordCategory(template.getRecordCategory() == 1 ? RecordCategoryEnum.CONSUME : RecordCategoryEnum.INCOME);
        dto.setRecordDate(completedAt.toLocalDate());
        Integer id = recordsService.addFromTask(dto, completedAt, template.getShared() == 1 ? snapshot.getFamilyGroupId() : 0);
        return queryRecords(List.of(id)).get(id);
    }

    private TaskBookkeepingTemplateDto validateFields(TaskBookkeepingTemplateDto input) {
        if (input == null) throw new BusinessException("请设置完整记账模板");
        TaskBookkeepingTemplateDto template = BeanUtil.copyProperties(input, TaskBookkeepingTemplateDto.class);
        if (!Objects.equals(template.getRecordCategory(), 1) && !Objects.equals(template.getRecordCategory(), 2)) {
            throw new BusinessException("记账类型无效");
        }
        BigDecimal amount = template.getAmount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("999999.99")) > 0) {
            throw new BusinessException("金额须大于零，最多两位小数且不超过999999.99");
        }
        if(template.getRecordType()!=null)throw new BusinessException("请更新客户端后重新保存记账模板");
        checkLength(template.getRecordSource(), 64, "收支项目");
        checkLength(template.getRemark(), 255, "备注");
        checkLength(template.getRecordIcon(), 255, "图标");
        template.setRecordSource(Objects.requireNonNullElse(template.getRecordSource(), ""));
        template.setRemark(Objects.requireNonNullElse(template.getRemark(), ""));
        template.setRecordIcon(Objects.requireNonNullElse(template.getRecordIcon(), ""));
        template.setShared(flag(template.getShared(), 0));
        template.setIsStatistics(flag(template.getIsStatistics(), 1));
        template.setIsExcitationRecord(template.getRecordCategory() == 1 ? 0 : flag(template.getIsExcitationRecord(), 0));
        List<Integer> tags = template.getRecordTags() == null ? List.of() : template.getRecordTags();
        int tagType=template.getRecordCategory()==1?4001:4011;
        if(tags.size()>100)throw new BusinessException("标签数量过多");
        var selected=snapshots.lock(template.getRecordTypeId(),tags,tagType);
        template.setRecordTypeName(template.getRecordTypeId()==null?null:selected.get(template.getRecordTypeId()).getDictName());
        template.setRecordTags(tags.stream().distinct().toList());
        template.setRecordTagSnapshots(template.getRecordTags().stream().map(id->new com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot(id,tagType,selected.get(id).getDictName())).toList());
        return template;
    }

    private int flag(Integer value, int fallback) {
        if (value == null) return fallback;
        if (value != 0 && value != 1) throw new BusinessException("记账开关值无效");
        return value;
    }

    private void checkLength(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) throw new BusinessException(field + "过长");
    }

    @Override
    public Map<Integer, TaskBookkeepingRecordVo> queryRecords(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        Map<Integer, TaskBookkeepingRecordVo> result = new HashMap<>();
        for (Integer id : ids) {
            TaskBookkeepingRecordVo vo = new TaskBookkeepingRecordVo();
            vo.setId(id);
            vo.setAvailability("DELETED");
            result.put(id, vo);
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("select id,user_id,deleted from bookkeeping_records where id in (" + placeholders + ")", rs -> {
            TaskBookkeepingRecordVo vo = result.get(rs.getInt("id"));
            if (!rs.getBoolean("deleted") && rs.getInt("user_id") != UserUtils.getUserId()) {
                vo.setAvailability("INACCESSIBLE");
            }
        }, ids.toArray());
        // These are task-owner records. Explicit ownership prevents shared query settings widening access.
        for (BookkeepingRecordsEntity entity : recordsDao.lambdaQuery().in(BookkeepingRecordsEntity::getId, ids)
                .eq(BookkeepingRecordsEntity::getUserId, UserUtils.getUserId()).list()) {
            TaskBookkeepingRecordVo vo = result.get(entity.getId());
            vo.setAvailability("AVAILABLE");
            vo.setAmount(entity.getAmount());
            vo.setRecordDate(entity.getRecordDate());
        }
        return result;
    }
}
