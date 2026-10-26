package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.KitchenDictionaryMapper;
import com.itwray.iw.web.bookkeeping.DictionaryNames;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.kitchen.KitchenDictionaryAccess;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Flat kitchen dictionaries for fridge category/section. Scope locks serialize names and initialization. */
@Service
@Transactional(rollbackFor = Exception.class)
public class KitchenDictionaryService implements KitchenDictionaryAccess {
    public enum Type {
        CATEGORY(3010), SECTION(3011);
        public final int code;
        Type(int code) { this.code = code; }
    }
    public enum Scope { PERSONAL, FAMILY }
    public record Item(Integer id, Integer type, String name, boolean enabled, Integer sort, Integer revision, String scope) { }
    public record Management(List<Item> items, boolean canManage, String scopeVersion, Integer familyGroupId) { }
    public record Rename(Integer oldId, Item item, String scopeVersion) { }
    public record Mutation(Item item, String scopeVersion) { }
    public record Skipped(Integer id, String reason) { }
    public record Copied(List<Item> created, List<Skipped> skipped, String scopeVersion) { }
    private record Actor(int user, int group, boolean manager) { }
    private final KitchenDictionaryMapper mapper;

    public KitchenDictionaryService(KitchenDictionaryMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true)
    public Management items(int user, Type type, Scope scope) {
        requireType(type); Actor actor = actor(user, false); requireScope(actor, scope, false, null);
        String version = version(actor, scope, false);
        List<Item> rows = list(actor, scope, false).stream().filter(r -> Objects.equals(r.getDictType(), type.code))
                .sorted(order()).map(this::item).toList();
        return new Management(rows, scope == Scope.PERSONAL || actor.manager, version, actor.group);
    }

    public Mutation create(int user, Type type, Scope scope, String name, String expectedVersion, Integer expectedGroup) {
        requireType(type); Actor actor = actor(user, true); requireScope(actor, scope, true, expectedGroup);
        expect(actor, scope, expectedVersion); List<BaseDictEntity> rows = list(actor, scope, true);
        String normalized = normalize(name);
        unique(rows, type.code, normalized, null);
        BaseDictEntity row = fresh(actor, scope, type.code, normalized, nextSort(rows, type.code));
        insert(row); bump(actor, scope);
        return new Mutation(item(row), version(actor, scope, true));
    }

    public Rename rename(int user, int id, String name, Integer revision, String expectedVersion, Integer expectedGroup) {
        Actor actor = actor(user, true); BaseDictEntity row = managed(actor, id, revision, expectedVersion, expectedGroup);
        String normalized = normalize(name); Scope scope = scope(row);
        if (normalized.equals(row.getDictName())) return new Rename(id, item(row), version(actor, scope, true));
        unique(list(actor, scope, true), row.getDictType(), normalized, id);
        if (mapper.retire(row) != 1) throw stale();
        BaseDictEntity next = fresh(actor, scope, row.getDictType(), normalized, row.getSort());
        next.setPreviousId(id); next.setDictStatus(row.getDictStatus()); next.setDictCode(row.getDictCode());
        insert(next); bump(actor, scope);
        return new Rename(id, item(next), version(actor, scope, true));
    }

    public Mutation status(int user, int id, boolean enabled, Integer revision, String expectedVersion, Integer expectedGroup) {
        Actor actor = actor(user, true); BaseDictEntity row = managed(actor, id, revision, expectedVersion, expectedGroup);
        if (!Objects.equals(row.getDictStatus(), enabled ? 1 : 0)) {
            row.setDictStatus(enabled ? 1 : 0); update(row); bump(actor, scope(row));
        }
        return new Mutation(item(row), version(actor, scope(row), true));
    }

    public String delete(int user, int id, Integer revision, String expectedVersion, Integer expectedGroup) {
        Actor actor = actor(user, true); BaseDictEntity row = managed(actor, id, revision, expectedVersion, expectedGroup);
        if (mapper.retire(row) != 1) throw stale();
        bump(actor, scope(row)); return version(actor, scope(row), true);
    }

