package com.itwray.iw.bookkeeping.support;

import cn.idev.excel.FastExcel;
import com.itwray.iw.bookkeeping.model.bo.BookkeepingRecordsImportBo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookkeepingImportFormatTest {
    @Test
    void readsExternalSpreadsheetMonthAndTwentyFourHourTime() {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FastExcel.write(file).head(List.of(List.of("记录时间"), List.of("分类"), List.of("收支类型"),
                List.of("金额"), List.of("备注"))).sheet().doWrite(List.of(
                List.of("2032/8/1 8:00", "餐饮", "支出", BigDecimal.ONE, "fixture"),
                List.of("2032/12/31 23:59", "餐饮", "支出", BigDecimal.ONE, "fixture")));
        List<BookkeepingRecordsImportBo> rows = FastExcel.read(new ByteArrayInputStream(file.toByteArray()))
                .head(BookkeepingRecordsImportBo.class).sheet().doReadSync();
        assertEquals(LocalDateTime.of(2032, 8, 1, 8, 0), rows.get(0).getRecordTime());
        assertEquals(LocalDateTime.of(2032, 12, 31, 23, 59), rows.get(1).getRecordTime());
    }
}
