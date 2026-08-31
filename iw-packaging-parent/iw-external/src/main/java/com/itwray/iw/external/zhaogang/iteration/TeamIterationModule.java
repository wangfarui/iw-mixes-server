package com.itwray.iw.external.zhaogang.iteration;

import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.AddReleasePlanCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateChildIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CreateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationDetail;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationListItem;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationIssue;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueCreationOptions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IssueWorklog;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationQuery;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.PageResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReplaceMembersCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RegisterWorklogCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReleasePlan;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.RemoveIssuesCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.SelectionOption;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingIssueType;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.CodingSyncResult;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.StageCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UpdateIssueStatusCommand;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UserSnapshot;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.IterationTeamOption;

import java.util.List;

public interface TeamIterationModule {

    PageResult<IterationListItem> list(Actor actor, IterationQuery query);

    IterationDetail create(Actor actor, CreateCommand command);

    IterationDetail detail(Actor actor, long iterationId);

    IterationDetail update(Actor actor, long iterationId, UpdateCommand command);

    IterationDetail transition(Actor actor, long iterationId, StageCommand command);

    IterationDetail replaceMembers(Actor actor, long iterationId, ReplaceMembersCommand command);

    void delete(Actor actor, long iterationId);

    List<UserSnapshot> teamMembers(Actor actor, String keyword);

    List<IterationTeamOption> iterationMemberOptions(Actor actor);

    IterationIssue addCodingIssue(Actor actor, long iterationId, CodingIssueCommand command);

    IterationIssue addChildIssue(Actor actor, long iterationId, long parentIssueId,
                                 CreateChildIssueCommand command);

    IssueCreationOptions issueCreationOptions(Actor actor, long iterationId, long parentIssueId,
                                              CodingIssueType issueType);

    IssueCreationOptions issueEditOptions(Actor actor, long iterationId, long issueId);

    List<SelectionOption> issueStatusOptions(Actor actor, long iterationId, long issueId);

    IterationIssue updateIssue(Actor actor, long iterationId, long issueId, UpdateIssueCommand command);

    IterationIssue updateIssueStatus(Actor actor, long iterationId, long issueId, UpdateIssueStatusCommand command);

    IterationIssue syncIssue(Actor actor, long iterationId, long issueId);

    CodingSyncResult syncCodingIssues(Actor actor, long iterationId);

    IssueWorklog registerWorklog(Actor actor, long iterationId, long issueId, RegisterWorklogCommand command);

    IssueWorklog retryWorklog(Actor actor, long iterationId, long issueId, long worklogId);

    void removeIssue(Actor actor, long iterationId, long issueId);

    void removeIssues(Actor actor, long iterationId, RemoveIssuesCommand command);

    ReleasePlan addReleasePlan(Actor actor, long iterationId, AddReleasePlanCommand command);

    void removeReleasePlan(Actor actor, long iterationId, long releasePlanId);
}
