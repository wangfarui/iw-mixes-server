package com.itwray.iw.wardrobe.mapper;

import com.itwray.iw.web.annotation.IgnorePermission;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WardrobeDetailMapper {
    /** Only these compile-time identifiers can enter the SQL; no HTTP string becomes a table. */
    enum Kind {
        OUTFIT("wardrobe_outfit_item", "outfit_id"), WEAR_RECORD("wardrobe_wear_record_item", "record_id");
        public final String table, head;
        Kind(String table, String head) { this.table = table; this.head = head; }
    }
    @IgnorePermission
    @Select("SELECT * FROM ${kind.table} WHERE ${kind.head}=#{head} AND user_id=#{owner} AND deleted=0 ORDER BY sort,id FOR UPDATE")
    List<WardrobeOutfitItemEntity> lockRows(@Param("kind") Kind kind, @Param("head") Integer head, @Param("owner") Integer owner);
    @IgnorePermission
    @Update("UPDATE ${kind.table} SET deleted=1,update_time=NOW() WHERE id=#{id} AND ${kind.head}=#{head} AND user_id=#{owner} AND deleted=0")
    int remove(@Param("kind") Kind kind, @Param("head") Integer head, @Param("owner") Integer owner, @Param("id") Integer id);
    @IgnorePermission
    @Update("UPDATE ${kind.table} SET sort=#{sort},update_time=NOW() WHERE id=#{id} AND ${kind.head}=#{head} AND user_id=#{owner} AND deleted=0")
    int sort(@Param("kind") Kind kind, @Param("head") Integer head, @Param("owner") Integer owner, @Param("id") Integer id, @Param("sort") int sort);
    @IgnorePermission
    @Insert("""
        INSERT INTO ${kind.table}(${kind.head},user_id,item_id,item_name,item_image,category,item_style,sort,
         category_source_id,category_name_snapshot,category_match_kind_snapshot,item_style_source_id,item_style_name_snapshot,dictionary_snapshot_status)
        VALUES(#{head},#{owner},#{row.itemId},#{row.itemName},#{row.itemImage},#{row.category},#{row.itemStyle},#{sort},
         #{row.categorySourceId},#{row.categoryNameSnapshot},#{row.categoryMatchKindSnapshot},#{row.itemStyleSourceId},#{row.itemStyleNameSnapshot},1)
        """)
    void insert(@Param("kind") Kind kind, @Param("head") Integer head, @Param("owner") Integer owner,
                @Param("row") WardrobeOutfitItemEntity row, @Param("sort") int sort);
}