    public String order(int user, Type type, Scope scope, List<Integer> ids, String expectedVersion, Integer expectedGroup) {
        requireType(type); Actor actor = actor(user, true); requireScope(actor, scope, true, expectedGroup); expect(actor, scope, expectedVersion);
        List<BaseDictEntity> rows = list(actor, scope, true).stream().filter(r -> Objects.equals(r.getDictType(), type.code)).toList();
        if (ids == null || ids.size() != rows.size() || new HashSet<>(ids).size() != ids.size()
                || !new HashSet<>(ids).equals(new HashSet<>(rows.stream().map(BaseDictEntity::getId).toList()))) throw stale();
        for (BaseDictEntity row : rows) { row.setSort(ids.indexOf(row.getId()) + 1); update(row); }
        bump(actor, scope); return version(actor, scope, true);
    }

    public Copied copyPersonal(int user, List<Integer> sourceIds, String personalVersion, String familyVersion, Integer expectedGroup) {
        Actor actor = actor(user, true); requireScope(actor, Scope.FAMILY, true, expectedGroup);
        expect(actor, Scope.PERSONAL, personalVersion); expect(actor, Scope.FAMILY, familyVersion);
        Set<Integer> ids = checkedIds(sourceIds); List<BaseDictEntity> sources = list(actor, Scope.PERSONAL, true);
        Map<Integer,BaseDictEntity> sourceMap = new HashMap<>(); sources.forEach(r -> sourceMap.put(r.getId(), r));
        if (!sourceMap.keySet().containsAll(ids)) throw failure("只能复制本人的当前厨房选项");
        List<BaseDictEntity> targets = new ArrayList<>(list(actor, Scope.FAMILY, true));
        List<Item> created = new ArrayList<>(); List<Skipped> skipped = new ArrayList<>();
        for (BaseDictEntity source : sources.stream().filter(r -> ids.contains(r.getId())).sorted(order()).toList()) {
            if (!enabled(source)) { skipped.add(new Skipped(source.getId(), "来源已停用")); continue; }
            boolean duplicate = targets.stream().anyMatch(r -> sameNameKey(r, source.getDictType(), source.getDictName()));
            if (duplicate) { skipped.add(new Skipped(source.getId(), "家庭已存在同名选项（含停用项）")); continue; }
            BaseDictEntity target = fresh(actor, Scope.FAMILY, source.getDictType(), source.getDictName(), nextSort(targets, source.getDictType()));
            target.setDictCode(source.getDictCode());
            insert(target); targets.add(target); created.add(item(target));
        }
        if (!created.isEmpty()) bump(actor, Scope.FAMILY);
        return new Copied(created, skipped, version(actor, Scope.FAMILY, true));
    }

    public void initializePersonal(int user) {
        if (mapper.lockUser(user) == null) throw failure("用户已失效");
        initialize(new Actor(user, 0, false), Scope.PERSONAL);
    }

    public void initializeFamily(int group) {
        if (mapper.lockGroup(group) == null) throw failure("家庭已失效");
        initialize(new Actor(0, group, true), Scope.FAMILY);
    }

    public String initializeManaged(int user, Scope scope, Integer expectedGroup) {
        Actor actor = actor(user, true); requireScope(actor, scope, true, expectedGroup); initialize(actor, scope);
        return version(actor, scope, true);
    }

