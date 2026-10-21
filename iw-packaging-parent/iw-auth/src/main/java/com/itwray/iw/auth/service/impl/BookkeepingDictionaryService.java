package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.BookkeepingDictionaryMapper;
import com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess;
import com.itwray.iw.web.bookkeeping.DictionaryNames;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned bookkeeping dictionaries. No Redis state participates in authorization. */
@Service
@Transactional(rollbackFor = Exception.class)
public class BookkeepingDictionaryService implements BookkeepingDictionaryAccess {
    private final BookkeepingDictionaryMapper mapper;
    public BookkeepingDictionaryService(BookkeepingDictionaryMapper mapper) { this.mapper = mapper; }
    public enum Type {
        CATEGORY(4002), EXPENSE_TAG(4001), INCOME_TAG(4011), MEMBERSHIP_TYPE(4004);
        public final int code;
        Type(int code) { this.code=code; }
    }
    public enum Scope { PERSONAL, FAMILY }
    public record Item(Integer id, String name, boolean enabled, Integer sort, Integer revision) { }
    public record Management(List<Item> items, boolean canManage, String scopeVersion, Integer familyGroupId) { }
    public record Rename(Integer oldId, Item item) { }
    public record Copied(List<Item> created, List<Integer> skipped) { }
    public record Option(Integer id, String name, Integer sort) { }
    private record Actor(int userId, int groupId, boolean manager) { }

