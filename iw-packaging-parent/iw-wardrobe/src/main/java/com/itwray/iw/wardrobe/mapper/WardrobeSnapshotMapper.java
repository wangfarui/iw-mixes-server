package com.itwray.iw.wardrobe.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Internal only: callers must first authorize a business row, never expose arbitrary IDs. */
@Mapper
public interface WardrobeSnapshotMapper {
    @IgnorePermission
    @Select("SELECT phase FROM wardrobe_dictionary_migration_state WHERE id=1")
    String migrationPhase();
    @IgnorePermission
    @Select("SELECT * FROM wardrobe_item WHERE id=#{id} AND user_id=#{owner} AND deleted=0 FOR UPDATE")
    com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity lockItem(@Param("id") Integer id, @Param("owner") Integer owner);
    record StoredTag(Integer businessId, Integer dictType, Integer sourceId, String nameSnapshot, Integer ordinal) { }
    @IgnorePermission
    @Select("SELECT business_id,dict_type,source_id,name_snapshot,ordinal FROM wardrobe_dictionary_tag_snapshot WHERE business_type=#{kind} AND business_id=#{id} ORDER BY dict_type,ordinal")
    @ConstructorArgs({@Arg(column="business_id",javaType=Integer.class),@Arg(column="dict_type",javaType=Integer.class),@Arg(column="source_id",javaType=Integer.class),
            @Arg(column="name_snapshot",javaType=String.class),@Arg(column="ordinal",javaType=Integer.class)})
    List<StoredTag> tags(@Param("kind") String kind, @Param("id") Integer id);
    @IgnorePermission
    @Select("<script>SELECT business_id,dict_type,source_id,name_snapshot,ordinal FROM wardrobe_dictionary_tag_snapshot WHERE business_type=#{kind} AND business_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY business_id,dict_type,ordinal</script>")
    @ConstructorArgs({@Arg(column="business_id",javaType=Integer.class),@Arg(column="dict_type",javaType=Integer.class),@Arg(column="source_id",javaType=Integer.class),
            @Arg(column="name_snapshot",javaType=String.class),@Arg(column="ordinal",javaType=Integer.class)})
    List<StoredTag> tagsBatch(@Param("kind") String kind, @Param("ids") List<Integer> ids);
    @IgnorePermission
    @Delete("DELETE FROM wardrobe_dictionary_tag_snapshot WHERE business_type=#{kind} AND business_id=#{id} AND dict_type=#{type}")
    void clear(@Param("kind") String kind, @Param("id") Integer id, @Param("type") Integer type);
    @IgnorePermission
    @Insert("INSERT INTO wardrobe_dictionary_tag_snapshot(business_type,business_id,dict_type,ordinal,source_id,name_snapshot) VALUES(#{kind},#{id},#{type},#{ordinal},#{source},#{name})")
    void insert(@Param("kind") String kind, @Param("id") Integer id, @Param("type") Integer type,
                @Param("ordinal") int ordinal, @Param("source") Integer source, @Param("name") String name);
}
