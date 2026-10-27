package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.AccountCategoryMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Keeps the existing 2010 codes stable; rename changes current account classification names. */
@Service
public class AccountCategoryService {
    private final AccountCategoryMapper mapper;
    public AccountCategoryService(AccountCategoryMapper mapper) { this.mapper=mapper; }
    public record Item(int id, int code, String name, boolean enabled, int revision, long accountCount) { }
    public record Management(List<Item> items, String scopeVersion, long unclassifiedCount) { }
    public record Mutation(Item item) { }

    @Transactional(readOnly=true)
    public Management items(int user) {
        List<BaseDictEntity> rows=mapper.list(user);
        Map<Integer,Long> counts=new HashMap<>();
        mapper.references(user).forEach(c -> counts.put(c.code(),c.count()));
        return new Management(rows.stream().map(r -> item(r,counts.getOrDefault(r.getDictCode(),0L))).toList(),
                version(user,rows),counts.getOrDefault(null,0L)+counts.getOrDefault(0,0L));
    }

    @Transactional
    public Mutation create(int user,String name,String expected) {
        List<BaseDictEntity> rows=locked(user,expected);
        String normalized=normalizeName(name);
        checkName(rows,normalized,null);
        BaseDictEntity row=new BaseDictEntity();
        row.setUserId(user); row.setDictType(2010); row.setDictName(normalized);
        row.setDictCode(mapper.nextCode(user)); row.setDictStatus(1); row.setRevision(0);
        row.setSort(rows.stream().map(BaseDictEntity::getSort).filter(Objects::nonNull).max(Integer::compareTo).orElse(0)+1);
        mapper.insert(row);
        return new Mutation(item(row,0));
    }

    @Transactional
    public void rename(int user,int id,String name,int revision,String expected) {
        List<BaseDictEntity> rows=locked(user,expected);
        BaseDictEntity row=owned(rows,id,revision);
        String normalized=normalizeName(name); checkName(rows,normalized,id);
        if (normalized.equals(row.getDictName())) return;
        row.setDictName(normalized); changed(mapper.update(row));
    }

    @Transactional
    public void status(int user,int id,boolean enabled,int revision,String expected) {
        BaseDictEntity row=owned(locked(user,expected),id,revision);
        row.setDictStatus(enabled?1:0); changed(mapper.update(row));
    }

    @Transactional
    public void delete(int user,int id,int revision,String expected) {
        BaseDictEntity row=owned(locked(user,expected),id,revision);
        if(mapper.countReferences(user,row.getDictCode())>0)
            throw new BusinessException("分类仍被账号使用，请先调整账号分类");
        changed(mapper.delete(row));
    }

    @Transactional
    public void order(int user,List<Integer> ids,String expected) {
        List<BaseDictEntity> rows=locked(user,expected);
        if(ids==null || ids.size()!=rows.size() || new HashSet<>(ids).size()!=ids.size()
            || !new HashSet<>(ids).equals(new HashSet<>(rows.stream().map(BaseDictEntity::getId).toList())))
            throw new BusinessException("分类已变化，请刷新后重新排序");
        Map<Integer,BaseDictEntity> byId=new HashMap<>(); rows.forEach(r -> byId.put(r.getId(),r));
        for(int i=0;i<ids.size();i++) { BaseDictEntity row=byId.get(ids.get(i)); row.setSort(i+1); changed(mapper.update(row)); }
    }

    /** Account writes and category writes lock the same existing user row, including first category creation. */
    public void lockOwner(int user) {
        if(mapper.lockUser(user)==null) throw new BusinessException("当前用户不存在");
    }

    public void validateSelection(int user,Integer code,Integer oldCode) {
        if(code==null || code==0 || Objects.equals(code,oldCode)) return;
        long matches=mapper.list(user).stream().filter(r -> Objects.equals(r.getDictCode(),code)&&Objects.equals(r.getDictStatus(),1)).count();
        if(matches!=1) throw new BusinessException("请选择有效的应用分类，停用分类不能用于新选择");
    }

    private List<BaseDictEntity> locked(int user,String expected) {
        lockOwner(user); List<BaseDictEntity> rows=mapper.list(user);
        if(!Objects.equals(version(user,rows),expected)) throw new BusinessException("分类已变化，请刷新后重试");
        return rows;
    }
    private BaseDictEntity owned(List<BaseDictEntity> rows,int id,int revision) {
        BaseDictEntity row=rows.stream().filter(r -> r.getId()==id).findFirst().orElseThrow(() -> new BusinessException("分类不存在或无权操作"));
        if(!Objects.equals(row.getRevision(),revision)) throw new BusinessException("分类已变化，请刷新后重试");
        return row;
    }
    private void changed(int count) { if(count!=1) throw new BusinessException("分类已变化，请刷新后重试"); }
    static String normalizeName(String raw) {
        String name=raw==null?"":raw.replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$", "");
        if(name.isEmpty()||name.codePointCount(0,name.length())>32) throw new BusinessException("分类名称请输入1–32个字符");
        return name;
    }
    private void checkName(List<BaseDictEntity> rows,String name,Integer id) {
        // Match common case-insensitive MySQL collations and include disabled categories.
        if(rows.stream().anyMatch(r -> !Objects.equals(r.getId(),id)&&name.equalsIgnoreCase(r.getDictName())))
            throw new BusinessException("分类名称已存在（包含停用分类）");
    }
    private static Item item(BaseDictEntity r,long count) {
        return new Item(r.getId(),r.getDictCode(),r.getDictName(),Objects.equals(r.getDictStatus(),1),r.getRevision()==null?0:r.getRevision(),count);
    }
    private static String version(int user,List<BaseDictEntity> rows) {
        try {
            String value=user+":"+rows.stream().map(r -> Arrays.asList(r.getId(),r.getRevision(),r.getSort(),r.getDictName(),r.getDictStatus()).toString()).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}
