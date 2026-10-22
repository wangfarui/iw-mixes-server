package com.itwray.iw.auth.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Every query carries explicit owner/scope predicates, independent of generic dictionary CRUD. */
@Mapper
public interface WardrobeDictionaryMapper {
    @IgnorePermission
    @Select("SELECT COALESCE(family_group_id,0) FROM auth_user WHERE id=#{id} AND deleted=0")
    Integer group(Integer id);
    @IgnorePermission
    @Select("SELECT COALESCE(family_group_id,0) FROM auth_user WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Integer lockUser(Integer id);
    @IgnorePermission
    @Select("SELECT id FROM auth_family_group WHERE id=#{id} AND deleted=0 AND status=1 FOR UPDATE")
    Integer lockGroup(Integer id);
    @IgnorePermission
    @Select("SELECT id FROM auth_family_group WHERE id=#{id} AND deleted=0 AND status=1")
    Integer activeGroup(Integer id);
    @IgnorePermission
    @Select("SELECT role FROM auth_family_member WHERE group_id=#{group} AND user_id=#{user} AND deleted=0 AND status=1")
    Integer role(@Param("group") int group, @Param("user") int user);
    @IgnorePermission
    @Select("SELECT role FROM auth_family_member WHERE group_id=#{group} AND user_id=#{user} AND deleted=0 AND status=1 FOR UPDATE")
    Integer lockRole(@Param("group") int group, @Param("user") int user);
    @IgnorePermission
    @Select("SELECT scope_revision FROM wardrobe_dictionary_scope_state WHERE scope_type=#{scope} AND owner_id=#{owner}")
    Long state(@Param("scope") int scope, @Param("owner") int owner);
    @IgnorePermission
    @Select("SELECT scope_revision FROM wardrobe_dictionary_scope_state WHERE scope_type=#{scope} AND owner_id=#{owner} FOR UPDATE")
    Long lockState(@Param("scope") int scope, @Param("owner") int owner);
    @IgnorePermission
    @Insert("INSERT INTO wardrobe_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at) VALUES(#{scope},#{owner},0,NOW())")
    void insertState(@Param("scope") int scope, @Param("owner") int owner);
    @IgnorePermission
    @Update("UPDATE wardrobe_dictionary_scope_state SET scope_revision=scope_revision+1 WHERE scope_type=#{scope} AND owner_id=#{owner}")
    int bump(@Param("scope") int scope, @Param("owner") int owner);
    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type BETWEEN 5002 AND 5006 AND deleted=0 ORDER BY sort,id")
    List<BaseDictEntity> list(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);
    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type BETWEEN 5002 AND 5006 AND deleted=0 ORDER BY id FOR UPDATE")
    List<BaseDictEntity> lockList(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);
    @IgnorePermission
    @Select("SELECT COUNT(*) FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type BETWEEN 5002 AND 5006")
    int history(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);
    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE id=#{id} AND dict_type BETWEEN 5002 AND 5006")
    BaseDictEntity find(Integer id);
    @IgnorePermission
    @Insert("""
        INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,
          scope_type,family_group_id,previous_id,revision,deleted,wardrobe_category_lineage_id,wardrobe_match_kind,create_time,update_time)
        VALUES(#{parentId},#{dictType},#{dictCode},#{dictName},#{dictStatus},#{sort},#{userId},
          #{scopeType},#{familyGroupId},#{previousId},0,0,#{wardrobeCategoryLineageId},#{wardrobeMatchKind},NOW(),NOW())
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BaseDictEntity row);
    @IgnorePermission
    @Update("UPDATE base_dict SET wardrobe_category_lineage_id=id WHERE id=#{id} AND dict_type=5002 AND wardrobe_category_lineage_id IS NULL")
    void root(Integer id);
    @IgnorePermission
    @Update("""
        UPDATE base_dict SET dict_status=#{dictStatus},sort=#{sort},revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND scope_type=#{scopeType} AND family_group_id=#{familyGroupId} AND user_id=#{userId}
        AND deleted=0 AND revision=#{revision}
        """)
    int update(BaseDictEntity row);
    @IgnorePermission
    @Update("UPDATE base_dict SET deleted=1,revision=revision+1,update_time=NOW() WHERE id=#{id} AND deleted=0 AND revision=#{revision} AND user_id=#{userId} AND scope_type=#{scopeType} AND family_group_id=#{familyGroupId}")
    int retire(BaseDictEntity row);
    @IgnorePermission
    @Update("UPDATE base_dict SET parent_id=#{next},revision=revision+1,update_time=NOW() WHERE parent_id=#{old} AND dict_type=5006 AND deleted=0 AND scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user}")
    void reparent(@Param("old") int old, @Param("next") int next, @Param("scope") int scope,
                  @Param("group") int group, @Param("user") int user);
}