    public Management items(int userId, Type type, Scope scope) {
        checkScope(type,scope); Actor actor=actor(userId,type.code);
        requireScope(actor,scope,false);
        List<BaseDictEntity> rows=list(actor,type.code,scope);
        return new Management(rows.stream().map(this::item).toList(),scope==Scope.PERSONAL||actor.manager,
                version(rows),actor.groupId);
    }
    public Item create(int userId, Type type, Scope scope, String name) {
        checkScope(type,scope); Actor actor=actor(userId,type.code); requireScope(actor,scope,true);
        List<BaseDictEntity> rows=list(actor,type.code,scope);
        String normalized=normalize(name); unique(rows,normalized,null);
        BaseDictEntity row=newItem(actor,scope,type.code,normalized,
                rows.stream().mapToInt(r->r.getSort()==null?0:r.getSort()).max().orElse(0)+1);
        insert(row); return item(row);
    }
    public Rename rename(int userId, int id, String name, Integer revision) {
        Actor actor=actorForId(userId,id); BaseDictEntity old=managed(actor,id,revision);
        String normalized=normalize(name);
        if (old.getDictName().equals(normalized)) return new Rename(id,item(old));
        unique(list(actor,old.getDictType(),scope(old)),normalized,id);
        if(mapper.retire(old)!=1) throw stale();
        BaseDictEntity next=newItem(actor,scope(old),old.getDictType(),normalized,old.getSort());
        next.setPreviousId(old.getId()); next.setDictStatus(old.getDictStatus());
        insert(next); return new Rename(id,item(next));
    }
    public Item status(int userId, int id, boolean enabled, Integer revision) {
        Actor actor=actorForId(userId,id); BaseDictEntity row=managed(actor,id,revision);
        row.setDictStatus(enabled?1:0); update(row); return item(row);
    }
    public void delete(int userId,int id,Integer revision) {
        BaseDictEntity row=managed(actorForId(userId,id),id,revision);
        if(mapper.retire(row)!=1) throw stale();
    }
    public void order(int userId, Type type, Scope scope, List<Integer> ids, String expectedVersion) {
        checkScope(type,scope); Actor actor=actor(userId,type.code); requireScope(actor,scope,true);
        List<BaseDictEntity> rows=list(actor,type.code,scope);
        if(ids==null || expectedVersion==null || !expectedVersion.equals(version(rows))
                || ids.size()!=rows.size() || new HashSet<>(ids).size()!=ids.size()
                || !new HashSet<>(ids).equals(new HashSet<>(rows.stream().map(BaseDictEntity::getId).toList()))) throw stale();
        Map<Integer,Integer> sorts=new HashMap<>();
        for(int i=0;i<ids.size();i++) sorts.put(ids.get(i),i+1);
        rows.stream().sorted(Comparator.comparing(BaseDictEntity::getId)).forEach(row->{
            row.setSort(sorts.get(row.getId())); update(row);
        });
    }
    public Copied copyPersonal(int userId,List<Integer> sourceIds) {
        Actor actor=actor(userId); requireScope(actor,Scope.FAMILY,true);
        List<Integer> ids=ids(sourceIds); List<BaseDictEntity> rows=owned(actor,ids);
        if(rows.size()!=ids.size() || rows.stream().anyMatch(r->!Objects.equals(r.getScopeType(),0)
                || !DictionaryNames.supportsFamily(r.getDictType()) || Boolean.TRUE.equals(r.getDeleted())))
            throw new BusinessException("只能复制本人当前记账选项");
        List<Item> created=new ArrayList<>(); List<Integer> skipped=new ArrayList<>();
        for(BaseDictEntity source:rows) {
            List<BaseDictEntity> targets=list(actor,source.getDictType(),Scope.FAMILY);
            if(targets.stream().anyMatch(t->t.getDictName().equals(source.getDictName()))) { skipped.add(source.getId()); continue; }
            BaseDictEntity target=newItem(actor,Scope.FAMILY,source.getDictType(),source.getDictName(),
                    targets.stream().mapToInt(t->t.getSort()==null?0:t.getSort()).max().orElse(0)+1);
            target.setDictStatus(source.getDictStatus()); insert(target); created.add(item(target));
        }
        return new Copied(created,skipped);
    }
    @Override
    @Transactional(readOnly = true)
    public List<BaseDictEntity> options(Integer userId,Integer dictType) {
        checkType(dictType); Actor actor=personal(dictType)?personalReader(userId):reader(userId);
        Map<String,BaseDictEntity> names=new LinkedHashMap<>();
        list(actor,dictType,Scope.PERSONAL).stream().filter(this::enabled).forEach(r->names.putIfAbsent(r.getDictName(),r));
        if(actor.groupId>0) list(actor,dictType,Scope.FAMILY).stream().filter(this::enabled).forEach(r->names.putIfAbsent(r.getDictName(),r));
        return names.values().stream().sorted(Comparator.comparing(BaseDictEntity::getSort,Comparator.nullsLast(Integer::compareTo))
                .thenComparing(BaseDictEntity::getId)).toList();
    }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BaseDictEntity> lockSelectable(Integer userId,Integer dictType,Collection<Integer> references) {
        checkType(dictType); Actor actor=actor(userId,dictType);
        List<Integer> ids=ids(references); List<BaseDictEntity> rows=owned(actor,ids);
        if(rows.size()!=ids.size() || rows.stream().anyMatch(r->!enabled(r) || !Objects.equals(r.getDictType(),dictType)))
            throw new BusinessException("选项已失效或无权使用，请重新选择");
        return rows;
    }
    @Override
    // Future-configuration readers catch unavailability and return an unavailable item.
    // An uncaught failure still rolls back the enclosing writer transaction.
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
    public BaseDictEntity resolveSuccessor(Integer userId,Integer dictType,Integer sourceId) {
        checkType(dictType); Actor actor=actor(userId,dictType);
        List<BaseDictEntity> initial=owned(actor,ids(Collections.singletonList(sourceId)));
        if(initial.size()!=1 || !Objects.equals(initial.get(0).getDictType(),dictType)) throw unavailable();
        BaseDictEntity row=initial.get(0); Set<Integer> visited=new HashSet<>();
        for(int depth=0;depth<128;depth++) {
            if(!visited.add(row.getId())) throw unavailable();
            if(!Boolean.TRUE.equals(row.getDeleted())) {
                if(!enabled(row)) throw unavailable();
                return row;
            }
            row=mapper.successor(row.getId(),row.getScopeType(),row.getFamilyGroupId(),row.getUserId(),dictType);
            if(row==null) throw unavailable();
        }
        throw unavailable();
    }
    @Override
    @Transactional(readOnly = true)
    public Map<Integer,BaseDictEntity> resolveSuccessors(Integer userId,Collection<Integer> sourceIds) {
        List<Integer> ids=ids(sourceIds);
        if(ids.isEmpty()) return Map.of();
        Actor actor=reader(userId);
        Map<Integer,BaseDictEntity> rows=new HashMap<>();
        mapper.readOwned(ids,actor.userId,actor.groupId).forEach(row->rows.put(row.getId(),row));
        Map<Integer,BaseDictEntity> successors=new HashMap<>();
        Set<Integer> expanded=new HashSet<>();
        List<Integer> frontier=rows.values().stream().filter(row->Boolean.TRUE.equals(row.getDeleted()))
                .map(BaseDictEntity::getId).sorted().toList();
        // One query per rename depth, shared by all actions and repeated source IDs.
        for(int depth=0;depth<127 && !frontier.isEmpty();depth++) {
            expanded.addAll(frontier);
            List<BaseDictEntity> next=mapper.readSuccessors(frontier,actor.userId,actor.groupId);
            for(BaseDictEntity row:next) {
                successors.put(row.getPreviousId(),row);
                rows.put(row.getId(),row);
            }
            frontier=next.stream().filter(row->Boolean.TRUE.equals(row.getDeleted())&&!expanded.contains(row.getId()))
                    .map(BaseDictEntity::getId).distinct().sorted().toList();
        }
        Map<Integer,BaseDictEntity> result=new HashMap<>();
        for(Integer id:ids) {
            BaseDictEntity source=rows.get(id), row=source;
            Set<Integer> visited=new HashSet<>();
            for(int depth=0;depth<128 && row!=null;depth++) {
                if(!visited.add(row.getId()) || !sameOwnerAndType(source,row)) break;
                if(!Boolean.TRUE.equals(row.getDeleted())) {
                    if(enabled(row)) result.put(id,row);
                    break;
                }
                row=successors.get(row.getId());
            }
        }
        return result;
    }

