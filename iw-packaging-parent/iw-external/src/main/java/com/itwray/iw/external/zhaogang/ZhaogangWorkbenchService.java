package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.Branch;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Build;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanDetail;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Project;
import com.itwray.iw.external.zhaogang.ZhaogangModels.SessionStatus;
import com.itwray.iw.external.zhaogang.ZhaogangModels.TokenValue;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
class ZhaogangWorkbenchService {

    private final CodingOpenApiPort coding;

    private final ZhaogangProperties properties;

    private final CodingCredentialService credentials;

    ZhaogangWorkbenchService(CodingOpenApiPort coding, ZhaogangProperties properties) {
        this(coding, properties, null);
    }

    @Autowired
    ZhaogangWorkbenchService(CodingOpenApiPort coding, ZhaogangProperties properties,
                             CodingCredentialService credentials) {
        this.coding = coding;
        this.properties = properties;
        this.credentials = credentials;
    }

    ZhaogangSession bind(String token) {
        return bind(token, true);
    }

    private ZhaogangSession bind(String token, boolean persistCredential) {
        CodingUser user = coding.currentUser(token);
        if (user.id() <= 0) {
            throw new CodingOpenApiException("CODING 用户信息不完整");
        }
        CodingOpenApiPort.Team team = coding.team(token);
        if (user.teamId() > 0 && team.id() > 0 && user.teamId() != team.id()) {
            throw new CodingOpenApiException("CODING 用户与当前团队不匹配");
        }
        if (user.teamId() <= 0) {
            throw new CodingOpenApiException("CODING 用户团队信息不完整");
        }
        long codingTeamId = team.id() > 0 ? team.id() : user.teamId();
        if (persistCredential && credentials != null) {
            credentials.upsert(codingTeamId, user.id(), token, user.name(), user.avatar());
        }
        return new ZhaogangSession(token, user.id(), user.name(), user.avatar(), properties.getTeam(), codingTeamId);
    }

    ZhaogangSession repairSession(ZhaogangSession session) {
        if (session.teamId() != null && session.teamId() > 0
                && StringUtils.isNotBlank(session.userName()) && !session.userName().contains("?")
                && !session.userName().contains("\uFFFD")) {
            return session;
        }
        // Legacy cookies are revalidated and upgraded, but must still force the
        // user through the explicit token rotation flow before creating a backup.
        return bind(session.token(), false);
    }

    SessionStatus status(ZhaogangSession session) {
        boolean tokenRotationRequired = credentials != null
                && (session.teamId() == null || session.teamId() <= 0
                || !credentials.exists(session.teamId(), session.userId()));
        return new SessionStatus(true, session.userId(), session.userName(), session.avatar(), session.team(),
                maskToken(session.token()), tokenRotationRequired, List.of());
    }

    TokenValue tokenValue(ZhaogangSession session) {
        return new TokenValue(session.token());
    }

    List<Project> projects(ZhaogangSession session) {
        return coding.projects(session.token(), session.userId()).stream()
                .map(project -> new Project(project.id(), project.name(), project.displayName()))
                .sorted(Comparator.comparing(Project::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    List<Plan> plans(ZhaogangSession session, long projectId) {
        CodingProject project = coding.projects(session.token(), session.userId()).stream()
                .filter(item -> item.id() == projectId)
                .findFirst()
                .orElseThrow(() -> new CodingOpenApiException("当前账号未加入或无法访问该项目"));
        return coding.plans(session.token(), projectId).stream()
                .map(plan -> toPlan(plan, project))
                .sorted(Comparator.comparing(Plan::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    PlanDetail planDetail(ZhaogangSession session, long projectId, long jobId) {
        CodingProject project = coding.projects(session.token(), session.userId()).stream()
                .filter(item -> item.id() == projectId)
                .findFirst()
                .orElseThrow(() -> new CodingOpenApiException("当前账号未加入或无法访问该项目"));
        CodingPlan codingPlan = coding.plan(session.token(), projectId, jobId);
        List<Build> builds = coding.builds(session.token(), projectId, jobId).stream().map(this::toBuild).toList();
        return new PlanDetail(toPlan(codingPlan, project), builds, codingPlan.depotId());
    }

    List<Branch> branches(ZhaogangSession session, long projectId, long jobId, String keyword) {
        CodingPlan plan = coding.plan(session.token(), projectId, jobId);
        if (plan.depotId() == null) {
            return List.of();
        }
        return coding.branches(session.token(), projectId, plan.depotId(), plan.depotType(), keyword).stream()
                .map(branch -> new Branch(branch.name()))
                .toList();
    }

    Build triggerBuild(ZhaogangSession session, long projectId, long jobId, ZhaogangTriggerBuildDto dto) {
        CodingPlan plan = coding.plan(session.token(), projectId, jobId);
        if (!plan.quickBuildSupported()) {
            throw new CodingOpenApiException("此计划存在非标准启动参数，请前往 CODING 发起构建");
        }
        return toBuild(coding.triggerBuild(session.token(), projectId, jobId, dto.getBranch().trim(),
                dto.getEnvironment().trim(), plan.defaultParameters()));
    }

    private Plan toPlan(CodingPlan plan, CodingProject project) {
        return new Plan(plan.id(), project.id(), project.name(), project.displayName(), plan.name(), plan.defaultBranch(),
                plan.environments(), plan.quickBuildSupported(), toBuild(plan.latestBuild()));
    }

    private Build toBuild(CodingBuild build) {
        if (build == null) {
            return null;
        }
        return new Build(build.id(), build.number(), build.status(), build.statusDetail(), build.branch(), build.commit(),
                build.triggerUser(), build.duration(), build.startedAt(), build.environment());
    }

    private String maskToken(String token) {
        if (StringUtils.isBlank(token)) {
            return "";
        }
        String normalized = token.trim();
        if (normalized.length() <= 8) {
            return "••••" + StringUtils.right(normalized, Math.min(4, normalized.length()));
        }
        return StringUtils.left(normalized, 4) + "••••••••" + StringUtils.right(normalized, 4);
    }

}
