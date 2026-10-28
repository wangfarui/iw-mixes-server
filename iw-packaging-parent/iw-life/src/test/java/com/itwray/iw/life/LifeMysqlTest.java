package com.itwray.iw.life;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.itwray.iw.life.LifeStore.Kind.*;

/** Opt-in local MySQL integration. Only LIFE-AC fixtures in the three new tables, rolled back. */
@EnabledIfEnvironmentVariable(named="IW_LIFE_TEST_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iw_(?:mixes|life_test)(?:\\?.*)?")
class LifeMysqlTest {
    static LifeToolsService service;static JdbcTemplate jdbc;static TransactionTemplate tx;
    static final int OWNER=190028001,OTHER=190028002;
    @BeforeAll static void setup() {
        var ds=new DriverManagerDataSource(System.getenv("IW_LIFE_TEST_JDBC"),System.getenv("IW_LIFE_TEST_USER"),System.getenv("IW_LIFE_TEST_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        service=new LifeToolsService(new LifeStore(jdbc),new LifeDates(),new ObjectMapper().findAndRegisterModules());
    }
    @BeforeEach void owner(){UserUtils.setUserId(OWNER);}
    @AfterEach void clear(){UserUtils.removeUserId();}
    private void rollback(Runnable test){tx.executeWithoutResult(status->{try{test.run();}finally{status.setRollbackOnly();}});}
    private LifeRequests.Wish wish(String id){var f=new LifeRequests.Wish();f.setTitle("LIFE-AC-20261028-"+id);f.setClientRequestId(UUID.randomUUID().toString());return f;}
    private LifeRequests.Bookmark bookmark(){var f=new LifeRequests.Bookmark();f.setTitle("LIFE-AC-20261028-bookmark");f.setClientRequestId(UUID.randomUUID().toString());f.setUrl("https://example.com/p?q=1#note");return f;}
    private int id(Map<String,Object> result){return ((Number)result.get("id")).intValue();}
    @Test void idempotencySurvivesEditsAndRejectsChangedPayloadAndDeletedRetry(){rollback(()->{
        var f=wish("retry");int id=id(service.add(WISH,f));assertEquals(id,id(service.add(WISH,f)));
        var changed=wish("edit");changed.setId(id);changed.setRevision(1);service.update(WISH,changed);
        assertEquals(id,id(service.add(WISH,f)));f.setTitle("LIFE-AC-20261028-different");assertThrows(BusinessException.class,()->service.add(WISH,f));
        f.setTitle("LIFE-AC-20261028-retry");service.delete(WISH,id,2);assertThrows(BusinessException.class,()->service.add(WISH,f));
    });}
    @Test void everyReadAndMutationIsOwnerScopedEvenWithKnownIds(){rollback(()->{
        int w=id(service.add(WISH,wish("private"))),b=id(service.add(BOOKMARK,bookmark()));
        UserUtils.setUserId(OTHER);
        assertEquals(0,service.page(WISH,new LifeRequests.Page()).get("total"));
        assertThrows(BusinessException.class,()->service.detail(WISH,w));assertThrows(BusinessException.class,()->service.delete(WISH,w,1));
        var patch=wish("forged");patch.setId(w);patch.setRevision(1);assertThrows(BusinessException.class,()->service.update(WISH,patch));
        assertThrows(BusinessException.class,()->service.changeStatus(new LifeRequests.State(w,1,"FULFILL")));assertThrows(BusinessException.class,()->service.pin(new LifeRequests.Pin(b,1,true)));
        UserUtils.setUserId(OWNER);assertEquals(1,service.detail(WISH,w).get("revision"));
    });}
    @Test void nullableFieldsReallyClearAndZeroAmountIsPreserved(){rollback(()->{
        var f=wish("nullable");f.setEstimatedAmount(BigDecimal.ZERO);f.setTargetDate(LocalDate.now());f.setReferenceUrl("https://example.com");int id=id(service.add(WISH,f));
        assertEquals(new BigDecimal("0.00"),service.detail(WISH,id).get("estimatedAmount"));
        f.setId(id);f.setRevision(1);f.setEstimatedAmount(null);f.setTargetDate(null);f.setReferenceUrl(null);service.update(WISH,f);
        var row=service.detail(WISH,id);assertNull(row.get("estimatedAmount"));assertNull(row.get("targetDate"));assertNull(row.get("referenceUrl"));
    });}
    @Test void wishTransitionsClearCompletionAndRejectStaleVersions(){rollback(()->{
        int id=id(service.add(WISH,wish("states")));var done=service.changeStatus(new LifeRequests.State(id,1,"FULFILL"));assertNotNull(done.get("fulfilledAt"));
        assertThrows(BusinessException.class,()->service.changeStatus(new LifeRequests.State(id,1,"RESTORE")));
        var restored=service.changeStatus(new LifeRequests.State(id,2,"RESTORE"));assertNull(restored.get("fulfilledAt"));assertEquals("PENDING",restored.get("status"));
        service.changeStatus(new LifeRequests.State(id,3,"ARCHIVE"));assertEquals("ARCHIVED",service.detail(WISH,id).get("status"));
    });}
    @Test void linksPreserveQueryAndFragmentAndNeverExposeInternalHashes(){rollback(()->{
        var f=bookmark();int id=id(service.add(BOOKMARK,f));var row=service.detail(BOOKMARK,id);assertEquals(f.getUrl(),row.get("url"));assertFalse(row.containsKey("createHash"));assertFalse(row.containsKey("clientRequestId"));
        service.pin(new LifeRequests.Pin(id,1,true));assertEquals(true,service.detail(BOOKMARK,id).get("pinned"));
        f.setUrl("javascript:alert(1)");assertThrows(BusinessException.class,()->service.add(BOOKMARK,f));f.setUrl("https://u:p@example.com");assertThrows(BusinessException.class,()->service.add(BOOKMARK,f));
    });}
    @Test void bookmarksCanBeNameOnlyAndExistingLinksCanBeCleared(){rollback(()->{
        var f=bookmark();f.setUrl(null);int id=id(service.add(BOOKMARK,f));
        assertNull(service.detail(BOOKMARK,id).get("url"));
        service.pin(new LifeRequests.Pin(id,1,true));assertEquals(true,service.detail(BOOKMARK,id).get("pinned"));
        f.setId(id);f.setRevision(2);f.setUrl("https://example.com/saved");service.update(BOOKMARK,f);
        assertEquals(f.getUrl(),service.detail(BOOKMARK,id).get("url"));
        f.setRevision(3);f.setUrl("   ");service.update(BOOKMARK,f);
        assertNull(service.detail(BOOKMARK,id).get("url"));
        var query=new LifeRequests.Page();query.setKeyword(f.getTitle());
        assertEquals(1,service.page(BOOKMARK,query).get("total"));
    });}
    @Test void datesSortAfterConversionAndRemindersUseSameCalculation(){rollback(()->{
        var today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        for(int days:List.of(20,0,10)){
            var d=today.plusDays(days);var f=new LifeRequests.ImportantDate();f.setTitle("LIFE-AC-20261028-date"+days);f.setClientRequestId(UUID.randomUUID().toString());f.setCalendarType("SOLAR");f.setEventYear(d.getYear());f.setEventMonth(d.getMonthValue());f.setEventDay(d.getDayOfMonth());f.setRepeatType("NONE");f.setRemindDays(0);service.add(DATE,f);
        }
        var q=new LifeRequests.Page();q.setPageSize(1);var result=service.page(DATE,q);
        var first=(Map<?,?>)((List<?>)result.get("records")).get(0);assertEquals(0L,first.get("daysUntil"));assertEquals(3,result.get("total"));assertEquals(1L,service.reminders().get("activeReminderCount"));
    });}
    @Test void concurrentSameRevisionHasOneWinner()throws Exception{
        int id=tx.execute(s->id(service.add(WISH,wish("concurrent"))));var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var wins=new AtomicInteger();
        try{
            List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{UserUtils.setUserId(OWNER);try{gate.await();tx.executeWithoutResult(s->{try{service.changeStatus(new LifeRequests.State(id,1,"FULFILL"));wins.incrementAndGet();}catch(BusinessException expected){s.setRollbackOnly();}});}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}finally{UserUtils.removeUserId();}}));
            gate.countDown();for(var job:jobs)job.get(15,TimeUnit.SECONDS);assertEquals(1,wins.get());
        }finally{pool.shutdownNow();jdbc.update("DELETE FROM life_wish WHERE id=? AND user_id=? AND title LIKE 'LIFE-AC-20261028-%'",id,OWNER);}
    }
}
