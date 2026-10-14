package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.bookkeeping.model.vo.BookkeepingExpenseAware;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.model.vo.UserOwnerDetailVo;
import com.itwray.iw.web.support.UserOwnerFillSupport;
import java.util.Collection;
import java.util.List;

/** Fill recorder permissions and payer display names independently, with batched name queries. */
public final class BookkeepingExpenseFillSupport {
    private BookkeepingExpenseFillSupport() {}

    public static void fill(Collection<? extends BookkeepingExpenseAware> records) {
        if (records == null || records.isEmpty()) return;
        UserOwnerFillSupport.fill(records);
        List<? extends BookkeepingExpenseAware> expenses = records.stream()
                .filter(record -> RecordCategoryEnum.CONSUME.equals(record.getRecordCategory())).toList();
        List<UserOwnerDetailVo<Integer>> payers = expenses.stream().map(record -> {
            if (record.getExpenseUserId() == null) record.setExpenseUserId(record.getUserId());
            UserOwnerDetailVo<Integer> payer = new UserOwnerDetailVo<Integer>() {};
            payer.setUserId(record.getExpenseUserId());
            return payer;
        }).toList();
        UserOwnerFillSupport.fill(payers);
        int index = 0;
        for (BookkeepingExpenseAware record : expenses) record.setExpenseUserName(payers.get(index++).getUserName());
    }
}
