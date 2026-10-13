package com.itwray.iw.points.bookkeeping;

import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs only against a disposable database explicitly supplied by the test runner. */
@EnabledIfEnvironmentVariable(named = "IW_TASK_REGENERATION_JDBC_URL", matches = ".+")
class TaskBookkeepingSourceLockTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private TaskBookkeepingSourceQuery source;

    @BeforeEach
    void setUp() {
        String url = System.getenv("IW_TASK_REGENERATION_JDBC_URL");
        assertTrue(url.contains("/iw_task_regeneration_"), "Only disposable test databases are allowed");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        source = new TaskBookkeepingSourceQuery(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS points_task_bookkeeping");
        jdbc.execute("DROP TABLE IF EXISTS points_task_basics");
        jdbc.execute("CREATE TABLE points_task_basics(id INT PRIMARY KEY,user_id INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE points_task_bookkeeping(task_id INT PRIMARY KEY,user_id INT,bookkeeping_record_id INT UNIQUE) ENGINE=InnoDB");
        jdbc.update("INSERT INTO points_task_basics VALUES (10,1)");
        jdbc.update("INSERT INTO points_task_bookkeeping VALUES (10,1,100)");
    }

    @Test
    void billDeletionAndCompletionSerializeOnSameTask() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch deletionLockedTask = new CountDownLatch(1);
        CountDownLatch allowDeletionCommit = new CountDownLatch(1);
        try {
            Future<?> deletion = workers.submit(() -> {
                UserUtils.setUserId(1);
                try {
                    transaction.executeWithoutResult(status -> {
                        source.lockTaskForRecord(100);
                        deletionLockedTask.countDown();
                        try {
                            assertTrue(allowDeletionCommit.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    });
                } finally {
                    UserUtils.clearContext();
                }
            });
            assertTrue(deletionLockedTask.await(5, TimeUnit.SECONDS));
            Future<Integer> completion = workers.submit(() -> transaction.execute(status ->
                    jdbc.queryForObject("SELECT id FROM points_task_basics WHERE id=10 FOR UPDATE", Integer.class)));
            assertThrows(TimeoutException.class, () -> completion.get(250, TimeUnit.MILLISECONDS));
            allowDeletionCommit.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            assertEquals(10, completion.get(5, TimeUnit.SECONDS));
        } finally {
            allowDeletionCommit.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void billRemainsDeletableAfterTaskIsPhysicallyRemoved() {
        jdbc.update("DELETE FROM points_task_basics WHERE id=10");
        UserUtils.setUserId(1);
        try {
            assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> source.lockTaskForRecord(100)));
        } finally {
            UserUtils.clearContext();
        }
    }
}
