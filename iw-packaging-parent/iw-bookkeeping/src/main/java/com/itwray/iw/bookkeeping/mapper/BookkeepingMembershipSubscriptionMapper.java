package com.itwray.iw.bookkeeping.mapper;

import com.itwray.iw.bookkeeping.model.entity.BookkeepingMembershipSubscriptionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.itwray.iw.web.annotation.IgnorePermission;

/**
 * 会员订阅记录表 Mapper 接口
 *
 * @author wray
 * @since 2025/11/5
 */
@Mapper
public interface BookkeepingMembershipSubscriptionMapper extends BaseMapper<BookkeepingMembershipSubscriptionEntity> {

    @IgnorePermission
    @Select("SELECT * FROM bookkeeping_membership_subscription WHERE id=#{id} AND user_id=#{userId} AND deleted=0 FOR UPDATE")
    BookkeepingMembershipSubscriptionEntity lockOwned(@Param("id") Integer id,@Param("userId") Integer userId);
}
