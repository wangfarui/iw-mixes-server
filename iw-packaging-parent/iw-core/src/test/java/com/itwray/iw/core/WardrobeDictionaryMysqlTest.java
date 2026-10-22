package com.itwray.iw.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.auth.mapper.WardrobeDictionaryMapper;
import com.itwray.iw.auth.service.impl.WardrobeDictionaryService;
import com.itwray.iw.auth.service.impl.WardrobeDictionaryService.*;
import com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper;
import com.itwray.iw.wardrobe.dictionary.WardrobeSnapshotService;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, actual MySQL only. The isolated fixture is never an application or production database. */
@EnabledIfEnvironmentVariable(named="IW_WARDROBE_TEST_JDBC", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iw_wardrobe_test(?:\\?.*)?")
class WardrobeDictionaryMysqlTest {
    private static WardrobeDictionaryService service;
    private static WardrobeSnapshotService snapshots;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static final ObjectMapper JSON = new ObjectMapper();
    @BeforeAll
    static void connect() throws Exception {
        String url = System.getenv("IW_WARDROBE_TEST_JDBC");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iw_wardrobe_test(?:\\?.*)?"));
        DriverManagerDataSource data = new DriverManagerDataSource(url,
                Objects.requireNonNullElse(System.getenv("IW_WARDROBE_TEST_USER"), "root"),
                Objects.requireNonNullElse(System.getenv("IW_WARDROBE_TEST_PASSWORD"), ""));
        jdbc = new JdbcTemplate(data); transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
        Configuration config = new Configuration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(WardrobeDictionaryMapper.class); config.addMapper(WardrobeSnapshotMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(data); factory.setConfiguration(config); factory.afterPropertiesSet();
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        service = new WardrobeDictionaryService(session.getMapper(WardrobeDictionaryMapper.class));
        snapshots = new WardrobeSnapshotService(service, session.getMapper(WardrobeSnapshotMapper.class));
        assertEquals("READY", jdbc.queryForObject("SELECT phase FROM wardrobe_dictionary_migration_state WHERE id=1", String.class));
    }
    private void rollback(Runnable test) { transactions.executeWithoutResult(status -> { try { test.run(); } finally { status.setRollbackOnly(); } }); }
    private Management personal(int owner, Type type) { return service.items(owner, type, Scope.PERSONAL, null); }
    private Item category(int owner) { return personal(owner, Type.CATEGORY).items().stream().filter(i -> i.matchKind() == 1).findFirst().orElseThrow(); }
    private Item createCategory(int owner, String name) { return service.create(owner, Type.CATEGORY, Scope.PERSONAL, name, null, null, 1, personal(owner,Type.CATEGORY).scopeVersion(), null).item(); }

    @Test void renameVersionsParentAtomicallyAndLeavesBusinessNameFrozen() { rollback(() -> {
        Item old = category(11001); String saved = jdbc.queryForObject("SELECT category_name_snapshot FROM wardrobe_item WHERE id=13001", String.class);
        var renamed = service.rename(11001, old.id(), "测试改名上装", old.revision(), personal(11001,Type.CATEGORY).scopeVersion(), null);
        assertNotEquals(old.id(),renamed.item().id());
        assertEquals(old.id(),jdbc.queryForObject("SELECT previous_id FROM base_dict WHERE id=?",Integer.class,renamed.item().id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM base_dict WHERE parent_id=? AND dict_type=5006 AND deleted=0",Integer.class,old.id()));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM base_dict WHERE parent_id=? AND dict_type=5006 AND deleted=0",Integer.class,renamed.item().id())>0);
        assertEquals(saved,jdbc.queryForObject("SELECT category_name_snapshot FROM wardrobe_item WHERE id=13001",String.class));
        assertEquals(old.categoryLineageId(),renamed.item().categoryLineageId());
    }); }
    @Test void familyRolesCannotBeForgedByKnowingAnId() { rollback(() -> {
        Management family = service.items(11004,Type.CATEGORY,Scope.FAMILY,null);
        assertFalse(family.canManage()); assertFalse(service.items(11003,Type.CATEGORY,Scope.FAMILY,null).canManage());
        assertTrue(service.items(11002,Type.CATEGORY,Scope.FAMILY,null).canManage());
        assertThrows(RuntimeException.class,()->service.create(11004,Type.CATEGORY,Scope.FAMILY,"伪造写入",null,null,1,family.scopeVersion(),12001));
        Item another = category(11002);
        assertThrows(RuntimeException.class,()->service.rename(11001,another.id(),"别人的项",another.revision(),personal(11001,Type.CATEGORY).scopeVersion(),null));
        assertThrows(RuntimeException.class,()->service.create(11001,Type.CATEGORY,Scope.FAMILY,"错家庭",null,null,1,family.scopeVersion(),99999));
    }); }
    @Test void parentDisablePreservesChildOwnStatusAndDeletionChecksDisabledChildren() { rollback(() -> {
        Item parent = category(11001);
        Item child = personal(11001,Type.ITEM_STYLE).items().stream().filter(i -> i.parentId().equals(parent.id())).findFirst().orElseThrow();
        service.status(11001,child.id(),false,child.revision(),personal(11001,Type.ITEM_STYLE).scopeVersion(),null);
        service.status(11001,parent.id(),false,parent.revision(),personal(11001,Type.CATEGORY).scopeVersion(),null);
        assertTrue(service.context(11001).items().stream().noneMatch(r -> Objects.equals(r.getParentId(),parent.id())));
        Item disabledParent = personal(11001,Type.CATEGORY).items().stream().filter(i->i.id().equals(parent.id())).findFirst().orElseThrow();
        service.status(11001,parent.id(),true,disabledParent.revision(),personal(11001,Type.CATEGORY).scopeVersion(),null);
        assertEquals(0,jdbc.queryForObject("SELECT dict_status FROM base_dict WHERE id=?",Integer.class,child.id()));
        Item restored = personal(11001,Type.CATEGORY).items().stream().filter(i->i.id().equals(parent.id())).findFirst().orElseThrow();
        assertThrows(RuntimeException.class,()->service.delete(11001,parent.id(),restored.revision(),personal(11001,Type.CATEGORY).scopeVersion(),null));
    }); }
    @Test void copyStyleCreatesIndependentFamilyParentAndRetainsMatchKind() { rollback(() -> {
        Item parent = createCategory(11001,"测试专属外套");
        Item child = service.create(11001,Type.ITEM_STYLE,Scope.PERSONAL,"测试轻外套",parent.id(),parent.revision(),null,personal(11001,Type.ITEM_STYLE).scopeVersion(),null).item();
        Management family = service.items(11001,Type.ITEM_STYLE,Scope.FAMILY,null);
        Copied result = service.copyPersonal(11001,List.of(child.id()),personal(11001,Type.ITEM_STYLE).scopeVersion(),family.scopeVersion(),12001);
        assertEquals(2,result.created().size()); Integer familyParent = result.parentMapping().get(parent.id()); assertNotEquals(parent.id(),familyParent);
        assertEquals(1,jdbc.queryForObject("SELECT wardrobe_match_kind FROM base_dict WHERE id=?",Integer.class,familyParent));
        assertEquals(familyParent,result.created().stream().filter(i->i.type()==5006).findFirst().orElseThrow().parentId());
    }); }
    @Test void scopeVersionBindsIdentityAndRejectsStaleCreate() { rollback(() -> {
        String before = personal(11001,Type.CATEGORY).scopeVersion();
        assertNotEquals(before,personal(11002,Type.CATEGORY).scopeVersion()); createCategory(11001,"测试版本A");
        assertThrows(RuntimeException.class,()->service.create(11001,Type.CATEGORY,Scope.PERSONAL,"测试版本B",null,null,1,before,null));
    }); }
    @Test void unchangedPatchPreservesOldSnapshotWhileExplicitClearProducesNull() { rollback(() -> {
        var context = service.context(11001); Item old = category(11001);
        WardrobeItemEntity current = new WardrobeItemEntity(); current.setUserId(11001); current.setId(13001);
        current.setCategorySourceId(old.id()); current.setCategoryNameSnapshot("保存旧名"); current.setCategoryMatchKindSnapshot(1); current.setCategory(1);
        current.setItemStyleSourceId(123); current.setItemStyleNameSnapshot("旧款式"); current.setItemStyle(101); current.setColorName("原颜色");
        WardrobeItemEntity target = new WardrobeItemEntity(); target.setUserId(11001);
        var keep = snapshots.prepareItem(target,current,null,null,null);
        assertEquals("保存旧名",target.getCategoryNameSnapshot()); assertEquals(123,target.getItemStyleSourceId()); assertTrue(keep.tags().isEmpty());
        try {
            snapshots.prepareItem(target,current,null,JSON.readTree("{\"itemStyleId\":null,\"sceneIds\":[]}"),Map.of("contextToken",context.contextToken()));
            assertNull(target.getItemStyleSourceId()); assertNull(target.getItemStyleNameSnapshot()); assertEquals(List.of(),snapshots.prepareItem(target,current,null,JSON.readTree("{\"sceneIds\":[]}"),Map.of("contextToken",context.contextToken())).tags().get(5004));
        } catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }); }
    @Test void styleParentPairAndOldContextAreRejected() { rollback(() -> {
        var context = service.context(11001); Item parent = category(11001);
        Item wrong = personal(11001,Type.ITEM_STYLE).items().stream().filter(i->!i.parentId().equals(parent.id())).findFirst().orElseThrow();
        WardrobeItemEntity target = new WardrobeItemEntity(); target.setUserId(11001);
        assertThrows(RuntimeException.class,()->snapshots.prepareItem(target,null,JSON.readTree("{\"categoryId\":"+parent.id()+",\"itemStyleId\":"+wrong.id()+"}"),null,Map.of("contextToken",context.contextToken())));
        createCategory(11001,"测试上下文变化");
        assertThrows(RuntimeException.class,()->service.lockSelectable(11001,context.contextToken(),List.of(parent.id())));
    }); }
    @Test void mysqlUniqueKeyDistinguishesStyleParentsAndProtectsDisabledNames() { rollback(() -> {
        Item first = createCategory(11001,"测试父A"), second = createCategory(11001,"测试父B");
        service.create(11001,Type.ITEM_STYLE,Scope.PERSONAL,"同名测试款式",first.id(),first.revision(),null,personal(11001,Type.ITEM_STYLE).scopeVersion(),null);
        Item child = service.create(11001,Type.ITEM_STYLE,Scope.PERSONAL,"同名测试款式",second.id(),second.revision(),null,personal(11001,Type.ITEM_STYLE).scopeVersion(),null).item();
        service.status(11001,child.id(),false,child.revision(),personal(11001,Type.ITEM_STYLE).scopeVersion(),null);
        assertThrows(RuntimeException.class,()->service.create(11001,Type.ITEM_STYLE,Scope.PERSONAL,"同名测试款式",second.id(),second.revision(),null,personal(11001,Type.ITEM_STYLE).scopeVersion(),null));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO base_dict(parent_id,dict_type,dict_code,dict_name,dict_status,sort,user_id,scope_type,family_group_id,revision,deleted,wardrobe_category_lineage_id) VALUES(?,5006,0,'同名测试款式',1,1,11001,0,0,0,0,?)",second.id(),second.categoryLineageId()));
    }); }
    @Test void concurrentSameVersionHasExactlyOneWinner() throws Exception {
        String expected = personal(11005,Type.CATEGORY).scopeVersion(); ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1); AtomicInteger winners = new AtomicInteger(), conflicts = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int i=0;i<2;i++) jobs.add(executor.submit(()->{
                try { start.await(); transactions.executeWithoutResult(status->service.create(11005,Type.CATEGORY,Scope.PERSONAL,"测试并发唯一",null,null,1,expected,null)); winners.incrementAndGet(); }
                catch (com.itwray.iw.web.exception.BusinessException ex) { conflicts.incrementAndGet(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }));
            start.countDown(); for (Future<?> job:jobs) job.get(15,TimeUnit.SECONDS);
            assertEquals(1,winners.get()); assertEquals(1,conflicts.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM base_dict WHERE user_id=11005 AND dict_type=5002 AND dict_name='测试并发唯一' AND deleted=0",Integer.class));
        } finally { executor.shutdownNow(); jdbc.update("DELETE FROM base_dict WHERE user_id=11005 AND dict_name='测试并发唯一'"); }
    }
}
