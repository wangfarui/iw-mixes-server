package com.itwray.iw.bookkeeping.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import com.itwray.iw.bookkeeping.model.vo.BookkeepingRecordPageVo;
import com.itwray.iw.web.model.vo.PageVo;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.itwray.iw.auth.model.enums.ShareStateEnum;
import com.itwray.iw.auth.model.enums.FamilyMemberRoleEnum;
import com.itwray.iw.auth.model.vo.*;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.bookkeeping.dao.BookkeepingRecordsDao;
import com.itwray.iw.bookkeeping.service.impl.BookkeepingRecordsServiceImpl;
import com.itwray.iw.bookkeeping.service.impl.BookkeepingRecordsFamilyGroupConsumerServiceImpl;
import com.itwray.iw.auth.model.mq.FamilyGroupMemberLeaveMqDto;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingSourceQuery;
import com.itwray.iw.web.dao.BaseDictBusinessRelationDao;
import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.model.enums.OrderNoEnum;
import java.util.List;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import com.itwray.iw.bookkeeping.dao.BookkeepingWalletDao;
import com.itwray.iw.bookkeeping.dao.BookkeepingWalletRecordsDao;
import com.itwray.iw.bookkeeping.mapper.*;
import com.itwray.iw.bookkeeping.model.dto.*;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.BookkeepingStatisticsTypeEnum;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.config.IwDaoProperties;
import com.itwray.iw.web.core.mybatis.UserDataPermissionHandler;
import com.itwray.iw.web.utils.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs production mapper SQL through the actual data-permission interceptor. */
class BookkeepingPayerIntegrationTest {
    private GenericApplicationContext context;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private TransactionTemplate transaction;
    private boolean mysql;
    private boolean ownsMysqlTables;
    private static final String MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:13316/iw_dictionary_test";
    private static final String[] TEST_TABLES = {"bookkeeping_records", "bookkeeping_record_tag_snapshot",
            "base_dict_business_relation", "points_task_bookkeeping", "bookkeeping_wallet", "bookkeeping_wallet_records", "dictionary_tx_probe"};

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource;
        mysql = System.getenv("IW_DICTIONARY_TEST_MYSQL_URL") != null;
        if (mysql) {
            assertEquals(MYSQL_TEST_URL, System.getenv("IW_DICTIONARY_TEST_MYSQL_URL"), "Only the private test MySQL is allowed");
            dataSource = new DriverManagerDataSource(MYSQL_TEST_URL, "root", "");
        } else {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:payer" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource = h2;
        }
        jdbc = new JdbcTemplate(dataSource);
        if (mysql) {
            assertTrue(jdbc.queryForObject("SELECT @@datadir", String.class)
                    .startsWith("/private/tmp/iw-dict-v2-functional-mysql-"), "Refuse application/production data directories");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class),
                    "Private test schema must be empty");
            ownsMysqlTables = true;
        }
        jdbc.execute("CREATE TABLE bookkeeping_records (id INT AUTO_INCREMENT PRIMARY KEY, user_id INT, expense_user_id INT, "
                + "group_id INT DEFAULT 10, share_state INT DEFAULT 0, deleted BOOLEAN DEFAULT FALSE, record_category INT, "
                + "record_date DATE, record_time TIMESTAMP, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "update_time TIMESTAMP, order_no VARCHAR(64), record_source VARCHAR(64), record_icon VARCHAR(64), "
                + "record_type INT DEFAULT 1, record_type_id INT, record_type_name VARCHAR(32), dictionary_snapshot_status INT DEFAULT 1, amount DECIMAL(14,2), remark VARCHAR(255), is_statistics INT DEFAULT 1, is_excitation_record INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE bookkeeping_record_tag_snapshot (id INT AUTO_INCREMENT PRIMARY KEY, record_id INT, dict_type INT, source_dict_id INT, tag_name VARCHAR(32))");
        jdbc.execute("CREATE TABLE base_dict_business_relation (id INT AUTO_INCREMENT PRIMARY KEY, business_type INT, business_id INT, dict_id INT)");
        jdbc.execute("CREATE TABLE points_task_bookkeeping (bookkeeping_record_id INT)");
        jdbc.execute("CREATE TABLE bookkeeping_wallet (id INT AUTO_INCREMENT PRIMARY KEY, user_id INT, wallet_balance DECIMAL(14,2), wallet_assets DECIMAL(14,2), deleted BOOLEAN DEFAULT FALSE, create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE bookkeeping_wallet_records (id INT AUTO_INCREMENT PRIMARY KEY, user_id INT, change_type INT, before_amount DECIMAL(14,2), after_amount DECIMAL(14,2), change_amount DECIMAL(14,2), deleted BOOLEAN DEFAULT FALSE, create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.update("INSERT INTO bookkeeping_wallet(user_id,wallet_balance,wallet_assets) VALUES (1,1000,0),(2,1000,0)");
        add(1, 1, 0, 100); add(1, 1, 1, 200); add(2, 2, 0, 80); add(2, 2, 1, 300); add(1, 2, 1, 50);
        jdbc.update("INSERT INTO base_dict_business_relation(business_type,business_id,dict_id) SELECT 4001,id,7 FROM bookkeeping_records");
        jdbc.update("INSERT INTO bookkeeping_record_tag_snapshot(record_id,dict_type,source_dict_id,tag_name) SELECT id,4001,7,'外卖' FROM bookkeeping_records");

        IwDaoProperties.DataPermission permission = new IwDaoProperties.DataPermission();
        permission.setEnableTableNames(Set.of("bookkeeping_records", "bookkeeping_wallet", "bookkeeping_wallet_records"));
        permission.setShareScopeEnableTableNames(Set.of("bookkeeping_records"));
        UserDataPermissionHandler handler = new UserDataPermissionHandler(permission, Set.of(
                BookkeepingWalletMapper.class.getName() + ".lockWallet", BookkeepingWalletMapper.class.getName() + ".updateWalletBalance"));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(handler));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BookkeepingRecordsMapper.class);
        configuration.addMapper(BookkeepingRecordsYearlyStatisticsMapper.class);
        configuration.addMapper(BookkeepingWalletMapper.class);
        configuration.addMapper(BookkeepingWalletRecordsMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        factory.setMapperLocations(new ClassPathResource("mapper/BookkeepingRecordsMapper.xml"),
                new ClassPathResource("mapper/BookkeepingRecordsYearlyStatisticsMapper.xml"),
                new ClassPathResource("mapper/BookkeepingWalletMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        context = new GenericApplicationContext();
        context.registerBean(BookkeepingQueryScope.class);
        context.registerBean(SqlSessionFactory.class, () -> sqlSessionFactory);
        context.refresh();
        new ApplicationContextHolder().setApplicationContext(context);
        session = new SqlSessionTemplate(sqlSessionFactory);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        scope(1, false);
    }

    @AfterEach
    void tearDown() {
        UserUtils.removeUserId(); UserUtils.removeUserDataPermission();
        UserSharedQueryUtils.clearContext(); UserCurrentGroupUtils.removeCurrentGroupId();
        if (context != null) context.close();
        ReflectionTestUtils.setField(ApplicationContextHolder.class, "applicationContext", null);
        if (ownsMysqlTables && jdbc != null) {
            for (String table : TEST_TABLES) jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void add(int recorder, int payer, int shared, int amount) {
        jdbc.update("INSERT INTO bookkeeping_records(user_id,expense_user_id,share_state,record_category,record_date,record_time,amount) VALUES (?,?,?,1,'2026-10-10','2026-10-10 12:00:00',?)", recorder, payer, shared, amount);
    }

    private void scope(int userId, boolean shared) {
        UserUtils.setUserId(userId);
        UserSharedQueryUtils.setUserSharedQuery(true);
        UserSharedQueryUtils.setUserSharedQueryOnlyMyself(!shared);
        UserCurrentGroupUtils.setCurrentGroupId(10);
        session.clearCache();
    }

    private BookkeepingStatisticsDto params() {
        BookkeepingStatisticsDto dto = new BookkeepingStatisticsDto();
        dto.setRecordCategory(RecordCategoryEnum.CONSUME);
        dto.setCurrentStartMonth(LocalDate.of(2026, 1, 1)); dto.setCurrentEndMonth(LocalDate.of(2026, 12, 31));
        dto.setStatisticsType(BookkeepingStatisticsTypeEnum.MONTH);
        return dto;
    }

    private void total(int userId, boolean shared, String amount) {
        scope(userId, shared);
        assertEquals(new BigDecimal(amount), session.getMapper(BookkeepingRecordsMapper.class).totalStatistics(params()).getTotalAmount());
    }

    @Test
    void personalAndFamilyTotalsRespectPayerAndPrivateRecords() {
        total(1, false, "300.00"); total(2, false, "430.00");
        total(1, true, "650.00"); total(2, true, "630.00");
    }

    @Test
    void pageSummaryCategoryTrendRankAndJoinedTagsShareTheSameScope() {
        scope(2, false);
        BookkeepingRecordsStatisticsDto detail = new BookkeepingRecordsStatisticsDto();
        detail.setRecordStartDate(LocalDate.of(2026, 1, 1)); detail.setRecordEndDate(LocalDate.of(2026, 12, 31));
        BookkeepingRecordsMapper mapper = session.getMapper(BookkeepingRecordsMapper.class);
        assertEquals(new BigDecimal("430.00"), mapper.statistics(detail).get(0).getTotalAmount());
        PageVo<BookkeepingRecordPageVo> page = new PageVo<>(detail);
        mapper.page(page, detail);
        assertEquals(3, page.getTotal());
        assertEquals(3, page.getRecords().size());
        assertEquals(3, mapper.rankStatistics(params()).size());
        assertTrue(mapper.rankStatistics(params()).stream().anyMatch(r -> r.getUserId() == 1 && r.getExpenseUserId() == 2));
        assertEquals(new BigDecimal("430.00"), mapper.barChartStatistics(params()).get(0).getAmount());
        BookkeepingConsumeCategoryStatisticsDto category = new BookkeepingConsumeCategoryStatisticsDto();
        category.setRecordCategory(RecordCategoryEnum.CONSUME); category.setCurrentStartMonth(params().getCurrentStartMonth()); category.setCurrentEndMonth(params().getCurrentEndMonth());
        assertEquals(new BigDecimal("430.00"), mapper.categoryStatistics(category).get(0).getAmount());
        BookkeepingRecordsYearStatisticsQueryDto yearly = new BookkeepingRecordsYearStatisticsQueryDto();
        yearly.setUserId(2); yearly.setStartDate(params().getCurrentStartMonth()); yearly.setEndDate(params().getCurrentEndMonth());
        yearly.setRecordCategories(Set.of(RecordCategoryEnum.CONSUME));
        assertEquals(new BigDecimal("430.00"), session.getMapper(BookkeepingRecordsYearlyStatisticsMapper.class).statisticsTagConsume(yearly).get(0).getAmount());
        assertEquals(new BigDecimal("430.00"), session.getMapper(BookkeepingRecordsYearlyStatisticsMapper.class).statisticsMaxDay(yearly).getMaxDayAmount());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IW_DICTIONARY_TEST_MYSQL_URL", matches = ".+")
    void dictionaryNameFiltersAndGroupingRemainExactThroughDataPermissions() {
        scope(2, false);
        jdbc.update("UPDATE bookkeeping_records SET record_type_name=CASE id WHEN 3 THEN 'Food' WHEN 4 THEN 'food' ELSE 'food2' END");
        jdbc.update("UPDATE bookkeeping_record_tag_snapshot SET tag_name=CASE record_id WHEN 3 THEN '外卖A' WHEN 4 THEN '外卖a' ELSE '外卖啊' END");
        BookkeepingRecordsMapper mapper = session.getMapper(BookkeepingRecordsMapper.class);
        BookkeepingConsumeCategoryStatisticsDto category = new BookkeepingConsumeCategoryStatisticsDto();
        category.setRecordCategory(RecordCategoryEnum.CONSUME);
        category.setCurrentStartMonth(params().getCurrentStartMonth());
        category.setCurrentEndMonth(params().getCurrentEndMonth());
        assertEquals(3, mapper.categoryStatistics(category).size());

        BookkeepingStatisticsDto filtered = params();
        filtered.setCategoryNames(List.of("food"));
        assertEquals(new BigDecimal("300.00"), mapper.totalStatistics(filtered).getTotalAmount());
        assertEquals(1, mapper.rankStatistics(filtered).size());
        assertEquals(new BigDecimal("300.00"), mapper.barChartStatistics(filtered).get(0).getAmount());
        category.setCategoryNames(List.of("food"));
        assertEquals(new BigDecimal("300.00"), mapper.categoryStatistics(category).get(0).getAmount());

        BookkeepingRecordsStatisticsDto detail = new BookkeepingRecordsStatisticsDto();
        detail.setRecordStartDate(params().getCurrentStartMonth());
        detail.setRecordEndDate(params().getCurrentEndMonth());
        detail.setCategoryNames(List.of("food"));
        PageVo<BookkeepingRecordPageVo> page = new PageVo<>(detail);
        mapper.page(page, detail);
        assertEquals(1, page.getTotal());

        filtered.setCategoryNames(null);
        filtered.setTagNames(List.of("外卖a"));
        assertEquals(new BigDecimal("300.00"), mapper.totalStatistics(filtered).getTotalAmount());
        assertEquals(1, mapper.rankStatistics(filtered).size());
        assertEquals(new BigDecimal("300.00"), mapper.barChartStatistics(filtered).get(0).getAmount());
        category.setCategoryNames(null);
        category.setTagNames(List.of("外卖a"));
        assertEquals(new BigDecimal("300.00"), mapper.categoryStatistics(category).get(0).getAmount());
        detail.setCategoryNames(null);
        detail.setTagNames(List.of("外卖a"));
        mapper.page(page, detail);
        assertEquals(1, page.getTotal());
    }

    @Test
    void handledUnavailableFutureOptionDoesNotPoisonCallerTransactionButUncaughtFailureRollsBack() {
        var mapper = mock(com.itwray.iw.auth.mapper.BookkeepingDictionaryMapper.class);
        when(mapper.currentGroup(1)).thenReturn(0);
        when(mapper.lockUser(1)).thenReturn(0);
        var disabled = new com.itwray.iw.web.model.entity.BaseDictEntity();
        disabled.setId(77); disabled.setDictType(4002); disabled.setDictStatus(0); disabled.setDeleted(false);
        when(mapper.lockOwned(List.of(77), 1, 0)).thenReturn(List.of(disabled));
        var target = new com.itwray.iw.auth.service.impl.BookkeepingDictionaryService(mapper);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transaction.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        var dictionaries = (com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess) factory.getProxy();
        jdbc.execute("CREATE TABLE dictionary_tx_probe (id INT PRIMARY KEY)");
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO dictionary_tx_probe VALUES (1)");
            assertThrows(com.itwray.iw.web.exception.BusinessException.class,
                    () -> dictionaries.resolveSuccessor(1, 4002, 77));
            assertFalse(status.isRollbackOnly(), "Expected unavailable configuration must be returnable by the caller");
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dictionary_tx_probe", Integer.class));
        assertThrows(com.itwray.iw.web.exception.BusinessException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO dictionary_tx_probe VALUES (2)");
            dictionaries.resolveSuccessor(1, 4002, 77);
        }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dictionary_tx_probe", Integer.class));
    }

    @Test
    void legacyNullPayerIncomeAndStatisticsFilterKeepTheirMeaning() {
        jdbc.update("UPDATE bookkeeping_records SET expense_user_id = NULL WHERE id = 1");
        jdbc.update("INSERT INTO bookkeeping_records(user_id,record_category,record_date,amount,share_state,is_statistics) VALUES (2,2,'2026-10-10',500,1,1),(1,2,'2026-10-10',100,0,0)");
        total(1, false, "300.00");
        BookkeepingStatisticsDto dto = params(); dto.setRecordCategory(RecordCategoryEnum.INCOME);
        scope(1, true);
        assertEquals(new BigDecimal("600.00"), session.getMapper(BookkeepingRecordsMapper.class).totalStatistics(dto).getTotalAmount());
        dto.setIsSearchAll(0);
        assertEquals(new BigDecimal("500.00"), session.getMapper(BookkeepingRecordsMapper.class).totalStatistics(dto).getTotalAmount());
    }

    @Test
    void recordingHabitsCountTheRecorderRatherThanThePayerOrFamily() {
        jdbc.update("UPDATE bookkeeping_records SET record_date = '2026-10-09' WHERE id = 5");
        scope(2, true);
        BookkeepingRecordsYearStatisticsQueryDto yearly = new BookkeepingRecordsYearStatisticsQueryDto();
        yearly.setUserId(2); yearly.setStartDate(params().getCurrentStartMonth()); yearly.setEndDate(params().getCurrentEndMonth());
        assertEquals(1, session.getMapper(BookkeepingRecordsYearlyStatisticsMapper.class).statisticsRecordingDays(yearly));
    }

    @Test
    void leftGroupRecordsRemainPersonalButDisappearFromOtherMembersFamilyTotals() {
        jdbc.update("UPDATE bookkeeping_records SET share_state = 2 WHERE id = 5");
        total(1, true, "600.00"); total(2, false, "430.00");
    }

    @Test
    void walletsFollowPayerAndRollbackTheWholeMoveWhenTargetWalletIsMissing() {
        BookkeepingWalletDao wallets = new BookkeepingWalletDao();
        BookkeepingWalletRecordsDao records = new BookkeepingWalletRecordsDao();
        ReflectionTestUtils.setField(wallets, "baseMapper", session.getMapper(BookkeepingWalletMapper.class));
        ReflectionTestUtils.setField(records, "baseMapper", session.getMapper(BookkeepingWalletRecordsMapper.class));
        BookkeepingWalletLedger ledger = new BookkeepingWalletLedger(wallets, records);
        BookkeepingRecordsEntity original = record(2, 50);
        transaction.executeWithoutResult(status -> ledger.apply(null, original));
        assertBalance(1, "1000.00"); assertBalance(2, "950.00");
        BookkeepingRecordsEntity moved = record(1, 70);
        transaction.executeWithoutResult(status -> ledger.apply(original, moved));
        assertBalance(1, "930.00"); assertBalance(2, "1000.00");
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> ledger.apply(moved, record(3, 100))));
        assertBalance(1, "930.00");
        transaction.executeWithoutResult(status -> ledger.apply(moved, null));
        assertBalance(1, "1000.00");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM bookkeeping_wallet_records", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM bookkeeping_wallet_records WHERE user_id = 2", Integer.class));
    }


    @Test
    void realRecordCrudKeepsOwnershipPermissionsAndWalletsConsistent() {
        BookkeepingRecordsDao dao = new BookkeepingRecordsDao();
        ReflectionTestUtils.setField(dao, "baseMapper", session.getMapper(BookkeepingRecordsMapper.class));
        BookkeepingWalletDao wallets = new BookkeepingWalletDao();
        BookkeepingWalletRecordsDao walletRecords = new BookkeepingWalletRecordsDao();
        ReflectionTestUtils.setField(wallets, "baseMapper", session.getMapper(BookkeepingWalletMapper.class));
        ReflectionTestUtils.setField(walletRecords, "baseMapper", session.getMapper(BookkeepingWalletRecordsMapper.class));
        AuthFamilyGroupService families = mock(AuthFamilyGroupService.class);
        FamilySharedSavePolicyVo savePolicy = new FamilySharedSavePolicyVo();
        savePolicy.setCurrentGroupId(10); savePolicy.setDefaultShared(0); savePolicy.setForceShared(0);
        when(families.querySharedSavePolicy(1)).thenReturn(savePolicy);
        FamilyMemberVo a = new FamilyMemberVo(); a.setUserId(1); a.setRole(FamilyMemberRoleEnum.MEMBER);
        FamilyMemberVo b = new FamilyMemberVo(); b.setUserId(2); b.setRole(FamilyMemberRoleEnum.MEMBER);
        when(families.memberList(10)).thenReturn(List.of(a, b));
        BaseDictBusinessRelationDao tags = mock(BaseDictBusinessRelationDao.class);
        BaseBusinessFileDao files = mock(BaseBusinessFileDao.class);
        BookkeepingRecordsServiceImpl service = new BookkeepingRecordsServiceImpl(dao, tags, files);
        ReflectionTestUtils.setField(service, "familyService", families);
        ReflectionTestUtils.setField(service, "payerPolicy", new BookkeepingPayerPolicy(families));
        ReflectionTestUtils.setField(service, "walletLedger", new BookkeepingWalletLedger(wallets, walletRecords));
        ReflectionTestUtils.setField(service, "taskSourceQuery", mock(TaskBookkeepingSourceQuery.class));
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "snapshots", new BookkeepingDictionarySnapshots(mock(com.itwray.iw.web.bookkeeping.BookkeepingDictionaryAccess.class),jdbc));
        try (MockedStatic<OrderNoUtils> orders = mockStatic(OrderNoUtils.class)) {
            orders.when(() -> OrderNoUtils.getAndIncrement(OrderNoEnum.BOOKKEEPING_RECORDS)).thenReturn("payer-test");
            BookkeepingRecordAddDto add = new BookkeepingRecordAddDto();
            add.setRecordCategory(RecordCategoryEnum.CONSUME); add.setAmount(new BigDecimal("50.00")); add.setExpenseUserId(2); add.setShared(0);
            Integer id = transaction.execute(status -> service.add(add));
            assertBalance(1, "1000.00"); assertBalance(2, "950.00");
            assertEquals(1, jdbc.queryForObject("SELECT share_state FROM bookkeeping_records WHERE id = ?", Integer.class, id));
            assertTrue(service.detail(id).getCanEdit());
            scope(2, false);
            assertFalse(service.detail(id).getCanEdit());
            BookkeepingRecordUpdateDto edit = new BookkeepingRecordUpdateDto();
            edit.setId(id); edit.setRecordCategory(RecordCategoryEnum.CONSUME); edit.setAmount(new BigDecimal("70.00")); edit.setShared(0);
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> service.update(edit)));
            scope(1, false);
            transaction.executeWithoutResult(status -> service.update(edit));
            assertEquals(2, jdbc.queryForObject("SELECT expense_user_id FROM bookkeeping_records WHERE id = ?", Integer.class, id));
            assertBalance(2, "930.00");
            edit.setExpenseUserId(1);
            transaction.executeWithoutResult(status -> service.update(edit));
            assertBalance(1, "930.00"); assertBalance(2, "1000.00");
            // An ordinary edit must preserve an explicit sharing choice, independent of the current default.
            edit.setShared(1);
            transaction.executeWithoutResult(status -> service.update(edit));
            edit.setShared(null);
            transaction.executeWithoutResult(status -> service.update(edit));
            assertEquals(1, jdbc.queryForObject("SELECT share_state FROM bookkeeping_records WHERE id = ?", Integer.class, id));
            edit.setShared(0);
            transaction.executeWithoutResult(status -> service.update(edit));
            savePolicy.setDefaultShared(1);
            edit.setShared(null);
            transaction.executeWithoutResult(status -> service.update(edit));
            assertEquals(0, jdbc.queryForObject("SELECT share_state FROM bookkeeping_records WHERE id = ?", Integer.class, id));
            transaction.executeWithoutResult(status -> service.delete(id));
            assertBalance(1, "1000.00");
            assertEquals(1, jdbc.queryForObject("SELECT deleted FROM bookkeeping_records WHERE id = ?", Integer.class, id));
            // A valid family member without a wallet must roll back the record insert too.
            FamilyMemberVo c = new FamilyMemberVo(); c.setUserId(3); c.setRole(FamilyMemberRoleEnum.MEMBER);
            when(families.memberList(10)).thenReturn(List.of(a, b, c));
            add.setExpenseUserId(3);
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM bookkeeping_records", Integer.class);
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> service.add(add)));
            assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM bookkeeping_records", Integer.class));
        }
    }

    @Test
    void departureAfterCommitPersistsWithdrawalInANewTransaction() {
        BookkeepingRecordsDao dao = new BookkeepingRecordsDao();
        ReflectionTestUtils.setField(dao, "baseMapper", session.getMapper(BookkeepingRecordsMapper.class));
        ProxyFactory proxy = new ProxyFactory(new BookkeepingRecordsFamilyGroupConsumerServiceImpl(dao));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transaction.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        BookkeepingRecordsFamilyGroupConsumerServiceImpl consumer = (BookkeepingRecordsFamilyGroupConsumerServiceImpl) proxy.getProxy();
        FamilyGroupMemberLeaveMqDto leave = new FamilyGroupMemberLeaveMqDto(); leave.setGroupId(10); leave.setUserIdList(List.of(2));
        transaction.executeWithoutResult(status -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { consumer.doConsume(leave); }
        }));
        assertEquals(2, jdbc.queryForObject("SELECT share_state FROM bookkeeping_records WHERE id = 5", Integer.class));
    }

    @Test
    void payerDepartureWithdrawsDelegatedRecordsEvenWhenRecorderStays() {
        BookkeepingRecordsDao dao = new BookkeepingRecordsDao();
        ReflectionTestUtils.setField(dao, "baseMapper", session.getMapper(BookkeepingRecordsMapper.class));
        FamilyGroupMemberLeaveMqDto leave = new FamilyGroupMemberLeaveMqDto(); leave.setGroupId(10); leave.setUserIdList(List.of(2));
        new BookkeepingRecordsFamilyGroupConsumerServiceImpl(dao).doConsume(leave);
        assertEquals(2, jdbc.queryForObject("SELECT share_state FROM bookkeeping_records WHERE id = 5", Integer.class));
        total(1, true, "300.00"); total(2, false, "430.00");
    }

    private BookkeepingRecordsEntity record(int payer, int amount) {
        BookkeepingRecordsEntity record = new BookkeepingRecordsEntity();
        record.setUserId(1); record.setExpenseUserId(payer); record.setRecordCategory(RecordCategoryEnum.CONSUME); record.setAmount(BigDecimal.valueOf(amount));
        return record;
    }
    private void assertBalance(int userId, String expected) {
        assertEquals(new BigDecimal(expected), jdbc.queryForObject("SELECT wallet_balance FROM bookkeeping_wallet WHERE user_id = ?", BigDecimal.class, userId));
    }
}
