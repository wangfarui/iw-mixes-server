package com.itwray.iw.auth.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Every query carries explicit owner/scope predicates, independent of generic dictionary CRUD. */
@Mapper
public interface KitchenDictionaryMapper {
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
    @Select("SELECT scope_revision FROM kitchen_dictionary_scope_state WHERE scope_type=#{scope} AND owner_id=#{owner}")
    Long state(@Param("scope") int scope, @Param("owner") int owner);

    @IgnorePermission
    @Select("SELECT scope_revision FROM kitchen_dictionary_scope_state WHERE scope_type=#{scope} AND owner_id=#{owner} FOR UPDATE")
    Long lockState(@Param("scope") int scope, @Param("owner") int owner);

    @IgnorePermission
    @Insert("INSERT INTO kitchen_dictionary_scope_state(scope_type,owner_id,scope_revision,initialized_at) VALUES(#{scope},#{owner},0,NOW())")
    void insertState(@Param("scope") int scope, @Param("owner") int owner);

    @IgnorePermission
    @Update("UPDATE kitchen_dictionary_scope_state SET scope_revision=scope_revision+1 WHERE scope_type=#{scope} AND owner_id=#{owner}")
    int bump(@Param("scope") int scope, @Param("owner") int owner);

    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type IN (3010,3011) AND deleted=0 ORDER BY sort,id")
    List<BaseDictEntity> list(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);

    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type IN (3010,3011) AND deleted=0 ORDER BY id FOR UPDATE")
    List<BaseDictEntity> lockList(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);

    @IgnorePermission
    @Select("SELECT COUNT(*) FROM base_dict WHERE scope_type=#{scope} AND family_group_id=#{group} AND user_id=#{user} AND dict_type IN (3010,3011)")
    int history(@Param("scope") int scope, @Param("group") int group, @Param("user") int user);

    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE id=#{id} AND dict_type IN (3010,3011)")
    BaseDictEntity find(Integer id);

    @IgnorePermission
    @Insert("""
        INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,
          scope_type,family_group_id,previous_id,revision,deleted,create_time,update_time)
        VALUES(#{parentId},#{dictType},#{dictCode},#{dictName},#{dictStatus},#{sort},#{userId},
          #{scopeType},#{familyGroupId},#{previousId},0,0,NOW(),NOW())
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BaseDictEntity row);

    @IgnorePermission
    @Update("""
        UPDATE base_dict SET dict_status=#{dictStatus},sort=#{sort},revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND scope_type=#{scopeType} AND family_group_id=#{familyGroupId} AND user_id=#{userId}
        AND deleted=0 AND revision=#{revision} AND dict_type IN (3010,3011)
        """)
    int update(BaseDictEntity row);

    @IgnorePermission
    @Update("""
        UPDATE base_dict SET deleted=1,revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND deleted=0 AND revision=#{revision} AND user_id=#{userId}
        AND scope_type=#{scopeType} AND family_group_id=#{familyGroupId} AND dict_type IN (3010,3011)
        """)
    int retire(BaseDictEntity row);

    @IgnorePermission
    @Select("SELECT phase FROM kitchen_dictionary_migration_state WHERE id=1")
    String migrationPhase();
}
