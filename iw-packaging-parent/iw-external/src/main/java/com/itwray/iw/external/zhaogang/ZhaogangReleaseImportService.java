package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Plan;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.ZhaogangModels.Project;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiConfigService;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.AddReleasePlanCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationDetail;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReleasePlan;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModule;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.AddItem;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddFailure;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddResult;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Candidate;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.MatchCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.MatchRow;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Preview;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.RecognizedRow;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Status;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportPrompt;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ZhaogangReleaseImportService {

    private static final Pattern PLAN_MARKER = Pattern.compile("\\.(service|ui)", Pattern.CASE_INSENSITIVE);

    private final ZhaogangAiConfigService aiConfig;
    private final ZhaogangCatalogService catalogService;
    private final TeamIterationModule iterationModule;
    private final ObjectMapper objectMapper;
    private final ZhaogangProperties properties;

    public ZhaogangReleaseImportService(ZhaogangAiConfigService aiConfig, ZhaogangCatalogService catalogService,
                                        TeamIterationModule iterationModule, ObjectMapper objectMapper,
                                        ZhaogangProperties properties) {
        this.aiConfig = aiConfig;
        this.catalogService = catalogService;
        this.iterationModule = iterationModule;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Preview recognizeAndMatch(Actor actor, long iterationId, byte[] image, String contentType,
                                     String projectColumnName, String planColumnName) {
        if (image == null || image.length == 0 || image.length > properties.getAiMaxImageBytes()) {
            throw new IllegalArgumentException("截图不能为空且不能超过 10 MB");
        }
        JsonNode response = aiConfig.vision(actor.codingTeamId(), actor.userId(), image,
                contentType, ReleaseImportPrompt.build(projectColumnName, planColumnName));
        String content = ZhaogangAiConfigService.content(response);
        List<RecognizedRow> rows = parseRows(content);
        return match(actor, iterationId, rows);
    }

    public Preview match(Actor actor, long iterationId, MatchCommand command) {
        if (command == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("AI 未识别到可用的发布项目行");
        }
        return match(actor, iterationId, command.items());
    }

    public BatchAddResult add(Actor actor, long iterationId, BatchAddCommand command) {
        if (command == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("请选择要添加的发布项目");
        }
        List<BatchAddFailure> failures = new ArrayList<>();
        int success = 0;
        for (AddItem item : command.items()) {
            if (item == null || item.projectId() <= 0 || item.planId() <= 0) {
                failures.add(new BatchAddFailure(item == null ? 0 : item.rowNo(),
                        item == null ? 0 : item.projectId(), item == null ? 0 : item.planId(), "项目或构建计划无效"));
                continue;
            }
            try {
                // addReleasePlan performs the final real-time project/plan/PAT validation.
                iterationModule.addReleasePlan(actor, iterationId, new AddReleasePlanCommand(item.projectId(), item.planId()));
                success++;
            } catch (RuntimeException error) {
                failures.add(new BatchAddFailure(item.rowNo(), item.projectId(), item.planId(),
                        StringUtils.defaultIfBlank(error.getMessage(), "添加失败")));
            }
        }
        return new BatchAddResult(success, failures.size(), failures);
    }

    private Preview match(Actor actor, long iterationId, List<RecognizedRow> rows) {
        PlanCatalog catalog;
        IterationDetail detail;
        try {
            catalog = catalogService.catalog(new ZhaogangSession(actor.token(), actor.userId(), actor.userName(),
                    actor.avatar(), actor.teamKey(), actor.codingTeamId()));
            detail = iterationModule.detail(actor, iterationId);
        } catch (RuntimeException error) {
            return new Preview(rows.stream().map((row) -> new MatchRow(0, row, Status.CATALOG_UNAVAILABLE,
                    null, null, null, null, List.of(), "CODING 项目和构建计划目录暂不可用")).toList());
        }
        Set<String> imageKeys = new LinkedHashSet<>();
        Set<String> addedKeys = new HashSet<>();
        for (ReleasePlan plan : detail.releasePlans()) {
            addedKeys.add(key(plan.projectId(), plan.planId()));
        }
        List<MatchRow> result = new ArrayList<>();
        int rowNo = 1;
        for (RecognizedRow row : rows) {
            List<Project> projects = findProjects(catalog.projects(), projectHint(row));
            if (projects.size() != 1) {
                result.add(new MatchRow(rowNo++, row, projects.isEmpty() ? Status.UNMATCHED : Status.PROJECT_AMBIGUOUS,
                        null, null, null, null, projects.stream().map(project -> new Candidate(project.id(), project.name(),
                                project.displayName(), 0, "", false)).toList(), projects.isEmpty() ? "未匹配到 CODING 项目" : "匹配到多个 CODING 项目"));
                continue;
            }
            Project project = projects.get(0);
            List<Plan> allPlans = catalog.plans().stream().filter(plan -> plan.projectId() == project.id()).toList();
            List<Plan> plans = findPlans(allPlans.stream().filter(Plan::quickBuildSupported).toList(), planHint(row));
            if (plans.isEmpty()) {
                plans = findPlans(allPlans.stream().filter(plan -> !plan.quickBuildSupported()).toList(), planHint(row));
            }
            if (plans.size() != 1) {
                Status status = plans.isEmpty() ? Status.UNMATCHED : Status.PLAN_AMBIGUOUS;
                result.add(new MatchRow(rowNo++, row, status, project.id(), project.name(), null, null,
                        plans.stream().map(plan -> candidate(project, plan)).toList(), plans.isEmpty() ? "未匹配到构建计划" : "匹配到多个构建计划"));
                continue;
            }
            Plan plan = plans.get(0);
            String key = key(project.id(), plan.id());
            Status status = !plan.quickBuildSupported() ? Status.UNBUILDABLE
                    : addedKeys.contains(key) ? Status.ALREADY_ADDED
                    : !imageKeys.add(key) ? Status.DUPLICATE_IN_IMAGE : Status.READY;
            result.add(new MatchRow(rowNo++, row, status, project.id(), project.name(), plan.id(), plan.name(),
                    List.of(candidate(project, plan)), status == Status.READY ? "" : statusMessage(status)));
        }
        return new Preview(result);
    }

    private List<RecognizedRow> parseRows(String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("AI 未返回识别结果");
        }
        try {
            JsonNode root = objectMapper.readTree(content.getBytes(StandardCharsets.UTF_8));
            JsonNode rows = root.isArray() ? root : root.path("rows").isArray() ? root.path("rows") : root.path("items");
            if (!rows.isArray()) throw new IllegalArgumentException("AI 响应缺少 rows 数组");
            List<RecognizedRow> result = new ArrayList<>();
            rows.forEach(row -> result.add(new RecognizedRow(text(row, "requirement", "需求描述", "project"),
                    text(row, "ops", "systemOps", "系统所属OPS", "系统所属 Ops"),
                    text(row, "systemName", "system", "系统名字"),
                    text(row, "projectHint", "codingProject", "projectName"),
                    text(row, "planHint", "buildPlan", "planName"))));
            return result.stream().filter(row -> StringUtils.isNotBlank(projectHint(row)) || StringUtils.isNotBlank(planHint(row))).toList();
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("AI 响应不是有效的 JSON 识别结果");
        }
    }

    private List<Project> findProjects(List<Project> projects, String hint) {
        String normalized = normalizeProject(hint);
        if (normalized.isBlank()) return List.of();
        return projects.stream().filter(project -> normalizeProject(project.name()).equals(normalized)
                || normalizeProject(project.displayName()).equals(normalized)).toList();
    }

    private List<Plan> findPlans(List<Plan> plans, String hint) {
        Set<String> aliases = planHintAliases(hint);
        if (aliases.isEmpty()) return List.of();
        return plans.stream().filter(plan -> catalogPlanAliases(plan.name()).stream().anyMatch(aliases::contains)).toList();
    }

    private String projectHint(RecognizedRow row) {
        return StringUtils.defaultIfBlank(row.projectHint(), row.ops());
    }

    private String planHint(RecognizedRow row) {
        return StringUtils.defaultIfBlank(row.planHint(), row.systemName());
    }

    private Candidate candidate(Project project, Plan plan) {
        return new Candidate(project.id(), project.name(), project.displayName(), plan.id(), plan.name(), plan.quickBuildSupported());
    }

    private String key(long projectId, long planId) {
        return projectId + ":" + planId;
    }

    private String statusMessage(Status status) {
        return switch (status) {
            case ALREADY_ADDED -> "当前迭代已添加";
            case DUPLICATE_IN_IMAGE -> "截图中重复";
            case UNBUILDABLE -> "该计划不支持快捷构建";
            default -> "需要人工确认";
        };
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name) && StringUtils.isNotBlank(node.path(name).asText())) return node.path(name).asText().trim();
        }
        return "";
    }

    private String normalizeProject(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private Set<String> planHintAliases(String value) {
        return planAliases(value, false);
    }

    private Set<String> catalogPlanAliases(String value) {
        return planAliases(value, true);
    }

    private Set<String> planAliases(String value, boolean includeBaseName) {
        String source = StringUtils.trimToEmpty(value);
        if (source.isBlank()) return Set.of();
        Set<String> aliases = new LinkedHashSet<>();
        addPlanAlias(aliases, source);
        Matcher marker = PLAN_MARKER.matcher(source);
        if (marker.find()) {
            if (includeBaseName) {
                addPlanAlias(aliases, source.substring(0, marker.start()));
            }
            addPlanAlias(aliases, source.substring(0, marker.end()));
        }
        return aliases;
    }

    private void addPlanAlias(Set<String> aliases, String value) {
        String normalized = StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-./()（）【】\\[\\]]", "");
        if (!normalized.isBlank()) {
            aliases.add(normalized);
        }
    }
}
