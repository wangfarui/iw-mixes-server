package com.itwray.iw.web.bookkeeping;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictionaryTagSnapshot {
    private Integer id;
    private Integer type;
    private String name;
}
