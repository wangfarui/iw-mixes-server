package com.itwray.iw.external.mapper;

import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.AutomaticSyncTarget;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ZhaogangCodeSyncTargetMapper {

    @Select("""
            select 'SELF' as scope, c.coding_team_id as codingTeamId, c.coding_user_id as scopeId
              from external_zhaogang_coding_credential c
             where c.token_plaintext <> ''
            union all
            select 'WORKBENCH_TEAM' as scope, t.coding_team_id as codingTeamId, t.id as scopeId
              from external_zhaogang_workbench_team t
             where exists (
                   select 1 from external_zhaogang_workbench_team_member m
                     join external_zhaogang_coding_credential c on c.coding_user_id = m.coding_user_id
                    where m.team_id = t.id and c.coding_team_id = t.coding_team_id
                      and m.managed_statistics = 0 and c.token_plaintext <> ''
             )
                or (exists (select 1 from external_zhaogang_workbench_team_token mt where mt.team_id = t.id)
                    and exists (select 1 from external_zhaogang_workbench_team_member m
                                 where m.team_id = t.id and m.managed_statistics = 1))
             order by codingTeamId, scopeId, scope
            """)
    List<AutomaticSyncTarget> selectAutomaticTargets();
}
