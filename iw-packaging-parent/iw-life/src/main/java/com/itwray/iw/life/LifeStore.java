package com.itwray.iw.life;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

/** All identifiers come from the closed Kind enum. All user values are bound parameters. */
@Repository
class LifeStore {
    enum Kind {
        WISH("life_wish"), DATE("life_important_date"), BOOKMARK("life_bookmark");
        final String table; Kind(String table) { this.table=table; }
    }
    private final JdbcTemplate jdbc;
    LifeStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    List<Map<String,Object>> rows(Kind kind,int owner) {
        return jdbc.query("SELECT * FROM "+kind.table+" WHERE user_id=? AND deleted=0",LifeStore::row,owner);
    }
    Map<String,Object> find(Kind kind,int owner,int id) {
        var rows=jdbc.query("SELECT * FROM "+kind.table+" WHERE user_id=? AND id=? AND deleted=0",LifeStore::row,owner,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    Map<String,Object> request(Kind kind,int owner,String requestId,boolean currentRead) {
        var rows=jdbc.query("SELECT * FROM "+kind.table+" WHERE user_id=? AND client_request_id=?"+(currentRead?" FOR UPDATE":""),LifeStore::row,owner,requestId);
        return rows.isEmpty()?null:rows.get(0);
    }
    int insert(Kind kind,Map<String,Object> columns) {
        String sql="INSERT INTO "+kind.table+" ("+String.join(",",columns.keySet())+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);int i=1;for(Object value:columns.values())statement.setObject(i++,value);return statement;},key);
        return Objects.requireNonNull(key.getKey()).intValue();
    }
    int update(Kind kind,int owner,int id,int revision,Map<String,Object> columns) {
        List<Object> params=new ArrayList<>(columns.values());Collections.addAll(params,owner,id,revision);
        return jdbc.update("UPDATE "+kind.table+" SET "+String.join(",",columns.keySet().stream().map(k->k+"=?").toList())+",revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE user_id=? AND id=? AND revision=? AND deleted=0",params.toArray());
    }
    private static Map<String,Object> row(ResultSet rs,int index)throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();ResultSetMetaData meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            StringBuilder key=new StringBuilder();boolean upper=false;
            for(char c:meta.getColumnLabel(i).toCharArray()) { if(c=='_')upper=true;else {key.append(upper?Character.toUpperCase(c):Character.toLowerCase(c));upper=false;} }
            Object value=rs.getObject(i);
            if(value instanceof java.sql.Date date)value=date.toLocalDate();
            if(value instanceof Timestamp time)value=time.toLocalDateTime();
            result.put(key.toString(),value);
        }
        return result;
    }
}
