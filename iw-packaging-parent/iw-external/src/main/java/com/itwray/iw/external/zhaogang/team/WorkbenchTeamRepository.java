package com.itwray.iw.external.zhaogang.team;

import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.MemberProfile;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.UpdateMemberRoleCommand;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.TeamEntity;

import java.util.List;
import java.util.Optional;

interface WorkbenchTeamRepository {

    List<StoredTeam> findByMember(long userId);

    void reorder(long userId, List<Long> teamIds);

    Optional<StoredTeam> findById(long teamId);

    Optional<StoredTeam> findByInviteCode(String inviteCode);

    Optional<StoredTeam> findByRequestId(String requestId);

    StoredTeam create(String requestId, String name, String inviteCode, Actor actor);

    StoredTeam rename(long teamId, int versionNo, Actor actor, String name);

    StoredTeam join(long teamId, Actor actor);

    List<MemberProfile> findMemberProfiles(long teamId, boolean includeActivity);

    StoredTeam updateMemberRole(long teamId, Actor actor, long targetUserId, UpdateMemberRoleCommand command);

    StoredTeam removeMember(long teamId, int versionNo, Actor actor, long targetUserId);

    StoredTeam transferAdministrator(long teamId, int versionNo, Actor actor, long successorUserId);

    void leave(long teamId, int versionNo, Actor actor, Long successorUserId);

    void dissolve(long teamId, int versionNo, Actor actor);

    void refreshProfile(Actor actor);

    record StoredTeam(TeamEntity team, List<MemberEntity> members) {
    }
}
