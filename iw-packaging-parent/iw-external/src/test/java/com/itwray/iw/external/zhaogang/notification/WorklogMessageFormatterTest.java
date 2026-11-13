package com.itwray.iw.external.zhaogang.notification;

import com.itwray.iw.external.zhaogang.worklog.WorklogDailyReader.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorklogMessageFormatterTest {
    private Entry entry(long id,String project,long code,String title,String hours) {
        return new Entry(id,project,code,title,List.of("需求：#1001 优惠券管理"),new BigDecimal(hours),project,"子工作项");
    }
    @Test void reportsAllMembersIncludingLeaveAndFailuresWithoutFalseZeroOrShortfall() {
        var snapshot=new Snapshot("研发团队",LocalDate.of(2026,11,13),"2026-11-13 17:00:23",List.of(
                new Member(1,"张三",false,true,"",List.of(entry(1,"业务平台",1002,"接口开发","2"),entry(2,"业务平台",1002,"接口开发","1"),entry(3,"业务平台",1011,"索引优化","3"))),
                new Member(2,"李四",false,true,"",List.of()),
                new Member(3,"赵六",false,true,"",List.of(entry(4,"业务平台",1002,"接口开发","8"))),
                new Member(4,"钱七",true,false,"成员未绑定令牌",List.of()),
                new Member(5,"王五",false,false,"与 CODING 通信失败",List.of())));
        var messages=new WorklogMessageFormatter().format(snapshot);
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0)).startsWith("研发团队 ｜ 2026-11-13 ｜ 今日工时报告汇总\n").doesNotContain("合计", "仅代表已获取数据", "[Asia/Shanghai]").contains("应登记：4人","已达8h：1人","已登记但不足8h：1人","未登记：1人","数据异常：1人","请假：1人","已登记但不足8h：1人（张三）","未登记：1人（李四）","数据异常：1人（王五）","请假：1人（钱七）");
        assertThat(messages.get(1)).startsWith("研发团队 ｜ 2026-11-13 ｜ 张三\n").doesNotContain("数据更新", "今日工时").contains("张三","距8h还差2h","子工作项：#1002","登记工时：2h、1h","登记工时：3h");
        assertThat(messages.get(2)).contains("今日暂无工时登记","还差8h");
        assertThat(messages.get(3)).contains("已达8h");
        assertThat(messages.get(4)).contains("今日请假，不计不足","数据不完整").doesNotContain("还差");
        assertThat(messages.get(5)).contains("数据未获取，无法判断").doesNotContain("0h","还差");
    }
    @Test void hidesZeroCategoriesAndUsesTheAgreedHierarchyAndBlankLines() {
        var entries = List.of(
                new Entry(1,"yuncunzheng",8733,"冲突分析与修复", List.of("需求：#8165 在途销售更换仓库优化", "用户故事：#8494 在途销售相关需求功能开发"), new BigDecimal("2"),"基础服务阿米巴","子工作项"),
                new Entry(2,"yuncunzheng",8733,"冲突分析与修复", List.of(), new BigDecimal("1"),"基础服务阿米巴","子工作项"),
                new Entry(3,"yuncunzheng",9000,"接口验证", List.of("任务：#8800 版本验证"), new BigDecimal("5"),"基础服务阿米巴","子工作项"));
        var messages = new WorklogMessageFormatter().format(new Snapshot("基础服务组", LocalDate.of(2026,11,13), "2026-11-13 17:39:21", List.of(new Member(1,"步步(王发瑞)",false,true,"",entries))));
        assertThat(messages.get(0)).isEqualTo("""
                基础服务组 ｜ 2026-11-13 ｜ 今日工时报告汇总

                团队成员：1人；应登记：1人
                已达8h：1人

                接下来逐人发送今日登记明细。
                本报告为采集时的登记进度，非最终缺勤结果。
                数据更新：2026-11-13 17:39:21""");
        assertThat(messages.get(1)).isEqualTo("""
                基础服务组 ｜ 2026-11-13 ｜ 步步(王发瑞)

                已登记8h，已达8h
                \u200B
                项目：基础服务阿米巴
                需求：＃8165 在途销售更换仓库优化
                用户故事：＃8494 在途销售相关需求功能开发
                子工作项：#8733 冲突分析与修复
                登记工时：2h、1h
                \u200B
                项目：基础服务阿米巴
                任务：＃8800 版本验证
                子工作项：#9000 接口验证
                登记工时：5h
                """);
    }
    @Test void sameDisplayNameAndCodeAcrossDifferentProjectsNeverMergeAndNamesAreEscaped() {
        var a=new Entry(1,"project-a",1,"任务A",List.of(),BigDecimal.ONE,"同名项目","子工作项");
        var b=new Entry(2,"project-b",1,"任务B",List.of(),BigDecimal.TEN,"同名项目","子工作项");
        var messages=new WorklogMessageFormatter().format(new Snapshot("团队",LocalDate.of(2026,11,13),"2026-11-13 17:00:00",List.of(
                new Member(1,"甲<@all>\n冒充标题",false,true,"",List.of(a)),new Member(2,"乙",false,true,"",List.of(a,b)))));
        assertThat(messages.get(0)).contains("已登记但不足8h：1人（甲＜＠all＞ 冒充标题）").doesNotContain("<@all>");
        assertThat(messages.get(2)).contains("任务A\n登记工时：1h\n\u200B\n项目：同名项目", "任务B\n登记工时：10h").doesNotContain("1h、10h");
    }
    @Test void splitsUtf8IncludingHeadersWithoutDroppingAnyTextOrMixingMembers() {
        String title="中文🙂<@all>_[x](url)".repeat(800);
        var snapshot=new Snapshot("团队",LocalDate.of(2026,11,13),"17:00",List.of(
                new Member(1,"张三",false,true,"",List.of(entry(1,"a",1,title,"1"))),
                new Member(2,"李四",false,true,"",List.of())));
        var messages=new WorklogMessageFormatter().format(snapshot);
        assertThat(messages.size()).isGreaterThan(3);
        messages.forEach(m -> assertThat(WorklogMessageFormatter.bytes(m)).isLessThanOrEqualTo(4096));
        assertThat(messages.get(1)).contains("张三", "张三（1/");
        assertThat(messages.get(messages.size()-1)).contains("李四");
        String recovered=messages.subList(1,messages.size()-1).stream().map(m->m.substring(m.indexOf('\n',m.indexOf('\n')+1)+1)).reduce("",String::concat);
        assertThat(recovered).contains(WorklogMessageFormatter.safe(title)).contains("登记工时：1h");
        assertThat(recovered).doesNotContain("<@all>");
    }
    @Test void preservesLongNamesAndPartialHoursWithoutTargetJudgement() {
        String name="长名🙂".repeat(500);
        var messages=new WorklogMessageFormatter().format(new Snapshot(name,LocalDate.now(),"17:00",List.of(
                new Member(1,name,false,false,"记录缺少登记时间",List.of(entry(1,"a",1,"任务","2"))))));
        messages.forEach(m->assertThat(WorklogMessageFormatter.bytes(m)).isLessThanOrEqualTo(4096));
        assertThat(String.join("",messages)).contains("数据不完整，暂不判断是否达到8h").doesNotContain("还差6h");
    }
}
