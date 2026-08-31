package com.itwray.iw.external.zhaogang.iteration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingIssueUrlParserTest {

    private final CodingIssueUrlParser parser = new CodingIssueUrlParser();

    @Test
    void parsesRequirementAndCustomIssueRoutesWithoutDependingOnTypeSegment() {
        CodingIssueUrlParser.ParsedIssueUrl requirement = parser.parse(
                "https://g-iijw5014.coding.net/p/project-a/requirements/issues/7234/detail", "g-iijw5014");
        CodingIssueUrlParser.ParsedIssueUrl story = parser.parse(
                "https://g-iijw5014.coding.net/p/project-a/user-stories/issues/8123/detail", "g-iijw5014");

        assertThat(requirement.projectName()).isEqualTo("project-a");
        assertThat(requirement.issueCode()).isEqualTo(7234L);
        assertThat(story.issueCode()).isEqualTo(8123L);
    }

    @Test
    void rejectsOtherTeamsAndNonIssueLinks() {
        assertThatThrownBy(() -> parser.parse(
                "https://other.coding.net/p/project/requirements/issues/1/detail", "g-iijw5014"))
                .isInstanceOf(TeamIterationException.class);
        assertThatThrownBy(() -> parser.parse(
                "https://g-iijw5014.coding.net/p/project/all/issues", "g-iijw5014"))
                .isInstanceOf(TeamIterationException.class);
    }
}
