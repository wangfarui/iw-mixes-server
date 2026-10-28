package com.itwray.iw.life;

import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LifeDatesTest {
    private LifeDates at(String day) {return new LifeDates(Clock.fixed(LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneId.of("Asia/Shanghai")));}
    private Map<String,Object> event(String calendar,int y,int m,int d,boolean leap,String repeat,Integer reminder) {
        Map<String,Object> row=new HashMap<>();row.put("calendarType",calendar);row.put("eventYear",y);row.put("eventMonth",m);row.put("eventDay",d);row.put("leapMonth",leap);row.put("repeatType",repeat);row.put("remindDays",reminder);return row;
    }
    // Independent expected conversions: Hong Kong Observatory annual calendar tables (2023/2026).
    @Test void hkoGregorianReferenceDates() {
        LifeDates dates=at("2026-01-01");
        assertEquals(LocalDate.of(2026,2,17),dates.convert("LUNAR",2026,1,1,false,false));
        assertEquals(LocalDate.of(2026,11,13),dates.convert("LUNAR",2026,10,5,false,false));
        assertEquals(LocalDate.of(2023,3,22),dates.convert("LUNAR",2023,2,1,true,false));
    }
    @Test void todayRemainsCurrentAndTomorrowAdvancesAnnualDate() {
        var row=at("2026-11-01").decorate(event("SOLAR",2020,11,1,false,"YEARLY",0));
        assertEquals(0L,row.get("daysUntil"));assertEquals(true,row.get("reminderActive"));
        assertEquals(LocalDate.of(2027,11,1),at("2026-11-02").decorate(event("SOLAR",2020,11,1,false,"YEARLY",7)).get("nextOccurrenceDate"));
    }
    @Test void leapDayFallbackNeverMutatesOriginalAndFutureLeapYearRestores() {
        var row=event("SOLAR",2024,2,29,false,"YEARLY",0);
        assertEquals(LocalDate.of(2025,2,28),at("2025-02-01").decorate(row).get("nextOccurrenceDate"));assertEquals(29,row.get("eventDay"));
        assertEquals(LocalDate.of(2028,2,29),at("2028-02-01").decorate(row).get("nextOccurrenceDate"));
    }
    @Test void missingLeapMonthFallsBackToRegularMonth() {
        var dates=at("2024-02-11");var row=event("LUNAR",2023,2,1,true,"YEARLY",0);
        assertEquals(LocalDate.of(2024,3,10),dates.decorate(row).get("nextOccurrenceDate"));assertEquals(true,row.get("leapMonth"));
    }
    @Test void shortLunarMonthClampsOnlyOccurrence() {
        LifeDates dates=at("2026-02-17");
        int length=cn.hutool.core.date.chinese.LunarInfo.monthDays(2026,12);assertEquals(29,length);
        var row=event("LUNAR",2023,12,30,false,"YEARLY",0);
        assertEquals(dates.convert("LUNAR",2026,12,29,false,false),dates.decorate(row).get("nextOccurrenceDate"));assertEquals(30,row.get("eventDay"));
    }
    @Test void invalidOriginalDatesAreNotSilentlyClamped() {
        var dates=at("2026-01-01");
        assertThrows(BusinessException.class,()->dates.convert("SOLAR",2025,2,29,false,false));
        assertThrows(BusinessException.class,()->dates.convert("LUNAR",2026,12,30,false,false));
        assertThrows(BusinessException.class,()->dates.convert("LUNAR",2026,2,1,true,false));
    }
    @Test void startYearIsRespectedAndExpiredOneTimeIsRetained() {
        var dates=at("2026-10-28");
        assertEquals(LocalDate.of(2028,1,1),dates.decorate(event("SOLAR",2028,1,1,false,"YEARLY",7)).get("nextOccurrenceDate"));
        var expired=dates.decorate(event("SOLAR",2026,10,27,false,"NONE",30));assertEquals(-1L,expired.get("daysUntil"));assertEquals(false,expired.get("reminderActive"));assertEquals("EXPIRED",expired.get("occurrenceStatus"));
    }
    @Test void reminderWindowIsInclusiveAndCanBeDisabled() {
        var dates=at("2026-10-28");
        assertEquals(true,dates.decorate(event("SOLAR",2026,11,4,false,"NONE",7)).get("reminderActive"));
        assertEquals(false,dates.decorate(event("SOLAR",2026,11,5,false,"NONE",7)).get("reminderActive"));
        assertEquals(false,dates.decorate(event("SOLAR",2026,10,28,false,"NONE",null)).get("reminderActive"));
    }
    @Test void rangeBoundariesAndShanghaiClockAreExplicit() {
        var dates=at("2099-12-31");assertEquals("OUT_OF_RANGE",dates.decorate(event("SOLAR",2090,1,1,false,"YEARLY",7)).get("occurrenceStatus"));
        assertDoesNotThrow(()->dates.convert("LUNAR",1901,1,1,false,false));assertDoesNotThrow(()->dates.convert("LUNAR",2099,12,1,false,false));
        var clock=new LifeDates(Clock.fixed(Instant.parse("2026-10-28T16:30:00Z"),ZoneId.of("Asia/Shanghai")));assertEquals(LocalDate.of(2026,10,29),clock.today());
    }
    @Test void pickerMonthsExposeOnlyActualLeapMonthAndLengths() {
        var months=at("2023-01-01").months(2023);assertEquals(13,months.size());assertEquals(1,months.stream().filter(LifeDates.Month::leap).count());assertEquals(2,months.stream().filter(LifeDates.Month::leap).findFirst().orElseThrow().month());
        assertTrue(months.stream().allMatch(m->m.days()==29||m.days()==30));
    }
}
