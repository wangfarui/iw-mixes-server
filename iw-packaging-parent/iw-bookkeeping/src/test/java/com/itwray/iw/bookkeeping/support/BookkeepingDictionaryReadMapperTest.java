package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.auth.mapper.BookkeepingDictionaryMapper;
import com.itwray.iw.auth.service.impl.BookkeepingDictionaryService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual mapper SQL in an isolated database, including competing writer locks. */
class BookkeepingDictionaryReadMapperTest {
    @Test void readsDoNotWaitForWriterLocksAndCannotFollowForeignRenameChains() throws Exception {
        var dataSource=new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:quick_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=100");
        try(var connection=dataSource.getConnection(); var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE auth_user(id INT PRIMARY KEY,family_group_id INT,deleted INT)");
            sql.execute("CREATE TABLE auth_family_group(id INT PRIMARY KEY,status INT,deleted INT)");
            sql.execute("CREATE TABLE auth_family_member(group_id INT,user_id INT,role INT,status INT,deleted INT)");
            sql.execute("CREATE TABLE bookkeeping_family_dict_state(family_group_id INT PRIMARY KEY)");
            sql.execute("CREATE TABLE base_dict(id INT PRIMARY KEY,dict_name VARCHAR(64),dict_type INT,dict_status INT,scope_type INT,user_id INT,family_group_id INT,deleted INT,sort INT,previous_id INT)");
            sql.execute("INSERT INTO auth_user VALUES(7,3,0),(8,0,0)");
            sql.execute("INSERT INTO auth_family_group VALUES(3,1,0)");
            sql.execute("INSERT INTO auth_family_member VALUES(3,7,1,1,0)");
            sql.execute("INSERT INTO bookkeeping_family_dict_state VALUES(3)");
            sql.execute("INSERT INTO base_dict VALUES(10,'旧餐饮',4002,1,1,0,3,1,1,NULL),(11,'新餐饮',4002,1,1,0,3,0,1,10),(20,'外卖',4001,1,0,7,0,0,1,NULL),(30,'外人分类',4002,1,0,8,0,0,1,NULL),(40,'断链',4002,1,0,7,0,1,1,NULL),(41,'外人后继',4002,1,0,8,0,0,1,40)");
        }
        var configuration=new Configuration(new Environment("test",new JdbcTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BookkeepingDictionaryMapper.class);
        String resource="mapper/BookkeepingDictionaryMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(configuration);
        try(var locker=dataSource.getConnection(); var sql=locker.createStatement(); var session=factory.openSession()) {
            locker.setAutoCommit(false);
            sql.executeUpdate("UPDATE auth_user SET family_group_id=family_group_id WHERE id=7");
            sql.executeUpdate("UPDATE auth_family_group SET status=status WHERE id=3");
            sql.executeUpdate("UPDATE auth_family_member SET role=role WHERE user_id=7");
            sql.executeUpdate("UPDATE bookkeeping_family_dict_state SET family_group_id=family_group_id WHERE family_group_id=3");
            sql.executeUpdate("UPDATE base_dict SET dict_status=dict_status");
            var mapper=session.getMapper(BookkeepingDictionaryMapper.class);
            var reader=mapper.reader(7);
            assertEquals(new BookkeepingDictionaryMapper.Reader(7,3,3,1,3),reader);
            assertEquals(new BookkeepingDictionaryMapper.Reader(8,0,null,null,null),mapper.reader(8));
            assertNull(mapper.reader(99));
            var service=new BookkeepingDictionaryService(mapper);
            assertEquals(List.of(11),service.options(7,4002).stream().map(row->row.getId()).toList());
            var resolved=service.resolveSuccessors(7,List.of(10,20,30,40));
            assertEquals(11,resolved.get(10).getId()); assertEquals(20,resolved.get(20).getId());
            assertFalse(resolved.containsKey(30),"其它用户来源不可读取");
            assertFalse(resolved.containsKey(40),"改名链不可跨用户");
            locker.rollback();
        }
    }
}
