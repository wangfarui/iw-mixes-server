package com.itwray.iw.life;

import cn.hutool.core.date.ChineseDate;
import cn.hutool.core.date.chinese.LunarInfo;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Original dates never change when a repeated occurrence falls back to a shorter month. */
@Component
public class LifeDates {
    private final Clock clock;
    public LifeDates() { this(Clock.system(ZoneId.of("Asia/Shanghai"))); }
    LifeDates(Clock clock) { this.clock=clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public record Month(int month, boolean leap, String label, int days) {}
    public List<Month> months(int year) {
        if(year<1901||year>2099) throw new BusinessException("日期年份支持1901–2099年");
        List<Month> result=new ArrayList<>();
        for(int month=1;month<=12;month++) {
            result.add(new Month(month,false,month+"月",LunarInfo.monthDays(year,month)));
            if(LunarInfo.leapMonth(year)==month) result.add(new Month(month,true,"闰"+month+"月",LunarInfo.leapDays(year)));
        }
        return result;
    }
    LocalDate convert(String calendar,int year,int month,int day,boolean leap,boolean fallback) {
        if(year<1901||year>2099||month<1||month>12||day<1) throw new BusinessException("日期超出支持范围");
        try {
            if("SOLAR".equals(calendar)) {
                if(leap) throw new BusinessException("公历不支持闰月");
                return LocalDate.of(year,month,fallback?Math.min(day,YearMonth.of(year,month).lengthOfMonth()):day);
            }
            if(!"LUNAR".equals(calendar)) throw new BusinessException("请选择公历或农历");
            boolean actualLeap=leap && LunarInfo.leapMonth(year)==month;
            if(leap&&!actualLeap&&!fallback) throw new BusinessException("所选年份不存在该闰月");
            int length=actualLeap?LunarInfo.leapDays(year):LunarInfo.monthDays(year,month);
            if(!fallback&&day>length) throw new BusinessException("所选农历日期不存在");
            ChineseDate converted=new ChineseDate(year,month,Math.min(day,length),actualLeap);
            return LocalDate.of(converted.getGregorianYear(),converted.getGregorianMonthBase1(),converted.getGregorianDay());
        } catch(DateTimeException ex) { throw new BusinessException("所选日期不存在"); }
    }
    public void validate(LifeRequests.ImportantDate input) {
        convert(input.getCalendarType(),input.getEventYear(),input.getEventMonth(),input.getEventDay(),Boolean.TRUE.equals(input.getLeapMonth()),false);
    }
    public Map<String,Object> decorate(Map<String,Object> row) {
        String calendar=(String)row.get("calendarType"),repeat=(String)row.get("repeatType");
        int year=((Number)row.get("eventYear")).intValue(),month=((Number)row.get("eventMonth")).intValue(),day=((Number)row.get("eventDay")).intValue();
        boolean leap=Boolean.TRUE.equals(row.get("leapMonth"))||(row.get("leapMonth") instanceof Number n&&n.intValue()==1);
        LocalDate original=convert(calendar,year,month,day,leap,false),today=today(),next=original;
        if("YEARLY".equals(repeat)) {
            int currentYear="LUNAR".equals(calendar)?new ChineseDate(today).getChineseYear():today.getYear();
            next=null;
            for(int candidate=Math.max(year,currentYear);candidate<=2099;candidate++) {
                LocalDate date=convert(calendar,candidate,month,day,leap,true);
                if(!date.isBefore(today)&&!date.isBefore(original)) { next=date;break; }
            }
        }
        Long days=next==null?null:ChronoUnit.DAYS.between(today,next);
        Number remind=(Number)row.get("remindDays");
        row.put("nextOccurrenceDate",next);row.put("daysUntil",days);
        row.put("reminderActive",days!=null&&remind!=null&&days>=0&&days<=remind.intValue());
        row.put("occurrenceStatus",next==null?"OUT_OF_RANGE":days<0?"EXPIRED":"UPCOMING");
        row.put("originalDateText",("LUNAR".equals(calendar)?"农历 ":"公历 ")+year+"年"+(leap?"闰":"")+month+"月"+day+"日");
        return row;
    }
}
