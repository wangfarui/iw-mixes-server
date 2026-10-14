package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.web.core.mybatis.UserTableQueryScope;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.springframework.stereotype.Component;

/** Personal cash flows plus the current family's voluntarily shared records. */
@Component
public class BookkeepingQueryScope implements UserTableQueryScope {
    @Override
    public boolean supports(String tableName) {
        return "bookkeeping_records".equalsIgnoreCase(tableName.replace("`", ""));
    }

    @Override
    public Expression build(Table table, Integer userId, Integer groupId, boolean shared) {
        String prefix = (table.getAlias() == null ? table.getName() : table.getAlias().getName()) + ".";
        String owner = "((" + prefix + "record_category = 1 AND COALESCE(" + prefix
                + "expense_user_id, " + prefix + "user_id) = " + userId + ") OR (" + prefix
                + "record_category = 2 AND " + prefix + "user_id = " + userId + "))";
        if (shared && groupId != null && groupId > 0) {
            owner = "(" + owner + " OR (" + prefix + "group_id = " + groupId
                    + " AND " + prefix + "share_state = 1))";
        }
        try {
            return CCJSqlParserUtil.parseCondExpression(owner);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成记账数据权限条件", e);
        }
    }
}
