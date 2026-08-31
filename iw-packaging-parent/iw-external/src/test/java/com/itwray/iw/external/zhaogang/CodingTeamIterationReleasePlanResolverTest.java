package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodingTeamIterationReleasePlanResolverTest {

    private final Actor actor = new Actor(100L, "creator", "avatar", "token", "g-iijw5014");

    @Test
    void resolvesProjectAndPlanFromCurrentUsersCodingScope() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.projects("token", 100L)).thenReturn(List.of(new CodingProject(11L, "project-a", "项目 A")));
        when(coding.plans("token", 11L)).thenReturn(List.of(new CodingPlan(
                22L, "sit-build", 11L, 33L, "GIT", "test", List.of("sit"), true, List.of(), null)));
        CodingTeamIterationReleasePlanResolver resolver = new CodingTeamIterationReleasePlanResolver(coding);

        var resolved = resolver.resolve(actor, 11L, 22L);

        assertThat(resolved.projectDisplayName()).isEqualTo("项目 A");
        assertThat(resolved.planName()).isEqualTo("sit-build");
        assertThat(resolved.quickBuildSupported()).isTrue();
    }

    @Test
    void fallsBackToProjectNameWhenDisplayNameIsBlank() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.projects("token", 100L)).thenReturn(List.of(new CodingProject(11L, "project-a", " ")));
        when(coding.plans("token", 11L)).thenReturn(List.of(new CodingPlan(
                22L, "sit-build", 11L, 33L, "GIT", "test", List.of("sit"), true, List.of(), null)));
        CodingTeamIterationReleasePlanResolver resolver = new CodingTeamIterationReleasePlanResolver(coding);

        var resolved = resolver.resolve(actor, 11L, 22L);

        assertThat(resolved.projectDisplayName()).isEqualTo("project-a");
    }

    @Test
    void rejectsProjectOutsideCurrentUsersCodingScope() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        when(coding.projects("token", 100L)).thenReturn(List.of());
        CodingTeamIterationReleasePlanResolver resolver = new CodingTeamIterationReleasePlanResolver(coding);

        assertThatThrownBy(() -> resolver.resolve(actor, 11L, 22L))
                .isInstanceOf(CodingOpenApiException.class)
                .hasMessageContaining("无法访问该项目");
    }
}