    private boolean sameOwnerAndType(BaseDictEntity source,BaseDictEntity row) {
        return Objects.equals(source.getScopeType(),row.getScopeType())
                && Objects.equals(source.getFamilyGroupId(),row.getFamilyGroupId())
                && Objects.equals(source.getUserId(),row.getUserId())
                && Objects.equals(source.getDictType(),row.getDictType());
    }
    @Override
    public List<BaseDictEntity> sources(Collection<Integer> authorizedSourceIds) {
        List<Integer> ids=ids(authorizedSourceIds);
        return ids.isEmpty()?List.of():mapper.sources(ids);
    }
    /** Caller already holds the newly created group; insert marker and templates atomically. */
    public void initializeFamily(int groupId) {
        if(mapper.lockGroup(groupId)==null) throw new BusinessException("家庭已失效");
        if(mapper.lockState(groupId)!=null) return;
        Actor actor=new Actor(0,groupId,true);
        List<BaseDictEntity> existing=list(actor,4002,Scope.FAMILY);
        // An existing family without a marker is initialized only by the reviewed migration.
        if(!existing.isEmpty()) throw new BusinessException("家庭字典初始化状态缺失，请先完成迁移");
        mapper.insertState(groupId);
        for(BaseDictEntity template:mapper.listScope(0,0,0,4002)) {
            if(enabled(template)) insert(newItem(actor,Scope.FAMILY,4002,normalize(template.getDictName()),template.getSort()));
        }
    }
    private boolean personal(Integer type) { return Objects.equals(type,4004); }
    private void checkScope(Type type,Scope scope) {
        if(type==null || (personal(type.code) && scope!=Scope.PERSONAL))
            throw new BusinessException("会员类型仅支持个人范围");
    }
    private Actor actorForId(Integer userId,int id) { return actor(userId,mapper.typeOf(id)); }
    private Actor actor(Integer userId,Integer type) {
        if(!personal(type)) return actor(userId);
        if(userId==null || userId<=0) throw new BusinessException("请先登录");
        if(mapper.lockUser(userId)==null) throw new BusinessException("用户已失效，请重新登录");
        return new Actor(userId,0,false);
    }
    private Actor personalReader(Integer userId) {
        if(userId==null || userId<=0) throw new BusinessException("请先登录");
        if(mapper.currentGroup(userId)==null) throw new BusinessException("用户已失效，请重新登录");
        return new Actor(userId,0,false);
    }
    private Actor reader(Integer userId) {
        if(userId==null||userId<=0) throw new BusinessException("请先登录");
        BookkeepingDictionaryMapper.Reader reader=mapper.reader(userId);
        if(reader==null) throw new BusinessException("用户已失效，请重新登录");
        int group=reader.groupId();
        if(group>0) {
            if(reader.activeGroupId()==null) throw new BusinessException("家庭状态已变化，请刷新重试");
            if(reader.role()==null) throw new BusinessException("家庭成员状态已变化，请刷新重试");
            if(reader.initializedGroupId()==null) throw new BusinessException("家庭字典初始化状态缺失，请先完成迁移");
        }
        return new Actor(userId,group,Objects.equals(reader.role(),1)||Objects.equals(reader.role(),2));
    }
    private Actor actor(Integer userId) {
        if(userId==null||userId<=0) throw new BusinessException("请先登录");
        Integer candidate=mapper.currentGroup(userId); int group=candidate==null?0:candidate;
        Integer role=null;
        if(group>0) {
            if(mapper.lockGroup(group)==null) throw new BusinessException("家庭状态已变化，请刷新重试");
            role=mapper.lockRole(group,userId);
        }
        Integer current=mapper.lockUser(userId);
        if(current==null) throw new BusinessException("用户已失效，请重新登录");
        if(!Objects.equals(candidate,current)) throw new BusinessException("家庭状态已变化，请刷新重试");
        if(group>0&&role==null) throw new BusinessException("家庭成员状态已变化，请刷新重试");
        if(group>0) initializeFamily(group);
        return new Actor(userId,group,Objects.equals(role,1)||Objects.equals(role,2));
    }
    private BaseDictEntity managed(Actor actor,int id,Integer revision) {
        List<BaseDictEntity> rows=owned(actor,List.of(id));
        if(rows.size()!=1) throw new BusinessException("选项不存在或无权管理");
        BaseDictEntity row=rows.get(0); checkType(row.getDictType());
        if(personal(row.getDictType()) && (!Objects.equals(row.getScopeType(),0) || !Objects.equals(row.getFamilyGroupId(),0) || !Objects.equals(row.getUserId(),actor.userId))) throw new BusinessException("会员类型仅支持本人个人范围");
        requireScope(actor,scope(row),true);
        if(Boolean.TRUE.equals(row.getDeleted())||revision==null||!revision.equals(row.getRevision())) throw stale();
        return row;
    }
    private List<BaseDictEntity> owned(Actor actor,List<Integer> ids) {
        return ids.isEmpty()?List.of():mapper.lockOwned(ids,actor.userId,actor.groupId);
    }
    private List<BaseDictEntity> list(Actor actor,int type,Scope scope) {
        return mapper.listScope(scope==Scope.PERSONAL?0:1,scope==Scope.PERSONAL?0:actor.groupId,
                scope==Scope.PERSONAL?actor.userId:0,type);
    }
    private void requireScope(Actor actor,Scope scope,boolean write) {
        if(scope==null || (scope==Scope.FAMILY && (actor.groupId<=0 || (write&&!actor.manager))))
            throw new BusinessException("暂无家庭字典管理权限");
    }
    private BaseDictEntity newItem(Actor actor,Scope scope,int type,String name,int sort) {
        BaseDictEntity row=new BaseDictEntity(); row.setDictType(type); row.setDictCode(0);
        row.setDictName(name); row.setDictStatus(1); row.setSort(sort); row.setRevision(0);
        row.setScopeType(scope==Scope.PERSONAL?0:1); row.setFamilyGroupId(scope==Scope.PERSONAL?0:actor.groupId);
        row.setUserId(scope==Scope.PERSONAL?actor.userId:0); row.setDeleted(false); return row;
    }
    private void insert(BaseDictEntity row) {
        try { mapper.insert(row); } catch(DuplicateKeyException ex) { throw new BusinessException("名称或版本已存在，请刷新重试"); }
    }
    private void update(BaseDictEntity row) {
        if(mapper.updateCurrent(row)!=1) throw stale();
        row.setRevision(row.getRevision()+1);
    }
    private static String normalize(String name) {
        try { return DictionaryNames.normalize(name); } catch(IllegalArgumentException ex) { throw new BusinessException(ex.getMessage()); }
    }
    private void unique(List<BaseDictEntity> rows,String name,Integer except) {
        if(rows.stream().anyMatch(r->!Objects.equals(r.getId(),except)&&r.getDictName().equals(name)))
            throw new BusinessException("同一范围内名称不能重复（含停用项）");
    }
    private boolean enabled(BaseDictEntity row) { return !Boolean.TRUE.equals(row.getDeleted())&&Objects.equals(row.getDictStatus(),1); }
    private Scope scope(BaseDictEntity row) { return Objects.equals(row.getScopeType(),1)?Scope.FAMILY:Scope.PERSONAL; }
    private Item item(BaseDictEntity row) { return new Item(row.getId(),row.getDictName(),enabled(row),row.getSort(),row.getRevision()); }
    private void checkType(Integer type) { if(!DictionaryNames.isBookkeeping(type)) throw new BusinessException("不支持的记账字典类型"); }
    private static List<Integer> ids(Collection<Integer> ids) {
        if(ids==null) throw new BusinessException("请选择有效选项");
        if(ids.stream().anyMatch(id->id==null||id<=0)) throw new BusinessException("请选择有效选项");
        return ids.stream().distinct().sorted().toList();
    }
    static String version(List<BaseDictEntity> rows) {
        StringBuilder value=new StringBuilder();
        rows.stream().sorted(Comparator.comparing(BaseDictEntity::getId)).forEach(r->value.append(r.getId()).append(':')
                .append(r.getRevision()).append(':').append(r.getSort()).append(';'));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private BusinessException stale() { return new BusinessException("选项版本已变化，请刷新后重试"); }
    private BusinessException unavailable() { return new BusinessException("记账配置选项已失效，请重新保存配置"); }
}
