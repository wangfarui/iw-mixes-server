package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.AsyncTaskSnapshot;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Preview;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.TaskPhase;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.TaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 服务端截图识别任务：HTTP 请求只负责入队，AI 调用在后台执行。 */
@Service
public class ZhaogangReleaseImportAsyncTaskService {

    private static final String KEY_PREFIX = "zhaogang:release-import:task:";
    private static final String CANCEL_PREFIX = KEY_PREFIX + "cancel:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhaogangReleaseImportService releaseImport;
    private final ZhaogangProperties properties;
    private final ExecutorService executor;

    public ZhaogangReleaseImportAsyncTaskService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                                 ZhaogangReleaseImportService releaseImport,
                                                 ZhaogangProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.releaseImport = releaseImport;
        this.properties = properties;
        int threads = Math.max(1, properties.getReleaseImportTaskConcurrency());
        int queueSize = Math.max(threads * 4, 4);
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), daemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public AsyncTaskSnapshot create(Actor actor, long iterationId, byte[] image, String contentType,
                                    String projectColumnName, String planColumnName) {
        if (actor == null || actor.userId() <= 0 || actor.codingTeamId() <= 0 || iterationId <= 0) {
            throw new IllegalArgumentException("截图识别任务参数不完整");
        }
        if (image == null || image.length == 0 || image.length > properties.getAiMaxImageBytes()) {
            throw new IllegalArgumentException("截图不能为空且不能超过 10 MB");
        }
        String taskId = UUID.randomUUID().toString();
        Path imagePath;
        try {
            imagePath = Files.createTempFile("zhaogang-release-import-", ".img");
            Files.write(imagePath, image);
        } catch (IOException error) {
            throw new IllegalStateException("截图识别任务暂时无法创建", error);
        }
        StoredTask task = new StoredTask(taskId, actor.codingTeamId(), actor.userId(), iterationId,
                contentType, imagePath.toString(), new AsyncTaskSnapshot(taskId, TaskStatus.QUEUED,
                TaskPhase.QUEUED, 0, null, "", "识别任务已提交", false));
        write(task);
        try {
            executor.submit(() -> run(task, actor, projectColumnName, planColumnName));
        } catch (RejectedExecutionException error) {
            deleteImage(imagePath);
            write(new StoredTask(taskId, actor.codingTeamId(), actor.userId(), iterationId, contentType,
                    "", failed(taskId, "BUSY", "当前识别任务较多，请稍后重试", true)));
            throw new IllegalStateException("当前识别任务较多，请稍后重试");
        }
        return task.snapshot();
    }

    public AsyncTaskSnapshot get(long teamId, long userId, long iterationId, String taskId) {
        StoredTask task = load(taskId);
        verifyOwner(task, teamId, userId, iterationId, taskId);
        return task.snapshot();
    }

    public AsyncTaskSnapshot cancel(long teamId, long userId, long iterationId, String taskId) {
        StoredTask task = load(taskId);
        verifyOwner(task, teamId, userId, iterationId, taskId);
        if (task.snapshot().status() == TaskStatus.QUEUED || task.snapshot().status() == TaskStatus.RUNNING) {
            redis.opsForValue().set(CANCEL_PREFIX + taskId, "1", ttl());
            AsyncTaskSnapshot cancelled = new AsyncTaskSnapshot(taskId, TaskStatus.CANCELLED,
                    task.snapshot().phase(), task.snapshot().progress(), null, "CANCELLED", "识别任务已取消", false);
            write(new StoredTask(task.taskId(), task.teamId(), task.userId(), task.iterationId(),
                    task.contentType(), "", cancelled));
            deleteImage(Path.of(task.imagePath()));
            return cancelled;
        }
        return task.snapshot();
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void run(StoredTask task, Actor actor, String projectColumnName, String planColumnName) {
        Path imagePath = StringUtils.isBlank(task.imagePath()) ? null : Path.of(task.imagePath());
        try {
            if (cancelRequested(task.taskId())) return;
            writeSnapshot(task, running(task.taskId()));
            byte[] image = Files.readAllBytes(imagePath);
            if (cancelRequested(task.taskId())) return;
            Preview preview = releaseImport.recognizeAndMatch(actor, task.iterationId(), image, task.contentType(),
                    projectColumnName, planColumnName, phase -> writeSnapshot(task, progress(task.taskId(), phase)));
            if (cancelRequested(task.taskId())) return;
            writeSnapshot(task, new AsyncTaskSnapshot(task.taskId(), TaskStatus.SUCCEEDED, TaskPhase.COMPLETED,
                    100, preview, "", "识别完成", false));
        } catch (Exception error) {
            if (cancelRequested(task.taskId())) return;
            String message = StringUtils.defaultIfBlank(error.getMessage(), "截图识别失败");
            boolean retryable = isNetworkFailure(error, message);
            writeSnapshot(task, failed(task.taskId(), retryable ? "NETWORK" : "FAILED", message, retryable));
        } finally {
            deleteImage(imagePath);
            redis.delete(CANCEL_PREFIX + task.taskId());
        }
    }

    private AsyncTaskSnapshot running(String taskId) {
        return new AsyncTaskSnapshot(taskId, TaskStatus.RUNNING, TaskPhase.QUEUED, 5, null, "", "任务执行中", false);
    }

    private AsyncTaskSnapshot progress(String taskId, TaskPhase phase) {
        int progress = phase == TaskPhase.WAITING_AI ? 15 : phase == TaskPhase.MATCHING ? 85 : 5;
        String message = phase == TaskPhase.WAITING_AI ? "正在等待 AI 返回" : "正在匹配 CODING 项目和构建计划";
        return new AsyncTaskSnapshot(taskId, TaskStatus.RUNNING, phase, progress, null, "", message, false);
    }

    private AsyncTaskSnapshot failed(String taskId, String errorCode, String message, boolean retryable) {
        return new AsyncTaskSnapshot(taskId, TaskStatus.FAILED, TaskPhase.COMPLETED, 100, null,
                errorCode, message, retryable);
    }

    private boolean isNetworkFailure(Throwable error, String message) {
        if (error instanceof IOException) return true;
        String value = message.toLowerCase();
        return value.contains("网络") || value.contains("连接") || value.contains("超时")
                || value.contains("不可达") || value.contains("请求失败") || value.contains("timed out")
                || value.contains("timeout") || value.contains("connection");
    }

    private boolean cancelRequested(String taskId) {
        return Boolean.TRUE.equals(redis.hasKey(CANCEL_PREFIX + taskId));
    }

    private void writeSnapshot(StoredTask original, AsyncTaskSnapshot snapshot) {
        if (cancelRequested(original.taskId())) return;
        write(new StoredTask(original.taskId(), original.teamId(), original.userId(), original.iterationId(),
                original.contentType(), snapshot.status() == TaskStatus.RUNNING ? original.imagePath() : "", snapshot));
    }

    private void write(StoredTask task) {
        try {
            redis.opsForValue().set(KEY_PREFIX + task.taskId(), objectMapper.writeValueAsString(task), ttl());
        } catch (Exception error) {
            throw new IllegalStateException("截图识别任务状态保存失败", error);
        }
    }

    private StoredTask load(String taskId) {
        if (StringUtils.isBlank(taskId)) throw new IllegalArgumentException("识别任务编号不能为空");
        String value = redis.opsForValue().get(KEY_PREFIX + taskId.trim());
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException("识别任务不存在或已过期");
        try {
            return objectMapper.readValue(value, StoredTask.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("识别任务状态无效");
        }
    }

    private void verifyOwner(StoredTask task, long teamId, long userId, long iterationId, String taskId) {
        if (task == null || task.teamId() != teamId || task.userId() != userId || task.iterationId() != iterationId) {
            throw new IllegalArgumentException("识别任务不存在或无权访问: " + taskId);
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(60, properties.getReleaseImportTaskTtlSeconds()));
    }

    private void deleteImage(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件由操作系统或定期清理任务兜底处理。
        }
    }

    private java.util.concurrent.ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("zhaogang-release-import-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record StoredTask(String taskId, long teamId, long userId, long iterationId, String contentType,
                              String imagePath, AsyncTaskSnapshot snapshot) {
    }
}
