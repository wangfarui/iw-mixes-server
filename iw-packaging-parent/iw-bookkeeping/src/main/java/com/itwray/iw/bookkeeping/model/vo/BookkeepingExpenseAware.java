package com.itwray.iw.bookkeeping.model.vo;

import com.itwray.iw.web.model.vo.UserOwnerAware;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;

public interface BookkeepingExpenseAware extends UserOwnerAware {
    default RecordCategoryEnum getRecordCategory() { return RecordCategoryEnum.CONSUME; }
    Integer getExpenseUserId();
    void setExpenseUserId(Integer userId);
    void setExpenseUserName(String name);
}
