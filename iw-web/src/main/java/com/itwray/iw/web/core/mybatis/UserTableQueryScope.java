package com.itwray.iw.web.core.mybatis;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

/** A business table can separate query ownership from its maintenance owner. */
public interface UserTableQueryScope {
    boolean supports(String tableName);

    Expression build(Table table, Integer userId, Integer groupId, boolean shared);
}
