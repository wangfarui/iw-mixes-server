package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberRoleEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ZhaogangIterationMemberRoleMapper extends BaseMapper<MemberRoleEntity> {

    @Delete("""
            delete role_item from external_zhaogang_iteration_member_role role_item
             inner join external_zhaogang_iteration_member member_item on member_item.id = role_item.member_id
             where member_item.iteration_id = #{iterationId}
            """)
    int deleteByIterationId(@Param("iterationId") long iterationId);
}
