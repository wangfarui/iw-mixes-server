alter table external_zhaogang_iteration
    add column board_order bigint default 0 not null comment '看板内排序值，越小越靠前' after stage,
    add key idx_team_stage_order (team_key, stage, board_order, id);

update external_zhaogang_iteration
   set board_order = -(unix_timestamp(update_time) * 1000 + mod(id, 1000))
 where board_order = 0;
