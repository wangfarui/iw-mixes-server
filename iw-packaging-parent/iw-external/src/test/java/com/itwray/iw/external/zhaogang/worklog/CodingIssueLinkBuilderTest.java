package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodingIssueLinkBuilderTest {

    private final CodingIssueLinkBuilder builder = new CodingIssueLinkBuilder();

    @Test
    void mapsKnownTypesAndFallsBackForUnknownTypes() {
        assertThat(builder.build("https://g-iijw5014.coding.net", "project", 10L,
                new Issue(10L, "REQUIREMENT", "需求", "title", "project", false)))
                .isEqualTo("https://g-iijw5014.coding.net/p/project/requirements/issues/10/detail");
        assertThat(builder.build("https://g-iijw5014.coding.net", "project", 11L,
                new Issue(11L, "CUSTOM", "自定义", "title", "project", false)))
                .isEqualTo("https://g-iijw5014.coding.net/p/project/all/issues");
    }

    @Test
    void usesSubtaskRouteBeforeMissionRoute() {
        assertThat(builder.build("https://g-iijw5014.coding.net", "project", 12L,
                new Issue(12L, "MISSION", "子任务", "title", "project", true)))
                .endsWith("/subtasks/issues/12/detail");
    }
}
