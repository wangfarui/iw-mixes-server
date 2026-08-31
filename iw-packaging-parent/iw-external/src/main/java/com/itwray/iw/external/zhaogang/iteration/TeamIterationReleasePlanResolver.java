package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;

public interface TeamIterationReleasePlanResolver {

    ResolvedReleasePlan resolve(Actor actor, long projectId, long planId);

    record ResolvedReleasePlan(long projectId, String projectName, String projectDisplayName,
                               long planId, String planName, boolean quickBuildSupported) {
    }
}
