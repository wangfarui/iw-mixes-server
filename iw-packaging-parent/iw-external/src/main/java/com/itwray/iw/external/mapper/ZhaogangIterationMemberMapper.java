package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.MemberEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ZhaogangIterationMemberMapper extends BaseMapper<MemberEntity> {

    @Delete("delete from external_zhaogang_iteration_member where iteration_id = #{iterationId}")
    int deleteByIterationId(@Param("iterationId") long iterationId);
}
