package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Preview;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZhaogangReleaseImportAsyncTaskServiceTest {

    @Test
    void persistsTaskAndCompletesWithoutRetainingImage() throws Exception {
        StringRedisTemplate redis = mockRedis();
        ZhaogangReleaseImportService releaseImport = mock(ZhaogangReleaseImportService.class);
        when(releaseImport.recognizeAndMatch(any(), eq(9L), any(byte[].class), eq("image/png"), eq("项目"), eq("计划"), any()))
                .thenReturn(new Preview(List.of()));
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setReleaseImportTaskTtlSeconds(60);
        ZhaogangReleaseImportAsyncTaskService service = new ZhaogangReleaseImportAsyncTaskService(
                redis, new ObjectMapper(), releaseImport, properties);

        AsyncSnapshotResult result = startAndAwait(service, actor(), 9L);
        assertEquals(TaskStatus.SUCCEEDED, result.snapshot().status());
        assertEquals(100, result.snapshot().progress());
        assertTrue(result.imagePath().isEmpty(), "completed task should not retain its image path");
    }

    @Test
    void rejectsTaskReadFromAnotherUserOrIteration() {
        StringRedisTemplate redis = mockRedis();
        ZhaogangReleaseImportService releaseImport = mock(ZhaogangReleaseImportService.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangReleaseImportAsyncTaskService service = new ZhaogangReleaseImportAsyncTaskService(
                redis, new ObjectMapper(), releaseImport, properties);

        assertThrows(IllegalArgumentException.class, () -> service.get(100L, 200L, 9L, "missing"));
    }

    private AsyncSnapshotResult startAndAwait(ZhaogangReleaseImportAsyncTaskService service, Actor actor,
                                              long iterationId) throws InterruptedException {
        var created = service.create(actor, iterationId, new byte[]{1, 2, 3}, "image/png", "项目", "计划");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var snapshot = service.get(actor.codingTeamId(), actor.userId(), iterationId, created.taskId());
            if (snapshot.status() == TaskStatus.SUCCEEDED || snapshot.status() == TaskStatus.FAILED) {
                return new AsyncSnapshotResult(snapshot, "");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("异步任务未完成");
    }

    private Actor actor() {
        return new Actor(22L, "user", "", "token", "team", 11L, "https://team.coding.net");
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(invocation -> store.containsKey(invocation.getArgument(0)));
        doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(invocation -> store.remove(invocation.getArgument(0)) != null);
        return redis;
    }

    private record AsyncSnapshotResult(
            com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.AsyncTaskSnapshot snapshot,
            String imagePath) {
    }
}
