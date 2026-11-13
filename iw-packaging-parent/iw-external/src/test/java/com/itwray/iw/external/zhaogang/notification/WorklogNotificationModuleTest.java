package com.itwray.iw.external.zhaogang.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.calendar.*;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.*;
import com.itwray.iw.external.zhaogang.team.*;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.*;
import com.itwray.iw.external.zhaogang.worklog.WorklogDailyReader;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class WorklogNotificationModuleTest {
    private final NotificationStore store=mock(NotificationStore.class);
    private final WorkbenchTeamModule teams=mock(WorkbenchTeamModule.class);
    private final WorkCalendarResolver calendar=mock(WorkCalendarResolver.class);
    private final WorklogDailyReader reader=mock(WorklogDailyReader.class);
    private final WecomWebhook webhook=mock(WecomWebhook.class);
    private final WorklogNotificationModule module=new WorklogNotificationModule(store,teams,calendar,reader,new ObjectMapper().findAndRegisterModules(),webhook);
    private final LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final NotificationStore.Config config=new NotificationStore.Config(1,10,true,"cipher","hash",1);
    @AfterEach void close() { module.shutdown(); }
    private CodeSyncTeam team() { return new CodeSyncTeam("团队","key","host",List.of(new CodeSyncMember(100,"张三","",null))); }
    private NotificationStore.Batch batch(long expires) { return new NotificationStore.Batch("batch",1,10,"DAILY",1,"hash","100","READY","",2,System.currentTimeMillis(),expires); }
    private void workday() { when(calendar.resolve(eq(10L),any())).thenReturn(new Schedule(today.toString().substring(0,7),1,List.of(new Day(today,DayType.WORKDAY,true)))); }
    @Test void usesWorkbenchCalendarIncludingOverrideInsteadOfWeekdayHeuristics() throws Exception {
        when(store.enabled()).thenReturn(List.of(config));
        when(calendar.resolve(eq(10L),any())).thenReturn(new Schedule("month",1,List.of(new Day(today,DayType.REST_DAY,true))));
        module.daily(); verify(store,never()).create(any(),anyString(),anyString(),anyString(),anyLong());
        workday(); when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        when(store.create(eq(config),anyString(),eq("DAILY"),eq("100"),anyLong())).thenReturn("batch");
        when(reader.read(10,team(),today)).thenReturn(new WorklogDailyReader.Snapshot("团队",today,"17:00",List.of(new WorklogDailyReader.Member(100,"张三",false,true,"",List.of()))));
        module.daily(); verify(store).ready(eq("batch"),anyString(),argThat(list->list.size()==2 && list.get(0).contains("今日工时报告") && list.get(1).contains("张三")));
    }
    @Test void cancelsChangedConfigAndRemovedMembersBeforeClaiming() {
        var b=batch(System.currentTimeMillis()+600_000); when(store.pending()).thenReturn(List.of(b));
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,true,"cipher","hash",2));
        module.deliver(); verify(store).stop("batch","CANCELLED","通知配置已停用或更新");
        when(store.config(1)).thenReturn(config); when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(new CodeSyncTeam("team","key","host",List.of())));
        module.deliver(); verify(store).stop("batch","CANCELLED","团队已解散或成员发生变化");
        verify(store,never()).claim(any(),anyLong()); verifyNoInteractions(webhook);
    }
    @Test void expiresOldBatchesAndSubmitsOnlyClaimedMessage() {
        when(store.pending()).thenReturn(List.of(batch(0))); module.deliver(); verify(store).stop("batch","EXPIRED","已超过当日发送窗口");
        var b=batch(System.currentTimeMillis()+600_000); when(store.pending()).thenReturn(List.of(b)); when(store.config(1)).thenReturn(config);
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team())); workday();
        var delivery=new NotificationStore.Delivery(b,0,"summary",1); when(store.claim(eq(b),anyLong())).thenReturn(delivery);
        var result=new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"accepted"); when(webhook.send("cipher","summary")).thenReturn(result);
        module.deliver(); verify(webhook).send("cipher","summary"); verify(store).complete(delivery,result);
    }
    @Test void deniesNotificationAccessToOrdinaryMember() {
        var actor=new Actor(200,"member","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(false,false,false,false,true,false)); when(teams.detail(actor,1)).thenReturn(detail);
        assertThatThrownBy(()->module.status(actor,1)).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.save(actor,1,new WorklogNotificationModule.Command(false,0,null,false))).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.test(actor,1,0)).hasMessageContaining("只有团队管理员");
        assertThatThrownBy(()->module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(0,"request-0001"))).hasMessageContaining("只有团队管理员");
        verifyNoInteractions(store,webhook);
    }
    @Test void connectionTestQueuesExactlyOneMessageUsingSavedConfigWithoutEnabling() {
        var actor=new Actor(100,"admin","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(true,true,true,true,true,true)); when(teams.detail(actor,1)).thenReturn(detail);
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(store.createTest(any(),eq(""),eq(1L),eq(100L))).thenReturn("test-batch");
        var status=module.test(actor,1,1);
        assertThat(status.enabled()).isFalse();
        verify(store).ready(eq("test-batch"),eq("{}"),argThat(messages->messages.size()==1 && messages.get(0).contains("连接测试")));
        verify(store,never()).save(anyLong(),anyLong(),anyLong(),any(),any());
        verifyNoInteractions(webhook);
    }

    private Actor manualAdmin() {
        var actor=new Actor(100,"admin","",10,"key","host");
        var detail=mock(TeamDetail.class); when(detail.permissions()).thenReturn(new Permissions(true,true,true,true,true,true)); when(teams.detail(actor,1)).thenReturn(detail);
        when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        return actor;
    }
    @Test void manualReportReturnsWhileCollectingAndQueuesFreshFullReportWithAutoNotificationOff() throws Exception {
        var actor=manualAdmin();
        when(store.createManual(any(),eq("100"),eq(1L),eq(100L),eq("request-0001")))
                .thenReturn(new NotificationStore.ManualBatch("manual-batch",true));
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(reader.read(10,team(),today)).thenAnswer(call -> {
            started.countDown();
            if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new WorklogDailyReader.Snapshot("团队",today,"17:24",List.of(new WorklogDailyReader.Member(100,"张三",false,true,"",List.of())));
        });
        try {
            var result=module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
            assertThat(result.enabled()).isFalse();
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            verify(store,never()).ready(anyString(),anyString(),anyList());
            release.countDown();
            verify(store,timeout(2000)).ready(eq("manual-batch"),anyString(),argThat(messages -> messages.size()==2 && messages.get(0).contains("今日工时报告") && messages.get(1).contains("张三")));
            verifyNoInteractions(calendar,webhook); // Manual reports do not depend on workday/17:00 or send in the HTTP request.
        } finally {release.countDown();}
    }
    @Test void retryOfSameManualRequestNeverCollectsOrEnqueuesAgain() {
        var actor=manualAdmin();
        when(store.createManual(any(),anyString(),anyLong(),anyLong(),anyString())).thenReturn(new NotificationStore.ManualBatch("existing",false));
        module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
        verifyNoInteractions(reader,calendar,webhook);
        verify(store,never()).ready(anyString(),anyString(),anyList());
    }
    @Test void manualCollectionFailureIsRecordedWithoutSendingPartialBatch() {
        var actor=manualAdmin();
        when(store.createManual(any(),anyString(),anyLong(),anyLong(),anyString())).thenReturn(new NotificationStore.ManualBatch("manual-failed",true));
        when(reader.read(10,team(),today)).thenThrow(new IllegalStateException("fixture failure"));
        module.sendNow(actor,1,new WorklogNotificationModule.SendNowCommand(1,"request-0001"));
        verify(store,timeout(2000)).stop("manual-failed","FAILED","当日工时采集未完成，未发送报告");
        verify(store,never()).ready(anyString(),anyString(),anyList()); verifyNoInteractions(webhook);
    }
    @Test void manualDeliveryIgnoresAutomaticSwitchAndCalendarButStillChecksMembers() {
        var b=new NotificationStore.Batch("manual",1,10,"MANUAL",1,"hash","100","READY","",2,System.currentTimeMillis(),System.currentTimeMillis()+1_800_000);
        when(store.pending()).thenReturn(List.of(b)); when(store.config(1)).thenReturn(new NotificationStore.Config(1,10,false,"cipher","hash",1));
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(team()));
        var delivery=new NotificationStore.Delivery(b,0,"report",1); when(store.claim(eq(b),anyLong())).thenReturn(delivery);
        var accepted=new WecomWebhook.Result(WecomWebhook.Outcome.ACCEPTED,"ok");when(webhook.send("cipher","report")).thenReturn(accepted);
        module.deliver(); verify(store).complete(delivery,accepted);verifyNoInteractions(calendar);
        when(teams.findCodeSyncTeam(10,1)).thenReturn(Optional.of(new CodeSyncTeam("team","key","host",List.of())));
        module.deliver();verify(store).stop("manual","CANCELLED","团队已解散或成员发生变化");
    }

}
