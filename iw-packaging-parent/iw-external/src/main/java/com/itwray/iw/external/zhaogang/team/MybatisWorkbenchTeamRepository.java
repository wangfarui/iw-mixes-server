package com.itwray.iw.external.zhaogang.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.external.mapper.ZhaogangWorkbenchTeamMapper;
import com.itwray.iw.external.mapper.ZhaogangWorkbenchTeamMemberMapper;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.MemberEntity;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.TeamEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Repository
class MybatisWorkbenchTeamRepository implements WorkbenchTeamRepository {

    private final ZhaogangWorkbenchTeamMapper teamMapper;
    private final ZhaogangWorkbenchTeamMemberMapper memberMapper;

    MybatisWorkbenchTeamRepository(ZhaogangWorkbenchTeamMapper teamMapper,
                                   ZhaogangWorkbenchTeamMemberMapper memberMapper) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public List<StoredTeam> findByMember(long userId) {
        return teamMapper.selectByMember(userId).stream().map(this::load).toList();
    }

    @Override
    @Transactional
    public void reorder(long userId, List<Long> teamIds) {
        List<Long> current = memberMapper.selectTeamIds(userId);
        if (current.size() != teamIds.size() || !new HashSet<>(current).equals(new HashSet<>(teamIds))) {
            throw new WorkbenchTeamException("团队列表已变化，请刷新后重试");
        }
        for (int index = 0; index < teamIds.size(); index++) {
            if (memberMapper.updateSortNo(userId, teamIds.get(index), index + 1) == 0) {
                throw new WorkbenchTeamException("团队列表已变化，请刷新后重试");
            }
        }
    }

    @Override
    public Optional<StoredTeam> findById(long teamId) {
        return Optional.ofNullable(teamMapper.selectById(teamId)).map(this::load);
    }

    @Override
    public Optional<StoredTeam> findByInviteCode(String inviteCode) {
        TeamEntity entity = teamMapper.selectOne(new LambdaQueryWrapper<TeamEntity>()
                .eq(TeamEntity::getInviteCode, inviteCode));
        return Optional.ofNullable(entity).map(this::load);
    }

    @Override
    public Optional<StoredTeam> findByRequestId(String requestId) {
        TeamEntity entity = teamMapper.selectOne(new LambdaQueryWrapper<TeamEntity>()
                .eq(TeamEntity::getRequestId, requestId));
        return Optional.ofNullable(entity).map(this::load);
    }

    @Override
    @Transactional
    public StoredTeam create(String requestId, String name, String inviteCode, Actor actor) {
        TeamEntity entity = new TeamEntity();
        entity.setRequestId(requestId);
        entity.setName(name);
        entity.setInviteCode(inviteCode);
        entity.setCodingTeamId(actor.codingTeamId());
        entity.setCodingTeamKey(actor.codingTeamKey());
        entity.setCodingTeamHost(actor.codingTeamHost());
        entity.setCreatorUserId(actor.userId());
        entity.setAdministratorUserId(actor.userId());
        entity.setVersionNo(1);
        teamMapper.insert(entity);
        memberMapper.insertIgnore(entity.getId(), actor.userId(), actor.userName(), actor.avatar());
        return require(entity.getId());
    }

    @Override
    @Transactional
    public StoredTeam rename(long teamId, int versionNo, Actor actor, String name) {
        requireChanged(teamMapper.renameAsAdministrator(teamId, actor.userId(), versionNo, name));
        return require(teamId);
    }

    @Override
    @Transactional
    public StoredTeam join(long teamId, Actor actor) {
        int inserted = memberMapper.insertIgnore(teamId, actor.userId(), actor.userName(), actor.avatar());
        if (inserted > 0) {
            if (teamMapper.touch(teamId) == 0) {
                throw new WorkbenchTeamException("团队不存在或已解散");
            }
        } else {
            memberMapper.updateProfile(actor.userId(), actor.userName(), actor.avatar());
        }
        return require(teamId);
    }

    @Override
    @Transactional
    public StoredTeam removeMember(long teamId, int versionNo, Actor actor, long targetUserId) {
        requireChanged(teamMapper.touchAsAdministrator(teamId, actor.userId(), versionNo));
        if (memberMapper.deleteMember(teamId, targetUserId) == 0) {
            throw new WorkbenchTeamException("成员不存在或已退出团队");
        }
        return require(teamId);
    }

    @Override
    @Transactional
    public StoredTeam transferAdministrator(long teamId, int versionNo, Actor actor, long successorUserId) {
        requireChanged(teamMapper.transferAdministrator(teamId, actor.userId(), versionNo, successorUserId));
        return require(teamId);
    }

    @Override
    @Transactional
    public void leave(long teamId, int versionNo, Actor actor, Long successorUserId) {
        if (successorUserId != null) {
            requireChanged(teamMapper.transferAdministrator(teamId, actor.userId(), versionNo, successorUserId));
        }
        if (memberMapper.deleteMember(teamId, actor.userId()) == 0) {
            throw new WorkbenchTeamException("当前用户不是团队成员");
        }
        if (successorUserId == null) {
            requireChanged(teamMapper.touchWithVersion(teamId, versionNo));
        }
    }

    @Override
    @Transactional
    public void dissolve(long teamId, int versionNo, Actor actor) {
        requireChanged(teamMapper.dissolve(teamId, actor.userId(), versionNo));
        memberMapper.deleteByTeamId(teamId);
    }

    @Override
    public void refreshProfile(Actor actor) {
        memberMapper.updateProfile(actor.userId(), actor.userName(), actor.avatar());
    }

    private StoredTeam require(long teamId) {
        return findById(teamId).orElseThrow(() -> new WorkbenchTeamException("团队不存在或已解散"));
    }

    private StoredTeam load(TeamEntity team) {
        List<MemberEntity> members = memberMapper.selectList(new LambdaQueryWrapper<MemberEntity>()
                        .eq(MemberEntity::getTeamId, team.getId()))
                .stream().sorted(Comparator.comparing(MemberEntity::getCreateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(MemberEntity::getId))
                .toList();
        return new StoredTeam(team, members);
    }

    private void requireChanged(int changed) {
        if (changed == 0) {
            throw new WorkbenchTeamException("团队数据已变化，请刷新后重试");
        }
    }
}