    private void initialize(Actor actor, Scope scope) {
        int owner = scope == Scope.PERSONAL ? actor.user : actor.group;
        int code = scope == Scope.PERSONAL ? 0 : 1;
        if (mapper.lockState(code, owner) != null) return;
        if (mapper.history(code, scope == Scope.FAMILY ? owner : 0, scope == Scope.PERSONAL ? owner : 0) != 0)
            throw failure("厨房字典初始化状态缺失，请先完成迁移");
        List<BaseDictEntity> templates = mapper.list(0, 0, 0);
        if (!templates.stream().filter(this::enabled).map(BaseDictEntity::getDictType).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(3010, 3011))) throw failure("系统厨房模板未就绪");
        for (BaseDictEntity template : templates.stream().filter(this::enabled).sorted(order()).toList()) {
            BaseDictEntity row = fresh(actor, scope, template.getDictType(), normalize(template.getDictName()), template.getSort());
            row.setDictCode(template.getDictCode());
            insert(row);
        }
        mapper.insertState(code, owner);
    }

    @Override
    @Transactional(readOnly = true)
    public Context context(Integer owner) {
        Actor actor = actor(owner, false);
        String personal = version(actor, Scope.PERSONAL, false);
        String family = actor.group == 0 ? "" : version(actor, Scope.FAMILY, false);
        List<BaseDictEntity> rows = new ArrayList<>(list(actor, Scope.PERSONAL, false));
        if (actor.group > 0) rows.addAll(list(actor, Scope.FAMILY, false));
        return new Context(owner, actor.group, personal, family, token(owner, actor.group, personal, family), selectable(rows));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Identity> lockOwners(Collection<Integer> ownerUserIds) {
        Set<Integer> ids = checkedIds(ownerUserIds);
        Map<Integer,Integer> groups = new HashMap<>();
        for (Integer id : ids) { Integer group = mapper.group(id); if (group == null) throw stale(); groups.put(id, group); }
        List<Integer> ordered = ids.stream().sorted(Comparator.comparing((Integer id) -> groups.get(id)).thenComparing(Integer::compareTo)).toList();
        List<Identity> result = new ArrayList<>();
        for (Integer id : ordered) {
            Actor actor = actor(id, true); version(actor, Scope.PERSONAL, true);
            if (actor.group > 0) version(actor, Scope.FAMILY, true);
            boolean child = actor.group > 0 && Objects.equals(mapper.lockRole(actor.group, id), 4);
            result.add(new Identity(id, actor.group, actor.manager, child));
        }
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BaseDictEntity> lockSelectable(Integer owner, String contextToken, Collection<Integer> sourceIds) {
        Actor actor = actor(owner, true);
        String personal = version(actor, Scope.PERSONAL, true);
        String family = actor.group == 0 ? "" : version(actor, Scope.FAMILY, true);
        if (!token(owner, actor.group, personal, family).equals(contextToken)) throw stale();
        List<BaseDictEntity> rows = new ArrayList<>(list(actor, Scope.PERSONAL, true));
        if (actor.group > 0) rows.addAll(list(actor, Scope.FAMILY, true));
        Set<Integer> ids = checkedIds(sourceIds);
        List<BaseDictEntity> result = selectable(rows).stream().filter(r -> ids.contains(r.getId())).toList();
        if (result.size() != ids.size()) throw failure("选项已失效或无权使用，请重新选择");
        return result;
    }

    private List<BaseDictEntity> selectable(List<BaseDictEntity> rows) {
        return rows.stream().filter(this::enabled).sorted(order()).toList();
    }

    private Actor actor(Integer user, boolean lock) {
        if (user == null || user <= 0) throw failure("请先登录");
        Integer candidate = mapper.group(user); if (candidate == null) throw failure("用户已失效");
        Integer role = null;
        if (candidate > 0) {
            Integer active = lock ? mapper.lockGroup(candidate) : mapper.activeGroup(candidate);
            if (active == null) throw stale();
            role = lock ? mapper.lockRole(candidate, user) : mapper.role(candidate, user);
            if (role == null) throw stale();
        }
        if (lock && !Objects.equals(candidate, mapper.lockUser(user))) throw stale();
        return new Actor(user, candidate, Objects.equals(role, 1) || Objects.equals(role, 2));
    }

    private BaseDictEntity managed(Actor actor, int id, Integer revision, String expectedVersion, Integer expectedGroup) {
        BaseDictEntity source = mapper.find(id);
        if (source == null || (!Objects.equals(source.getScopeType(), 0) && !Objects.equals(source.getScopeType(), 1))) throw failure("选项不存在");
        Scope scope = scope(source); requireScope(actor, scope, true, expectedGroup); expect(actor, scope, expectedVersion);
        BaseDictEntity row = list(actor, scope, true).stream().filter(r -> Objects.equals(r.getId(), id)).findFirst().orElseThrow(() -> failure("选项不存在或无权管理"));
        if (revision == null || !revision.equals(row.getRevision())) throw stale(); return row;
    }

    private void requireScope(Actor actor, Scope scope, boolean write, Integer expectedGroup) {
        if (scope == null) throw failure("请选择字典范围");
        if (scope == Scope.FAMILY && (actor.group <= 0 || (write && (!actor.manager || !Objects.equals(expectedGroup, actor.group)))))
            throw failure("家庭状态已变化或无管理权限，请刷新重试");
    }

    private String version(Actor actor, Scope scope, boolean lock) {
        int code = scope == Scope.PERSONAL ? 0 : 1, owner = scope == Scope.PERSONAL ? actor.user : actor.group;
        Long revision = lock ? mapper.lockState(code, owner) : mapper.state(code, owner);
        if (revision == null) throw failure("厨房字典未就绪，请先完成初始化或迁移");
        return token(code, owner, revision);
    }

    private void expect(Actor actor, Scope scope, String expected) {
        if (!version(actor, scope, true).equals(expected)) throw stale();
    }

    private void bump(Actor actor, Scope scope) {
        if (mapper.bump(scope == Scope.PERSONAL ? 0 : 1, scope == Scope.PERSONAL ? actor.user : actor.group) != 1) throw stale();
    }

    private List<BaseDictEntity> list(Actor actor, Scope scope, boolean lock) {
        int code = scope == Scope.PERSONAL ? 0 : 1, group = scope == Scope.FAMILY ? actor.group : 0, user = scope == Scope.PERSONAL ? actor.user : 0;
        return lock ? mapper.lockList(code, group, user) : mapper.list(code, group, user);
    }

    private BaseDictEntity fresh(Actor actor, Scope scope, int type, String name, int sort) {
        BaseDictEntity row = new BaseDictEntity(); row.setParentId(0); row.setDictType(type); row.setDictCode(0);
        row.setDictName(name); row.setDictStatus(1); row.setSort(sort); row.setRevision(0); row.setDeleted(false);
        row.setScopeType(scope == Scope.PERSONAL ? 0 : 1); row.setFamilyGroupId(scope == Scope.FAMILY ? actor.group : 0);
        row.setUserId(scope == Scope.PERSONAL ? actor.user : 0); return row;
    }

    private void insert(BaseDictEntity row) {
        try { mapper.insert(row); } catch (DuplicateKeyException ex) { throw failure("名称或版本已存在，请刷新重试"); }
    }

    private void update(BaseDictEntity row) {
        if (mapper.update(row) != 1) throw stale(); row.setRevision(row.getRevision() + 1);
    }

    private void unique(List<BaseDictEntity> rows, int type, String name, Integer except) {
        if (rows.stream().anyMatch(r -> !Objects.equals(r.getId(), except) && sameNameKey(r, type, name)))
            throw failure("同一范围内名称不能重复（含停用项）");
    }

    private boolean sameNameKey(BaseDictEntity row, int type, String name) {
        return Objects.equals(row.getDictType(), type) && row.getDictName().equals(name);
    }

    private int nextSort(List<BaseDictEntity> rows, int type) {
        return rows.stream().filter(r -> Objects.equals(r.getDictType(), type)).mapToInt(r -> r.getSort() == null ? 0 : r.getSort()).max().orElse(0) + 1;
    }

    private Comparator<BaseDictEntity> order() {
        return Comparator.comparing(BaseDictEntity::getSort, Comparator.nullsLast(Integer::compareTo)).thenComparing(BaseDictEntity::getId);
    }

    private boolean enabled(BaseDictEntity row) { return !Boolean.TRUE.equals(row.getDeleted()) && Objects.equals(row.getDictStatus(), 1); }
    private Scope scope(BaseDictEntity row) { return Objects.equals(row.getScopeType(), 1) ? Scope.FAMILY : Scope.PERSONAL; }
    private Item item(BaseDictEntity row) {
        return new Item(row.getId(), row.getDictType(), row.getDictName(), enabled(row), row.getSort(), row.getRevision(),
                scope(row) == Scope.FAMILY ? "FAMILY" : "PERSONAL");
    }
    private void requireType(Type type) { if (type == null) throw failure("请选择字典类型"); }
    private String normalize(String name) { try { return DictionaryNames.normalize(name); } catch (IllegalArgumentException ex) { throw failure(ex.getMessage()); } }
    private Set<Integer> checkedIds(Collection<Integer> ids) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id <= 0)) throw failure("请选择有效选项");
        return new LinkedHashSet<>(ids);
    }
    private String token(Object... parts) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.deepToString(parts).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private BusinessException stale() { return failure("选项或所属范围已变化，请刷新后重试"); }
    private BusinessException failure(String message) { return new BusinessException(message); }
}
