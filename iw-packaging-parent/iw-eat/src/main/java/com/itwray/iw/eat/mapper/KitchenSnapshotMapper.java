package com.itwray.iw.eat.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KitchenSnapshotMapper {
    @IgnorePermission
    @Select("SELECT phase FROM kitchen_dictionary_migration_state WHERE id=1")
    String migrationPhase();
}
