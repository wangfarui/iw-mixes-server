package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationReleasePlanResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
class CodingTeamIterationReleasePlanResolver implements TeamIterationReleasePlanResolver {

    private final CodingOpenApiPort coding;

    CodingTeamIterationReleasePlanResolver(CodingOpenApiPort coding) {
        this.coding = coding;
    }

    @Override
    public ResolvedReleasePlan resolve(Actor actor, long projectId, long planId) {
        CodingProject project = coding.projects(actor.token(), actor.userId()).stream()
                .filter(item -> item.id() == projectId)
                .findFirst()
                .orElseThrow(() -> new CodingOpenApiException("当前账号未加入或无法访问该项目"));
        CodingPlan plan = coding.plans(actor.token(), projectId).stream()
                .filter(item -> item.id() == planId)
                .findFirst()
                .orElseThrow(() -> new CodingOpenApiException("构建计划不存在或当前账号无权访问"));
        String projectDisplayName = StringUtils.defaultIfBlank(project.displayName(), project.name());
        return new ResolvedReleasePlan(project.id(), project.name(), projectDisplayName, plan.id(), plan.name(),
                plan.quickBuildSupported());
    }
}
