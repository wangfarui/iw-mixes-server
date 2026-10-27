package com.itwray.iw.auth.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Personal account categories: explicit owner predicates also apply to administrators. */
@Mapper
public interface AccountCategoryMapper {
    @IgnorePermission
    @Select("SELECT id FROM auth_user WHERE id=#{user} AND deleted=0 FOR UPDATE")
    Integer lockUser(int user);

    @IgnorePermission
    @Select("SELECT * FROM base_dict WHERE user_id=#{user} AND dict_type=2010 AND scope_type=0 AND family_group_id=0 AND dict_code<>0 AND deleted=0 ORDER BY sort,id")
    List<BaseDictEntity> list(int user);

    @IgnorePermission
    @Select("SELECT COUNT(*) FROM base_dict WHERE user_id=#{user} AND dict_type=2010 AND scope_type=0 AND family_group_id=0")
    int history(int user);

    @IgnorePermission
    @Select("SELECT COALESCE(MAX(dict_code),0)+1 FROM base_dict WHERE user_id=#{user} AND dict_type=2010 AND scope_type=0 AND family_group_id=0")
    int nextCode(int user);

    record ReferenceCount(Integer code, Long count) { }
    @IgnorePermission
    @Select("SELECT type AS code,COUNT(*) AS count FROM base_application_account WHERE user_id=#{user} AND deleted=0 GROUP BY type")
    List<ReferenceCount> references(int user);

    @IgnorePermission
    @Select("SELECT COUNT(*) FROM base_application_account WHERE user_id=#{user} AND deleted=0 AND type=#{code}")
    long countReferences(@Param("user") int user, @Param("code") int code);

    @IgnorePermission
    @Insert("""
        INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,
          scope_type,family_group_id,previous_id,revision,deleted,create_time,update_time)
        VALUES(0,2010,#{dictCode},#{dictName},1,#{sort},#{userId},0,0,NULL,0,0,NOW(),NOW())
        """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    void insert(BaseDictEntity row);

    @IgnorePermission
    @Update("""
        UPDATE base_dict SET dict_name=#{dictName},dict_status=#{dictStatus},sort=#{sort},
          revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND user_id=#{userId} AND dict_type=2010 AND scope_type=0
          AND family_group_id=0 AND deleted=0 AND revision=#{revision}
        """)
    int update(BaseDictEntity row);

    @IgnorePermission
    @Update("""
        UPDATE base_dict SET deleted=1,revision=revision+1,update_time=NOW()
        WHERE id=#{id} AND user_id=#{userId} AND dict_type=2010 AND scope_type=0
          AND family_group_id=0 AND deleted=0 AND revision=#{revision}
        """)
    int delete(BaseDictEntity row);
}
