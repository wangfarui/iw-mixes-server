package com.itwray.iw.auth.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SystemOptionsMapper {
    // One DB mutex serializes synchronization and registration through transaction commit.
    @IgnorePermission @Select("SELECT id FROM auth_user WHERE deleted=0 ORDER BY id LIMIT 1 FOR UPDATE")
    Integer lockGlobal();
    @IgnorePermission @Select("SELECT role_type FROM auth_user WHERE id=#{user} AND deleted=0 AND enabled=1")
    Integer role(int user);
    @IgnorePermission @Select("SELECT role_type FROM auth_user WHERE id=#{user} AND deleted=0 AND enabled=1 FOR UPDATE")
    Integer lockRole(int user);
    @IgnorePermission @Select("SELECT id FROM auth_user WHERE deleted=0 ORDER BY id")
    List<Integer> users();
    @IgnorePermission @Select("""
        SELECT d.* FROM base_dict d LEFT JOIN auth_user u ON u.id=d.user_id
        WHERE d.dict_type=#{type} AND d.deleted=0 AND (d.user_id=0 OR u.deleted=0 OR u.id IS NULL)
        ORDER BY d.user_id,d.dict_code,d.id
        """)
    List<BaseDictEntity> rows(int type);
    @IgnorePermission @Select("SELECT dict_type FROM base_dict WHERE id=#{id} AND user_id=#{user} AND deleted=0")
    Integer legacyType(@Param("user") int user,@Param("id") int id);
    @IgnorePermission @Insert("""
        INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,
        scope_type,family_group_id,previous_id,revision,deleted,create_time,update_time)
        VALUES(0,#{dictType},#{dictCode},#{dictName},1,#{sort},#{userId},0,0,NULL,0,0,NOW(),NOW())
        """)
    void insert(BaseDictEntity row);
    @IgnorePermission @Update("""
        UPDATE base_dict SET dict_name=#{dictName},dict_status=1,sort=#{sort},revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND user_id=#{userId} AND dict_type=#{dictType} AND dict_code=#{dictCode}
        AND scope_type=0 AND family_group_id=0 AND deleted=0 AND revision=#{revision}
        """)
    int update(BaseDictEntity row);
}
