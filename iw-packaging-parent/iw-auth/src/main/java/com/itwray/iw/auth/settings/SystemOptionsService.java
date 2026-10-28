package com.itwray.iw.auth.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.auth.mapper.SystemOptionsMapper;
import com.itwray.iw.auth.model.AuthRedisKeyEnum;
import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.security.MessageDigest;
import java.util.*;
import static com.itwray.iw.auth.settings.SystemOptionDefinitions.*;

@Service
@Slf4j
public class SystemOptionsService {
    private final SystemOptionsMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public SystemOptionsService(SystemOptionsMapper mapper,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper; this.json=json; this.transaction=new TransactionTemplate(manager);
        // Current reads after acquiring the mutex must see the latest committed preview state.
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public record Capabilities(boolean manageSystemOptions,boolean repairSystemOptions) { }
    public record Difference(int type,String name,int code,String option,int addCount,int correctCount) { }
    public record Preview(List<Definition> definitions,List<Difference> differences,List<String> issues,
                          String scopeVersion,int userCount,int addCount,int correctCount) { }
    public record Result(int changedCount,boolean cacheRefreshed) { }
    private record Snapshot(Preview preview,List<BaseDictEntity> inserts,List<BaseDictEntity> updates,List<Integer> users) { }
    public Capabilities capabilities(int user) { Integer role=mapper.role(user); return new Capabilities(admin(role),Objects.equals(role,20)); }
    static boolean admin(Integer role) { return Objects.equals(role,10)||Objects.equals(role,20); }
    private void authorize(int user,String domain,boolean lock) {
        Integer role=lock?mapper.lockRole(user):mapper.role(user);
        if(!admin(role)||(domain.equals("all")&&!Objects.equals(role,20))) throw new BusinessException("没有系统选项维护权限");
    }
    public Preview preview(int user,String domain,Integer type) {
        authorize(user,domain,false);
        requireType(domain,type);
        return snapshot(select(domain,type),null).preview();
    }
    public Result sync(int user,String domain,Integer type,String version) {
        requireType(domain,type);
        List<Integer> users=new ArrayList<>();
        Integer changed=transaction.execute(status -> {
            mapper.lockGlobal(); authorize(user,domain,true);
            Snapshot state=snapshot(select(domain,type),null);
            if(!Objects.equals(version,state.preview().scopeVersion())) throw new BusinessException("选项已变化，请重新预览后再同步");
            if(!state.preview().issues().isEmpty()) throw new BusinessException("存在存量异常，请先处理异常后再同步");
            users.addAll(state.users());
            return apply(state);
        });
        // A cache error after commit must never be reported as a rolled back write.
        return new Result(changed==null?0:changed,refreshCaches(users));
    }
    public boolean refresh(int user,String domain) {
        authorize(user,domain,false); select(domain,null);
        return refreshCaches(mapper.users());
    }
    private void requireType(String domain,Integer type) {
        if (!domain.equals("all") && type==null) throw new BusinessException("请选择当前类型");
    }
    public Integer legacyType(int user,int id) { return mapper.legacyType(user,id); }
    /** Called only by the registration event inside its owning transaction. No read-triggered writes. */
    public void initialize(int user) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Initialization requires transaction");
        mapper.lockGlobal();
        Snapshot state=snapshot(ALL,user);
        if(!state.preview().issues().isEmpty()) throw new BusinessException("系统选项初始化遇到异常，请联系管理员");
        apply(state);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { refreshCaches(List.of(user)); }
        });
    }
    private int apply(Snapshot state) {
        state.inserts().forEach(mapper::insert);
        for(BaseDictEntity row:state.updates()) if(mapper.update(row)!=1) throw new BusinessException("选项已变化，请重新预览");
        return state.inserts().size()+state.updates().size();
    }
    private Snapshot snapshot(List<Definition> definitions,Integer onlyUser) {
        List<Integer> users=onlyUser==null?mapper.users():List.of(onlyUser);
        List<Integer> owners=new ArrayList<>(users);
        if(onlyUser==null) owners.add(0,0);
        List<BaseDictEntity> inserts=new ArrayList<>(),updates=new ArrayList<>();
        List<Difference> differences=new ArrayList<>(); List<String> issues=new ArrayList<>();
        List<Object> fingerprint=new ArrayList<>(); fingerprint.add(definitions); fingerprint.add(owners);
        for(Definition def:definitions) {
            List<BaseDictEntity> rows=mapper.rows(def.type()).stream().filter(r -> onlyUser==null||Objects.equals(r.getUserId(),onlyUser)).toList();
            fingerprint.add(rows);
            Map<String,List<BaseDictEntity>> grouped=new HashMap<>();
            Set<Integer> codes=new HashSet<>(); def.options().forEach(o -> codes.add(o.code()));
            for(BaseDictEntity row:rows) {
                if(!owners.contains(row.getUserId())||!Objects.equals(row.getScopeType(),0)||!Objects.equals(row.getFamilyGroupId(),0)
                        ||!Objects.equals(row.getParentId(),0)||row.getRevision()==null) {
                    issues.add(def.name()+"存在归属或结构异常（记录 "+row.getId()+"）"); continue;
                }
                if(!codes.contains(row.getDictCode())) issues.add(def.name()+"存在未知编码 "+row.getDictCode()+"（记录 "+row.getId()+"）");
                grouped.computeIfAbsent(row.getUserId()+":"+row.getDictCode(),k -> new ArrayList<>()).add(row);
            }
            grouped.values().stream().filter(g -> g.size()>1).forEach(g -> issues.add(def.name()+"存在同码多条（编码 "+g.get(0).getDictCode()+"）"));
            for(Option option:def.options()) {
                int add=0,correct=0;
                for(Integer owner:owners) {
                    List<BaseDictEntity> matches=grouped.getOrDefault(owner+":"+option.code(),List.of());
                    if(matches.isEmpty()) {
                        BaseDictEntity row=new BaseDictEntity(); row.setUserId(owner); row.setDictType(def.type()); row.setDictCode(option.code());
                        row.setDictName(option.name()); row.setSort(option.sort()); inserts.add(row); add++;
                    } else if(matches.size()==1) {
                        BaseDictEntity row=matches.get(0);
                        if(!Objects.equals(row.getDictName(),option.name())||!Objects.equals(row.getSort(),option.sort())||!Objects.equals(row.getDictStatus(),1)) {
                            row=newCopy(row); row.setDictName(option.name()); row.setSort(option.sort()); row.setDictStatus(1); updates.add(row); correct++;
                        }
                    }
                }
                differences.add(new Difference(def.type(),def.name(),option.code(),option.name(),add,correct));
            }
        }
        try {
            String version=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(fingerprint)));
            return new Snapshot(new Preview(definitions,differences,List.copyOf(issues),version,users.size(),inserts.size(),updates.size()),inserts,updates,users);
        } catch(Exception e) { throw new IllegalStateException("Cannot create preview version",e); }
    }
    private BaseDictEntity newCopy(BaseDictEntity row) {
        BaseDictEntity copy=new BaseDictEntity(); org.springframework.beans.BeanUtils.copyProperties(row,copy); return copy;
    }
    boolean refreshCaches(List<Integer> users) {
        boolean success=true;
        for(Integer user:users) try {
            RedisUtil.delete(AuthRedisKeyEnum.DICT_KEY.getKey(user));
            // Atomic increment keeps concurrent post-commit refreshes distinguishable.
            RedisUtil.incrementOne(AuthRedisKeyEnum.USER_DICT_VERSION.getKey(user));
            AuthRedisKeyEnum.USER_DICT_VERSION.setExpire(user);
        } catch(RuntimeException e) { success=false; log.warn("System option cache refresh failed for user {}",user); }
        return success;
    }
}
