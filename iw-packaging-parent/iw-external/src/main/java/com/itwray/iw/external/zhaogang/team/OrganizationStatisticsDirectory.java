package com.itwray.iw.external.zhaogang.team;

import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Current organization membership, independent of the viewer's own team list. */
@Repository
public class OrganizationStatisticsDirectory {
    public record Membership(long teamId, long userId, String name, String avatar, String role,
                             boolean ignored, boolean managed, String personalFingerprint,
                             String managementFingerprint) {
        public boolean codeCredentialAvailable() {
            return managed ? managementFingerprint != null : personalFingerprint != null;
        }

        @Override
        public String toString() {
            return "Membership[teamId=" + teamId + ", userId=" + userId + "]";
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DefaultWorkbenchTeamModule teams;

    OrganizationStatisticsDirectory(NamedParameterJdbcTemplate jdbc, DefaultWorkbenchTeamModule teams) {
        this.jdbc = jdbc;
        this.teams = teams;
    }

    public List<Membership> memberships(long org) {
        Map<Long, String> managers = new HashMap<>();
        return jdbc.query("""
                SELECT m.team_id, m.coding_user_id, m.user_name, m.avatar, p.coding_role,
                    m.ignore_team_statistics, m.managed_statistics, c.token_fingerprint, mt.token_ciphertext
                FROM external_zhaogang_workbench_team t
                JOIN external_zhaogang_workbench_team_member m ON m.team_id = t.id
                LEFT JOIN external_zhaogang_workbench_profile p
                    ON p.coding_team_id = t.coding_team_id AND p.coding_user_id = m.coding_user_id
                LEFT JOIN external_zhaogang_coding_credential c
                    ON c.coding_team_id = t.coding_team_id AND c.coding_user_id = m.coding_user_id
                    AND c.token_plaintext <> ''
                LEFT JOIN external_zhaogang_workbench_team_token mt ON mt.team_id = t.id
                WHERE t.coding_team_id = :org
                ORDER BY m.team_id, m.coding_user_id
                """, Map.of("org", org), (rs, n) -> {
            long team = rs.getLong("team_id");
            if (!managers.containsKey(team)) {
                managers.put(team, managementFingerprint(rs.getString("token_ciphertext")));
            }
            return new Membership(team, rs.getLong("coding_user_id"), rs.getString("user_name"),
                    rs.getString("avatar"), rs.getString("coding_role"), rs.getBoolean("ignore_team_statistics"),
                    rs.getBoolean("managed_statistics"), rs.getString("token_fingerprint"), managers.get(team));
        });
    }

    private String managementFingerprint(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            String token = teams.decryptManagementToken(ciphertext);
            return token.isBlank() ? null : CodingCredentialService.fingerprint(token);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
