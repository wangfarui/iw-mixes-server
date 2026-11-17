package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorklogDailyReaderTest {
    private final LocalDate date=LocalDate.of(2026,11,13);
    private Worklog log(long id,long issue,long code,long user,int days,String hours) {
        long start=date.plusDays(days).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        return new Worklog(id,issue,code,"project",user,new BigDecimal(hours),"登记说明不是事项名称",start,start,start);
    }
    @Test void reusesOwnPatPaginationStartAtDeduplicationAndDeletionRules() {
        var coding=mock(CodingOpenApiPort.class); var deleted=mock(WorklogDeletedIssues.class);
        var credentials=mock(CodingCredentialService.class); var leaves=mock(PersonalLeaveResolver.class);
        when(credentials.tokens(eq(10L),anyList())).thenReturn(Map.of(1L,"pat-one",2L,"pat-two"));
        when(leaves.resolve(eq(10L),anyList(),any())).thenReturn(Map.of(2L,Set.of(date)));
        var one=log(1,11,101,1,0,"2"); var two=log(2,11,101,1,0,"1");
        var missingTime=new Worklog(5,11,101,"project",1,BigDecimal.ONE,"",null,one.createdAt(),one.createdAt());
        when(coding.worklogPage(eq("pat-one"),anyLong(),anyLong(),eq(1L),eq(0),eq(1000)))
                .thenReturn(new WorklogPage(List.of(one,one,two,log(3,12,102,1,-1,"8"),log(4,13,103,1,0,"7"),missingTime)));
        when(coding.worklogPage(eq("pat-two"),anyLong(),anyLong(),eq(2L),eq(0),eq(1000))).thenReturn(new WorklogPage(List.of()));
        when(coding.issue("pat-one","project",103)).thenThrow(new CodingOpenApiException("DescribeIssue","InvalidParameterValue","issue_not_exist"));
        when(coding.issue("pat-one","project",101)).thenReturn(new Issue(101,"SUB_TASK","子任务",1,"真实工时任务","项目",true,11,"","",BigDecimal.ZERO,null,100L,"project",null,null,null,null,null,null));
        when(coding.issue("pat-one","project",100)).thenThrow(new CodingOpenApiException("parent denied"));
        var query=new WorklogQueryService(coding,mock(CodingIssueLinkBuilder.class),Clock.systemUTC(),deleted,mock(WorklogIssueChecks.class));
        try(var loader=new CodingIssueSnapshotLoader(coding,new ZhaogangProperties())) {
            var reader=new WorklogDailyReader(query,credentials,loader,deleted,leaves,coding);
            var team = new CodeSyncTeam("团队", "team", "team.invalid", List.of(
                    new CodeSyncMember(1, "张三", "", null),
                    new CodeSyncMember(2, "李四", "", null),
                    new CodeSyncMember(3, "王五", "", null),
                    new CodeSyncMember(4, "忽略成员", "", null, "13800000004", false, true)));
            var snapshot = reader.read(10, team, date);
            assertThat(snapshot.members()).extracting(WorklogDailyReader.Member::userId).containsExactly(1L, 2L, 3L);
            verify(credentials).tokens(10L, List.of(1L, 2L, 3L));
            var member=snapshot.members().get(0);
            assertThat(snapshot.collectedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
            assertThat(member.entries()).allSatisfy(e -> {
                assertThat(e.projectDisplayName()).isEqualTo("项目");
                assertThat(e.issueType()).isEqualTo("子工作项");
            });
            assertThat(member.hours()).isEqualByComparingTo("3"); assertThat(member.complete()).isFalse();
            assertThat(member.entries()).hasSize(2).allSatisfy(e->{assertThat(e.title()).isEqualTo("真实工时任务"); assertThat(e.parents()).contains("上级 #100 信息暂未获取");});
            assertThat(snapshot.members().get(1).leave()).isTrue(); assertThat(snapshot.members().get(1).complete()).isTrue();
            assertThat(snapshot.members().get(2).complete()).isFalse(); assertThat(snapshot.members().get(2).warning()).contains("尚未绑定");
            verify(deleted).record(eq(10L),argThat(map->map.keySet().equals(Set.of(13L))));
            verify(coding,never()).issue(anyString(),eq("project"),eq(102L));
        }
    }
    @Test void resolvesMissingProjectDisplayNameOncePerMemberAndKeepsRealParentTypesInOrder() {
        var coding=mock(CodingOpenApiPort.class); var query=mock(WorklogQueryService.class);
        var credentials=mock(CodingCredentialService.class); var deleted=mock(WorklogDeletedIssues.class);
        var leaves=mock(PersonalLeaveResolver.class);
        when(credentials.tokens(eq(10L),anyList())).thenReturn(Map.of(1L,"own-token"));
        when(leaves.resolve(anyLong(),anyList(),any())).thenReturn(Map.of());
        when(query.dailyRows(any(),eq(date))).thenReturn(new WorklogQueryService.DailyRows(List.of(log(1,11,101,1,0,"2"),log(2,11,101,1,0,"1")),true,""));
        when(coding.projectDisplayNames("own-token",1)).thenReturn(Map.of("project","基础服务阿米巴"));
        when(coding.issue("own-token","project",101)).thenReturn(new Issue(101,"SUB_TASK","子任务",1,"子项","project",true,11,"","",BigDecimal.ZERO,null,100L,"project",null,null,null,null,null,null));
        when(coding.issue("own-token","project",100)).thenReturn(new Issue(100,"REQUIREMENT","用户故事",1,"故事","project",true,10,"","",BigDecimal.ZERO,null,99L,"project",null,null,null,null,null,null));
        when(coding.issue("own-token","project",99)).thenReturn(new Issue(99,"REQUIREMENT","需求",1,"需求标题","project",false,9,"","",BigDecimal.ZERO));
        try(var loader=new CodingIssueSnapshotLoader(coding,new ZhaogangProperties())) {
            var reader=new WorklogDailyReader(query,credentials,loader,deleted,leaves,coding);
            var team=new CodeSyncTeam("团队","key","host",List.of(new CodeSyncMember(1,"步步(王发瑞)","",null)));
            var member=reader.read(10,team,date).members().get(0);
            assertThat(member.complete()).isTrue();
            assertThat(member.entries()).hasSize(2).allSatisfy(e -> {
                assertThat(e.projectDisplayName()).isEqualTo("基础服务阿米巴");
                assertThat(e.parents()).containsExactly("需求：#99 需求标题","用户故事：#100 故事");
            });
            verify(coding,times(1)).projectDisplayNames("own-token",1);
            when(coding.projectDisplayNames("own-token",1)).thenThrow(new CodingOpenApiException("network"));
            var failedName=reader.read(10,team,date).members().get(0);
            assertThat(failedName.complete()).isTrue();
            assertThat(failedName.hours()).isEqualByComparingTo("3");
            assertThat(failedName.entries()).allSatisfy(e -> assertThat(e.projectDisplayName()).isEqualTo("项目名称暂未获取"));
        }
    }
    @Test void usesExplicitTypesRatherThanInferringEveryChildIsASubtask() {
        assertThat(WorklogDailyReader.issueType(new Issue(1,"REQUIREMENT","用户故事","故事","项目",true))).isEqualTo("用户故事");
        assertThat(WorklogDailyReader.issueType(new Issue(1,"REQUIREMENT","","需求","项目",true))).isEqualTo("需求");
        assertThat(WorklogDailyReader.issueType(new Issue(1,"BUG","","缺陷","项目",true))).isEqualTo("缺陷");
        assertThat(WorklogDailyReader.issueType(new Issue(1,"MISSION","自定义事项","事项","项目",true))).isEqualTo("自定义事项");
    }
    @Test void parentFailureKeepsVerifiedHoursCompleteAndCrossMemberConflictNeverPersistsDeletion() {
        var query=mock(WorklogQueryService.class); var deleted=mock(WorklogDeletedIssues.class);
        var credentials=mock(CodingCredentialService.class); var leaves=mock(PersonalLeaveResolver.class);
        when(credentials.tokens(eq(10L),anyList())).thenReturn(Map.of(1L,"a",2L,"b")); when(leaves.resolve(anyLong(),anyList(),any())).thenReturn(Map.of());
        when(query.dailyRows(any(),eq(date))).thenAnswer(call->{WorklogModule.MemberCredential c=call.getArgument(0); return new WorklogQueryService.DailyRows(List.of(log(c.userId(),11,101,c.userId(),0,"2")),true,"");});
        var coding=mock(CodingOpenApiPort.class);
        when(coding.issue("a","project",101)).thenThrow(new CodingOpenApiException("DescribeIssue","InvalidParameterValue","issue_not_exist"));
        when(coding.issue("b","project",101)).thenReturn(new Issue(101,"SUB_TASK","子任务",1,"任务","项目",true,11,"","",BigDecimal.ZERO,null,100L,"project",null,null,null,null,null,null));
        when(coding.issue("b","project",100)).thenThrow(new IllegalStateException("network"));
        try(var loader=new CodingIssueSnapshotLoader(coding,new ZhaogangProperties())) {
            var snapshot=new WorklogDailyReader(query,credentials,loader,deleted,leaves,coding).read(10,new CodeSyncTeam("team","key","host",List.of(new CodeSyncMember(1,"a","",null),new CodeSyncMember(2,"b","",null))),date);
            assertThat(snapshot.members().get(0).complete()).isFalse(); assertThat(snapshot.members().get(0).entries()).hasSize(1);
            assertThat(snapshot.members().get(1).complete()).isTrue(); assertThat(snapshot.members().get(1).hours()).isEqualByComparingTo("2");
            verify(deleted).record(eq(10L),argThat(Map::isEmpty));
        }
    }
}
