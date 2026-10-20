package com.itwray.iw.bookkeeping.model.dto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import java.util.List;
/** JSON presence is independent of null: omitted retains, null/[] clears. */
@Getter
public class BookkeepingDictionaryChangesDto {
    private Integer categoryId;
    private List<Integer> tagIds;
    @JsonIgnore private boolean categoryPresent;
    @JsonIgnore private boolean tagsPresent;
    @JsonSetter("categoryId") public void setCategoryId(Integer value) {categoryPresent=true;categoryId=value;}
    @JsonSetter("tagIds") public void setTagIds(List<Integer> value) {tagsPresent=true;tagIds=value;}
}
