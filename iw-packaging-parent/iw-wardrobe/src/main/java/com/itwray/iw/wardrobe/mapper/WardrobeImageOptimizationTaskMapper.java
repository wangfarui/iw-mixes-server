package com.itwray.iw.wardrobe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WardrobeImageOptimizationTaskMapper extends BaseMapper<WardrobeImageOptimizationTaskEntity> {

    @Select("select * from wardrobe_image_optimization_task "
            + "where task_id = #{taskId} and user_id = #{userId} and deleted = 0 limit 1 for update")
    WardrobeImageOptimizationTaskEntity selectByTaskIdForUpdate(@Param("taskId") String taskId,
                                                                 @Param("userId") Integer userId);
}
