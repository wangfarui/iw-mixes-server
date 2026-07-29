package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationAttemptDao;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class WardrobeImageOptimizationTaskServiceImplTest {

    @AfterEach
    void clearUserContext() {
        UserUtils.clearContext();
    }

    @Test
    void savedItemCanStartAQueuedImageOptimizationTask() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        WardrobeItemEntity item = new WardrobeItemEntity();
        item.setId(7);
        item.setUserId(13);
        item.setItemName("黑色衬衫");
        item.setItemImage("https://oss.example.com/wardrobe/source-7.jpg");
        item.setCategory(1);
        item.setItemStyle(102);
        when(itemDao.queryByIdInOwnerIds(eq(7), anyList())).thenReturn(item);

        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao,
                new WardrobeImageOptimizationPromptFactory());
        WardrobeItemImageOptimizeDto request = new WardrobeItemImageOptimizeDto();
        request.setItemId(7);
        request.setPrompt("保留纽扣细节");

        WardrobeItemImageOptimizeTaskVo task = service.start(request);

        assertNotNull(task.getTaskId());
        assertEquals(7, task.getItemId());
        assertEquals("queued", task.getStatus());
        assertEquals(1, task.getAttemptNo());
        assertFalse(task.getRetryable());
        ArgumentCaptor<WardrobeImageOptimizationTaskEntity> taskCaptor =
                ArgumentCaptor.forClass(WardrobeImageOptimizationTaskEntity.class);
        ArgumentCaptor<WardrobeImageOptimizationAttemptEntity> attemptCaptor =
                ArgumentCaptor.forClass(WardrobeImageOptimizationAttemptEntity.class);
        verify(taskDao).save(taskCaptor.capture());
        verify(attemptDao).save(attemptCaptor.capture());
        assertEquals(13, taskCaptor.getValue().getUserId());
        assertEquals(12, taskCaptor.getValue().getRequesterUserId());
        assertEquals(13, attemptCaptor.getValue().getUserId());
        assertEquals(12, attemptCaptor.getValue().getOperatorUserId());
    }

    @Test
    void sameActiveFingerprintReturnsExistingTaskWithoutCreatingAnotherAttempt() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        WardrobeItemEntity item = item(7, "https://oss.example.com/source.jpg");
        WardrobeImageOptimizationPromptFactory factory = new WardrobeImageOptimizationPromptFactory();
        String fingerprint = factory.create(item, "保留细节").fingerprint();
        WardrobeImageOptimizationTaskEntity active = task("task-1", 7, "queued", 1);
        active.setFingerprint(fingerprint);
        when(itemDao.queryByIdInOwnerIds(eq(7), anyList())).thenReturn(item);
        when(taskDao.findActiveByItem(7, 12)).thenReturn(active);
        when(attemptDao.findByTaskAndAttempt("task-1", 1, 12)).thenReturn(attempt("task-1", 1, "queued"));
        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao, factory);

        WardrobeItemImageOptimizeTaskVo result = service.start(request(7, "保留细节"));

        assertEquals("task-1", result.getTaskId());
    }

    @Test
    void activeTaskRejectsAnotherOptimizationForTheSameItem() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        when(itemDao.queryByIdInOwnerIds(eq(7), anyList()))
                .thenReturn(item(7, "https://oss.example.com/source.jpg"));
        WardrobeImageOptimizationTaskEntity active = task("task-1", 7, "running", 1);
        active.setFingerprint("another-fingerprint");
        when(taskDao.findActiveByItem(7, 12)).thenReturn(active);
        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao,
                new WardrobeImageOptimizationPromptFactory());

        assertThrows(BusinessException.class, () -> service.start(request(7, "新的要求")));
    }

    @Test
    void failedTaskCanBeRetriedAsTheNextAttempt() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        WardrobeItemEntity item = item(7, "https://oss.example.com/source.jpg");
        WardrobeImageOptimizationTaskEntity failed = task("task-1", 7, "failed", 1);
        failed.setSourceImageUrl(item.getItemImage());
        when(itemDao.queryByIdInOwnerIds(eq(7), anyList())).thenReturn(item);
        when(taskDao.findByTaskIdInOwners(eq("task-1"), anyList())).thenReturn(failed);
        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao,
                new WardrobeImageOptimizationPromptFactory());

        WardrobeItemImageOptimizeTaskVo result = service.retry("task-1");

        assertEquals("queued", result.getStatus());
        assertEquals(2, result.getAttemptNo());
        assertFalse(result.getRetryable());
        assertEquals("queued", failed.getStatus());
        assertEquals(2, failed.getCurrentAttemptNo());
    }

    @Test
    void activeTaskLocksOnlyTheSourceImage() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        WardrobeImageOptimizationTaskEntity active = task("task-1", 7, "running", 1);
        active.setSourceImageUrl("https://oss.example.com/source.jpg");
        when(taskDao.findActiveByItem(7, 12)).thenReturn(active);
        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao,
                new WardrobeImageOptimizationPromptFactory());

        service.assertSourceImageChangeAllowed(7, 12, "https://oss.example.com/source.jpg");
        assertThrows(BusinessException.class, () -> service.assertSourceImageChangeAllowed(
                7, 12, "https://oss.example.com/new-source.jpg"));
    }

    @Test
    void deletingItemCancelsTheActiveTaskAndMakesItNonRetryable() {
        UserUtils.setUserId(12);
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeImageOptimizationTaskDao taskDao = mock(WardrobeImageOptimizationTaskDao.class);
        WardrobeImageOptimizationAttemptDao attemptDao = mock(WardrobeImageOptimizationAttemptDao.class);
        WardrobeImageOptimizationTaskEntity active = task("task-1", 7, "running", 1);
        WardrobeImageOptimizationAttemptEntity running = attempt("task-1", 1, "running");
        when(taskDao.findActiveByItem(7, 12)).thenReturn(active);
        when(taskDao.findByTaskIdInOwners(eq("task-1"), anyList())).thenReturn(active);
        when(taskDao.findByTaskIdForUpdate("task-1", 12)).thenReturn(active);
        when(attemptDao.findByTaskAndAttempt("task-1", 1, 12)).thenReturn(running);
        when(itemDao.queryByIdInOwnerIds(eq(7), anyList()))
                .thenReturn(item(7, "https://oss.example.com/source.jpg"));
        WardrobeImageOptimizationTaskServiceImpl service = service(itemDao, taskDao, attemptDao,
                new WardrobeImageOptimizationPromptFactory());

        service.cancelForItemDeletion(7, 12);
        WardrobeItemImageOptimizeTaskVo result = service.get("task-1");

        assertEquals("cancelled", result.getStatus());
        assertFalse(result.getRetryable());
        assertThrows(BusinessException.class, () -> service.retry("task-1"));
    }

    private static WardrobeImageOptimizationTaskServiceImpl service(WardrobeItemDao itemDao,
                                                                     WardrobeImageOptimizationTaskDao taskDao,
                                                                     WardrobeImageOptimizationAttemptDao attemptDao,
                                                                     WardrobeImageOptimizationPromptFactory factory) {
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        when(accessService.resolveFamilyOwnerIds()).thenReturn(java.util.List.of(12));
        return new WardrobeImageOptimizationTaskServiceImpl(itemDao, taskDao, attemptDao, factory, accessService);
    }

    private static WardrobeItemEntity item(Integer id, String sourceImageUrl) {
        WardrobeItemEntity item = new WardrobeItemEntity();
        item.setId(id);
        item.setUserId(12);
        item.setItemName("黑色衬衫");
        item.setItemImage(sourceImageUrl);
        item.setCategory(1);
        item.setItemStyle(102);
        return item;
    }

    private static WardrobeItemImageOptimizeDto request(Integer itemId, String prompt) {
        WardrobeItemImageOptimizeDto dto = new WardrobeItemImageOptimizeDto();
        dto.setItemId(itemId);
        dto.setPrompt(prompt);
        return dto;
    }

    private static WardrobeImageOptimizationTaskEntity task(String taskId, Integer itemId, String status,
                                                             Integer attemptNo) {
        WardrobeImageOptimizationTaskEntity task = new WardrobeImageOptimizationTaskEntity();
        task.setTaskId(taskId);
        task.setUserId(12);
        task.setItemId(itemId);
        task.setStatus(status);
        task.setCurrentAttemptNo(attemptNo);
        return task;
    }

    private static WardrobeImageOptimizationAttemptEntity attempt(String taskId, Integer attemptNo, String status) {
        WardrobeImageOptimizationAttemptEntity attempt = new WardrobeImageOptimizationAttemptEntity();
        attempt.setTaskId(taskId);
        attempt.setUserId(12);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(status);
        return attempt;
    }
}
