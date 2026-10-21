package com.itwray.iw.auth.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Explicit ownership predicates isolate this business DAO from generic dictionary permissions. */
@Mapper
public interface BookkeepingDictionaryMapper {
    record Reader(Integer userId, Integer groupId, Integer activeGroupId, Integer role, Integer initializedGroupId) { }

    @IgnorePermission
    @Select("""
        SELECT u.id AS user_id, COALESCE(u.family_group_id,0) AS group_id,
               g.id AS active_group_id, m.role, s.family_group_id AS initialized_group_id
        FROM auth_user u
        LEFT JOIN auth_family_group g ON g.id=u.family_group_id AND g.deleted=0 AND g.status=1
        LEFT JOIN auth_family_member m ON m.group_id=g.id AND m.user_id=u.id AND m.status=1 AND m.deleted=0
        LEFT JOIN bookkeeping_family_dict_state s ON s.family_group_id=g.id
        WHERE u.id=#{userId} AND u.deleted=0
        """)
    @ConstructorArgs({
        @Arg(column="user_id", javaType=Integer.class), @Arg(column="group_id", javaType=Integer.class),
        @Arg(column="active_group_id", javaType=Integer.class), @Arg(column="role", javaType=Integer.class),
        @Arg(column="initialized_group_id", javaType=Integer.class)
    })
    Reader reader(Integer userId);
    @IgnorePermission
    @Select("SELECT COALESCE(family_group_id,0) FROM auth_user WHERE id=#{userId} AND deleted=0")
    Integer currentGroup(Integer userId);
    @IgnorePermission
    @Select("SELECT COALESCE(family_group_id,0) FROM auth_user WHERE id=#{userId} AND deleted=0 FOR UPDATE")
    Integer lockUser(Integer userId);
    @IgnorePermission
    @Select("SELECT id FROM auth_family_group WHERE id=#{groupId} AND deleted=0 AND status=1 FOR UPDATE")
    Integer lockGroup(Integer groupId);
    @Select("SELECT role FROM auth_family_member WHERE group_id=#{groupId} AND user_id=#{userId} AND status=1 AND deleted=0 FOR UPDATE")
    Integer lockRole(@Param("groupId") Integer groupId, @Param("userId") Integer userId);
    @IgnorePermission
    @Select("SELECT family_group_id FROM bookkeeping_family_dict_state WHERE family_group_id=#{groupId} FOR UPDATE")
    Integer lockState(Integer groupId);
    @IgnorePermission
    @Insert("INSERT INTO bookkeeping_family_dict_state(family_group_id, initialized_at) VALUES(#{groupId},NOW())")
    void insertState(Integer groupId);
    @Select("SELECT COUNT(*) FROM base_dict WHERE user_id=#{userId} AND scope_type=0 AND family_group_id=0 AND dict_type IN (4001,4002,4011)")
    int countPersonalHistory(Integer userId);
    List<BaseDictEntity> listScope(@Param("scope") int scope, @Param("groupId") int groupId,
                                  @Param("userId") int userId, @Param("type") int type);
    @IgnorePermission
    List<BaseDictEntity> sources(@Param("ids") List<Integer> ids);
    List<BaseDictEntity> lockOwned(@Param("ids") List<Integer> ids, @Param("userId") int userId, @Param("groupId") int groupId);
    @IgnorePermission
    List<BaseDictEntity> readOwned(@Param("ids") List<Integer> ids, @Param("userId") int userId, @Param("groupId") int groupId);
    @IgnorePermission
    List<BaseDictEntity> readSuccessors(@Param("ids") List<Integer> previousIds, @Param("userId") int userId, @Param("groupId") int groupId);
    BaseDictEntity successor(@Param("previousId") int previousId, @Param("scope") int scope,
                             @Param("groupId") int groupId, @Param("userId") int userId, @Param("type") int type);
    void insert(BaseDictEntity item);
    int updateCurrent(BaseDictEntity item);
    int retire(BaseDictEntity item);
}
