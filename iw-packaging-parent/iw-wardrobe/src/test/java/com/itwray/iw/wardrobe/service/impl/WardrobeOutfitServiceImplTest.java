package com.itwray.iw.wardrobe.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitDao;
import com.itwray.iw.wardrobe.dao.WardrobeOutfitItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeItemStatusEnum;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import com.itwray.iw.wardrobe.service.WardrobeItemService;
import com.itwray.iw.wardrobe.service.WardrobeWearRecordService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WardrobeOutfitServiceImplTest {

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wardrobe-test"),
                WardrobeItemEntity.class);
    }

    @Test
    void suggestionQueryIncludesAllVisibleFamilyOwnersWithoutFilteringRecentItems() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42, 43));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(2, 2, WardrobeItemStatusEnum.WEARING.getCode(), LocalDate.now(), 1)
        ));
        WardrobeOutfitServiceImpl service = service(itemDao, accessService);
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setAvoidRecentDays(3);
        request.setSeason("summer");
        request.setScene("2");
        request.setStyle("2");
        request.setLimit(1);

        assertEquals(1, service.suggest(request).size());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<WardrobeItemEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(itemDao).list(captor.capture());
        LambdaQueryWrapper<WardrobeItemEntity> query = captor.getValue();
        String sqlSegment = query.getSqlSegment();
        assertTrue(query.getParamNameValuePairs().containsValue(42));
        assertTrue(query.getParamNameValuePairs().containsValue(43));
        assertFalse(sqlSegment.contains("last_wear_date"));
        assertFalse(sqlSegment.contains("season_tags"));
        assertFalse(sqlSegment.contains("scene_tags"));
        assertFalse(sqlSegment.contains("style_tags"));
    }

    @Test
    void recentlyWornItemsAreDeprioritizedButRemainCandidates() {
        LocalDate today = LocalDate.of(2026, 7, 31);
        WardrobeItemEntity recent = item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), today, 0);
        WardrobeItemEntity older = item(2, 1, WardrobeItemStatusEnum.WEARING.getCode(), today.minusDays(10), 2);
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setAvoidRecentDays(3);

        List<WardrobeItemEntity> ranked = WardrobeOutfitServiceImpl.rankSuggestCandidates(
                List.of(recent, older), request, today);

        assertEquals(List.of(2, 1), ranked.stream().map(WardrobeItemEntity::getId).toList());
    }

    @Test
    void tagCodesAreMatchedExactlyInsteadOfBySubstring() {
        WardrobeItemEntity exact = item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        exact.setStyleTags("2");
        WardrobeItemEntity substringOnly = item(2, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        substringOnly.setStyleTags("1,12");
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setStyle("2");

        List<WardrobeItemEntity> ranked = WardrobeOutfitServiceImpl.rankSuggestCandidates(
                List.of(substringOnly, exact), request, LocalDate.of(2026, 7, 31));

        assertTrue(WardrobeOutfitServiceImpl.containsAnyExactTag("1, 2,12", "2"));
        assertFalse(WardrobeOutfitServiceImpl.containsAnyExactTag("1,12", "2"));
        assertEquals(List.of(1, 2), ranked.stream().map(WardrobeItemEntity::getId).toList());
    }

    @Test
    void partialMatchesRemainCandidatesAndRankBelowMoreRelevantItems() {
        WardrobeItemEntity fullMatch = item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 3);
        fullMatch.setSeasonTags("summer");
        fullMatch.setSceneTags("2");
        fullMatch.setStyleTags("2");
        WardrobeItemEntity seasonOnly = item(2, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        seasonOnly.setSeasonTags("summer");
        seasonOnly.setSceneTags("1");
        seasonOnly.setStyleTags("12");
        WardrobeItemEntity noMatch = item(3, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        noMatch.setSeasonTags("winter");
        noMatch.setSceneTags("1");
        noMatch.setStyleTags("12");
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setSeason("summer");
        request.setScene("2");
        request.setStyle("2");

        List<WardrobeItemEntity> ranked = WardrobeOutfitServiceImpl.rankSuggestCandidates(
                List.of(noMatch, seasonOnly, fullMatch), request, LocalDate.of(2026, 7, 31));

        assertEquals(List.of(1, 2, 3), ranked.stream().map(WardrobeItemEntity::getId).toList());
    }

    @Test
    void dressAndSeparateOutfitsAreMutuallyExclusiveAndDeduplicated() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(2, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 1),
                item(3, 2, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(4, 3, WardrobeItemStatusEnum.WEARING.getCode(), null, 0)
        ));
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setLimit(4);

        var suggestions = service(itemDao, accessService).suggest(request);

        assertEquals(3, suggestions.size());
        suggestions.forEach(suggestion -> {
            Set<Integer> categories = suggestion.getItemList().stream()
                    .map(item -> item.getCategory()).collect(Collectors.toSet());
            assertTrue(categories.contains(3)
                    ? !categories.contains(1) && !categories.contains(2)
                    : categories.contains(1) && categories.contains(2));
        });
        assertEquals(suggestions.size(), suggestions.stream()
                .map(suggestion -> suggestion.getItemList().stream()
                        .map(item -> item.getItemId()).sorted().toList())
                .distinct().count());
    }

    @Test
    void aDressIsACompleteOutfitWithoutASecondCategory() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                item(4, 3, WardrobeItemStatusEnum.WEARING.getCode(), null, 0)
        ));
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setLimit(4);

        var suggestions = service(itemDao, accessService).suggest(request);

        assertEquals(1, suggestions.size());
        assertEquals(List.of(4), suggestions.get(0).getItemList().stream()
                .map(item -> item.getItemId()).toList());
    }

    @Test
    void aLockedDressDoesNotMixWithTopsOrBottoms() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        WardrobeItemEntity lockedDress = item(4, 3, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(2, 2, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                lockedDress
        ));
        when(itemDao.queryByIdInOwnerIds(4, List.of(42))).thenReturn(lockedDress);
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setLockedItemId(4);
        request.setLimit(4);

        var suggestions = service(itemDao, accessService).suggest(request);

        assertEquals(1, suggestions.size());
        assertEquals(List.of(4), suggestions.get(0).getItemList().stream()
                .map(item -> item.getItemId()).toList());
    }

    @Test
    void aLockedTopOnlyBuildsSeparateOutfits() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        WardrobeItemEntity lockedTop = item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                lockedTop,
                item(2, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(3, 2, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(4, 3, WardrobeItemStatusEnum.WEARING.getCode(), null, 0)
        ));
        when(itemDao.queryByIdInOwnerIds(1, List.of(42))).thenReturn(lockedTop);
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setLockedItemId(1);
        request.setLimit(4);

        var suggestions = service(itemDao, accessService).suggest(request);

        assertEquals(1, suggestions.size());
        assertEquals(Set.of(1, 3), suggestions.get(0).getItemList().stream()
                .map(item -> item.getItemId()).collect(Collectors.toSet()));
    }

    @Test
    void aLockedOptionalItemIsNotDuplicatedByItsCategory() {
        WardrobeItemDao itemDao = mock(WardrobeItemDao.class);
        WardrobeItemAccessService accessService = mock(WardrobeItemAccessService.class);
        WardrobeItemEntity lockedShoes = item(6, 6, WardrobeItemStatusEnum.WEARING.getCode(), null, 0);
        when(accessService.resolveVisibleOwnerIds(false)).thenReturn(List.of(42));
        when(itemDao.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                item(1, 1, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(2, 2, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                item(3, 3, WardrobeItemStatusEnum.WEARING.getCode(), null, 0),
                lockedShoes,
                item(7, 6, WardrobeItemStatusEnum.WEARING.getCode(), null, 0)
        ));
        when(itemDao.queryByIdInOwnerIds(6, List.of(42))).thenReturn(lockedShoes);
        WardrobeOutfitSuggestDto request = new WardrobeOutfitSuggestDto();
        request.setLockedItemId(6);
        request.setLimit(4);

        var suggestions = service(itemDao, accessService).suggest(request);

        assertEquals(2, suggestions.size());
        suggestions.forEach(suggestion -> {
            assertTrue(suggestion.getItemList().stream().anyMatch(item -> item.getItemId().equals(6)));
            assertEquals(1, suggestion.getItemList().stream().filter(item -> item.getCategory().equals(6)).count());
        });
    }

    private WardrobeOutfitServiceImpl service(WardrobeItemDao itemDao,
                                              WardrobeItemAccessService accessService) {
        return new WardrobeOutfitServiceImpl(
                mock(WardrobeOutfitDao.class),
                mock(WardrobeOutfitItemDao.class),
                itemDao,
                mock(WardrobeWearRecordService.class),
                mock(WardrobeItemImageService.class),
                mock(WardrobeItemService.class),
                accessService
        );
    }

    private WardrobeItemEntity item(int id, int category, int status,
                                    LocalDate lastWearDate, int wearCount) {
        WardrobeItemEntity item = new WardrobeItemEntity();
        item.setId(id);
        item.setCategory(category);
        item.setStatus(status);
        item.setLastWearDate(lastWearDate);
        item.setWearCount(wearCount);
        return item;
    }
}
