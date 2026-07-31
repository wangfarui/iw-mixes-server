package com.itwray.iw.wardrobe.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WardrobeAssistantServiceImplTest {

    @Test
    void coldWeatherUsesTheSeasonOfTheCurrentDate() {
        assertEquals("summer", WardrobeAssistantServiceImpl.inferOutfitSeason(
                "明天通勤，偏冷，简约一点", LocalDate.of(2026, 7, 31)));
        assertEquals("winter", WardrobeAssistantServiceImpl.inferOutfitSeason(
                "下雨降温", LocalDate.of(2026, 1, 15)));
    }

    @Test
    void explicitSeasonOverridesTheSeasonOfTheCurrentDate() {
        assertEquals("winter", WardrobeAssistantServiceImpl.inferOutfitSeason(
                "准备冬天通勤穿", LocalDate.of(2026, 7, 31)));
        assertEquals("summer", WardrobeAssistantServiceImpl.inferOutfitSeason(
                "夏季室内有点冷", LocalDate.of(2026, 1, 15)));
    }
}
