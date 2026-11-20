package com.itwray.iw.external.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ZhaogangWorklogSyncTargetMapper {
  record Target(long codingTeamId, long userId, long workbenchTeamId) {}

  @Select(
      """
            SELECT c.coding_team_id AS codingTeamId,c.coding_user_id AS userId,0 AS workbenchTeamId
            FROM external_zhaogang_coding_credential c WHERE c.token_plaintext<>''
            UNION ALL
            SELECT t.coding_team_id AS codingTeamId,0 AS userId,t.id AS workbenchTeamId
            FROM external_zhaogang_workbench_team t
            WHERE EXISTS (SELECT 1 FROM external_zhaogang_workbench_team_token mt WHERE mt.team_id=t.id)
               OR EXISTS (SELECT 1 FROM external_zhaogang_workbench_team_member m
                  JOIN external_zhaogang_coding_credential c ON c.coding_team_id=t.coding_team_id AND c.coding_user_id=m.coding_user_id
                  WHERE m.team_id=t.id AND c.token_plaintext<>'')
            """)
  List<Target> targets();

  // A single deterministic management source for a member, shared by all Dev Hub teams.
  @Select(
      """
      SELECT MIN(t.id) FROM external_zhaogang_workbench_team t
      JOIN external_zhaogang_workbench_team_member m ON m.team_id=t.id
      JOIN external_zhaogang_workbench_team_token mt ON mt.team_id=t.id
      WHERE t.coding_team_id=#{codingTeamId} AND m.coding_user_id=#{userId}
        AND m.ignore_team_statistics=0
      """)
  Long managementTeamForMember(
      @Param("codingTeamId") long codingTeamId, @Param("userId") long userId);
}
