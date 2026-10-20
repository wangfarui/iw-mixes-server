package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.bookkeeping.model.dto.BookkeepingDictionaryChangesDto;
import com.itwray.iw.bookkeeping.model.dto.BookkeepingRecordAddDto;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess;
import com.itwray.iw.web.bookkeeping.DictionaryNames;
import com.itwray.iw.web.bookkeeping.DictionaryTagSnapshot;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Owns trusted bill facts. Source rows are read only for new selections, never for history. */
@Component
public class BookkeepingDictionarySnapshots {
    private final BookkeepingDictionaryAccess dictionaries;
    private final JdbcTemplate jdbc;
    public BookkeepingDictionarySnapshots(BookkeepingDictionaryAccess dictionaries, JdbcTemplate jdbc) {
        this.dictionaries=dictionaries;this.jdbc=jdbc;
    }
    public int tagType(RecordCategoryEnum category) {return category==RecordCategoryEnum.CONSUME?4001:4011;}
    public List<DictionaryTagSnapshot> tags(Integer recordId) {
        return jdbc.query("select source_dict_id,dict_type,tag_name from bookkeeping_record_tag_snapshot where record_id=? order by id",
            (rs,n)->new DictionaryTagSnapshot(rs.getInt(1),rs.getInt(2),rs.getString(3)),recordId);
    }
    /** All newly referenced IDs lock in global numeric order, independent of dictionary type. */
    public Map<Integer,BaseDictEntity> lock(Integer categoryId, Collection<Integer> tagIds,int tagType) {
        if(tagIds!=null&&tagIds.size()>100)throw new BusinessException("标签数量不能超过100个");
        Map<Integer,Integer> types=new TreeMap<>();
        if(categoryId!=null)types.put(categoryId,4002);
        if(tagIds!=null)for(Integer id:tagIds){
            if(id==null || types.containsKey(id)&&types.get(id)!=tagType)throw new BusinessException("分类或标签类型不正确");
            types.put(id,tagType);
        }
        Map<Integer,BaseDictEntity> result=new HashMap<>();
        types.forEach((id,type)->{
            List<BaseDictEntity> rows=dictionaries.lockSelectable(UserUtils.getUserId(),type,List.of(id));
            if(rows.size()!=1)throw new BusinessException("分类或标签已失效，请重新选择");
            result.put(id,rows.get(0));
        });return result;
    }
    public List<DictionaryTagSnapshot> prepareNew(BookkeepingRecordsEntity entity,BookkeepingRecordAddDto dto){
        if(dto.getRecordType()!=null)throw new BusinessException("请更新客户端后重试：分类须使用来源ID");
        int type=tagType(entity.getRecordCategory());
        List<Integer> ids=dto.getRecordTags()==null?List.of():dto.getRecordTags().stream().distinct().toList();
        Map<Integer,BaseDictEntity> sources=lock(dto.getRecordTypeId(),ids,type);
        entity.setRecordType(0);entity.setRecordTypeId(dto.getRecordTypeId());
        entity.setRecordTypeName(dto.getRecordTypeId()==null?null:DictionaryNames.normalize(sources.get(dto.getRecordTypeId()).getDictName()));
        entity.setDictionarySnapshotStatus(1);
        return ids.stream().map(id->new DictionaryTagSnapshot(id,type,DictionaryNames.normalize(sources.get(id).getDictName()))).toList();
    }
    public List<DictionaryTagSnapshot> prepareUpdate(BookkeepingRecordsEntity entity, BookkeepingRecordsEntity original,
            BookkeepingDictionaryChangesDto changes,BookkeepingRecordAddDto dto){
        if(dto.getRecordType()!=null || dto.getRecordTypeId()!=null || dto.getRecordTags()!=null)
            throw new BusinessException("请更新客户端后重试：编辑分类或标签须使用dictionaryChanges");
        entity.setRecordType(original.getRecordType());entity.setRecordTypeId(original.getRecordTypeId());
        entity.setRecordTypeName(original.getRecordTypeName());entity.setDictionarySnapshotStatus(original.getDictionarySnapshotStatus());
        List<DictionaryTagSnapshot> previous=tags(original.getId());
        if(changes==null)return previous;
        if(!Objects.equals(original.getDictionarySnapshotStatus(),1)
            && !(changes.isCategoryPresent()&&changes.isTagsPresent()))throw new BusinessException("字典快照待核对，请重新选择分类和标签");
        List<Integer> requested=changes.isTagsPresent()?changes.getTagIds():null;
        if(changes.isTagsPresent()&&requested==null)throw new BusinessException("清空标签请传空数组");
        Map<Integer,DictionaryTagSnapshot> retained=new HashMap<>();previous.forEach(t->retained.put(t.getId(),t));
        List<Integer> added=requested==null?List.of():requested.stream().filter(id->!retained.containsKey(id)).distinct().toList();
        int type=tagType(entity.getRecordCategory());
        Map<Integer,BaseDictEntity> sources=lock(changes.isCategoryPresent()?changes.getCategoryId():null,added,type);
        if(changes.isCategoryPresent()){
            entity.setRecordTypeId(changes.getCategoryId());
            entity.setRecordTypeName(changes.getCategoryId()==null?null:DictionaryNames.normalize(sources.get(changes.getCategoryId()).getDictName()));
        }
        if(changes.isCategoryPresent()&&changes.isTagsPresent())entity.setDictionarySnapshotStatus(1);
        if(requested==null)return previous;
        return requested.stream().distinct().map(id->{
            DictionaryTagSnapshot old=retained.get(id);
            if(old!=null){if(old.getType()!=type)throw new BusinessException("收支类型已变更，请清空或重选标签");return old;}
            return new DictionaryTagSnapshot(id,type,DictionaryNames.normalize(sources.get(id).getDictName()));
        }).toList();
    }
    public void replace(Integer recordId,List<DictionaryTagSnapshot> tags){
        jdbc.update("delete from bookkeeping_record_tag_snapshot where record_id=?",recordId);
        for(DictionaryTagSnapshot tag:tags)jdbc.update("insert into bookkeeping_record_tag_snapshot(record_id,dict_type,source_dict_id,tag_name) values(?,?,?,?)",recordId,tag.getType(),tag.getId(),tag.getName());
    }
}
