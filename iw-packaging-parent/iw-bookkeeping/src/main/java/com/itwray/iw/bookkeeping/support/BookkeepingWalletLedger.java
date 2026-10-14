package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.bookkeeping.dao.BookkeepingWalletDao;
import com.itwray.iw.bookkeeping.dao.BookkeepingWalletRecordsDao;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingWalletEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.bookkeeping.model.enums.WalletRecordsChangeTypeEnum;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

@Component
public class BookkeepingWalletLedger {
    private final BookkeepingWalletDao wallets;
    private final BookkeepingWalletRecordsDao records;

    public BookkeepingWalletLedger(BookkeepingWalletDao wallets, BookkeepingWalletRecordsDao records) {
        this.wallets = wallets;
        this.records = records;
    }

    /** Record lock is held by the caller; wallet locks use user-id order when moving a payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(BookkeepingRecordsEntity before, BookkeepingRecordsEntity after) {
        for (Map.Entry<Integer, BigDecimal> change : changes(before, after).entrySet()) {
            if (change.getValue().signum() == 0) continue;
            BookkeepingWalletEntity wallet = wallets.getBaseMapper().lockWallet(change.getKey());
            if (wallet == null) throw new BusinessException("支出人或收入人的钱包不存在，本次操作未保存");
            if (wallets.getBaseMapper().updateWalletBalance(change.getKey(), change.getValue()) != 1) {
                throw new BusinessException("钱包更新失败，本次操作未保存");
            }
            records.saveChangeRecord(change.getKey(), WalletRecordsChangeTypeEnum.BALANCE,
                    wallet.getWalletBalance(), change.getValue());
        }
    }

    static Map<Integer, BigDecimal> changes(BookkeepingRecordsEntity before, BookkeepingRecordsEntity after) {
        Map<Integer, BigDecimal> changes = new TreeMap<>();
        addChange(changes, before, -1);
        addChange(changes, after, 1);
        return changes;
    }

    private static void addChange(Map<Integer, BigDecimal> changes, BookkeepingRecordsEntity record, int direction) {
        if (record == null) return;
        boolean expense = RecordCategoryEnum.CONSUME.equals(record.getRecordCategory());
        Integer userId = expense && record.getExpenseUserId() != null ? record.getExpenseUserId() : record.getUserId();
        BigDecimal delta = expense ? record.getAmount().negate() : record.getAmount();
        changes.merge(userId, direction > 0 ? delta : delta.negate(), BigDecimal::add);
    }
}
