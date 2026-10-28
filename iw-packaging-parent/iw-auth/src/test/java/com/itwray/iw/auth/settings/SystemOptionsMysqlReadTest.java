package com.itwray.iw.auth.settings;

import com.itwray.iw.auth.mapper.SystemOptionsMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: local MySQL SELECTs only. Does not create accounts, change roles or write options. */
@EnabledIfEnvironmentVariable(named="IW_SYSTEM_OPTIONS_DB_CHECK",matches="true")
class SystemOptionsMysqlReadTest {
    @Test void actualMysqlSchemaAndMapperCanReadSixTypesWithoutMutation() throws Exception {
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of(System.getProperty("iw.settings.dev-config"))));
        Properties props=Objects.requireNonNull(yaml.getObject());
        DriverManagerDataSource ds=new DriverManagerDataSource();
        ds.setUrl(System.getenv().getOrDefault("IW_DB_URL",props.getProperty("iw.db.url")));
        ds.setUsername(System.getenv().getOrDefault("IW_DB_USERNAME",props.getProperty("iw.db.username")));
        ds.setPassword(System.getenv().getOrDefault("IW_DB_PASSWORD",props.getProperty("iw.db.password")));
        Configuration cfg=new Configuration(new Environment("local-readonly",new JdbcTransactionFactory(),ds));
        cfg.setMapUnderscoreToCamelCase(true); cfg.addMapper(SystemOptionsMapper.class);
        try(SqlSession session=new SqlSessionFactoryBuilder().build(cfg).openSession()) {
            session.getConnection().setReadOnly(true);
            try {
                SystemOptionsMapper mapper=session.getMapper(SystemOptionsMapper.class);
                List<Integer> users=mapper.users(); assertFalse(users.isEmpty());
                int total=0,unknown=0,duplicates=0,structure=0;
                Map<String,Integer> unknownByCode=new TreeMap<>();
                for(var definition:SystemOptionDefinitions.ALL) {
                    var rows=mapper.rows(definition.type()); total+=rows.size();
                    var codes=definition.options().stream().map(SystemOptionDefinitions.Option::code).toList();
                    Set<String> seen=new HashSet<>();
                    for(var row:rows) {
                        assertNotNull(row.getId()); assertNotNull(row.getDictType());
                        if(!codes.contains(row.getDictCode())) { unknown++; unknownByCode.merge(definition.type()+":"+row.getDictCode(),1,Integer::sum); }
                        if(!seen.add(row.getUserId()+":"+row.getDictCode())) duplicates++;
                        if(!Objects.equals(row.getScopeType(),0)||!Objects.equals(row.getFamilyGroupId(),0)||!Objects.equals(row.getParentId(),0)||row.getRevision()==null) structure++;
                    }
                }
                // No identities, labels or credentials in evidence.
                System.out.printf("SYSTEM_OPTIONS_MYSQL_READ rows=%d unknownCodes=%d duplicateCodes=%d structureIssues=%d%n",total,unknown,duplicates,structure);
                System.out.println("SYSTEM_OPTIONS_UNKNOWN_TYPE_CODE_COUNTS "+unknownByCode);
                String evidence=System.getProperty("iw.settings.evidence");
                if(evidence!=null) {
                    Map<String,String> hashes=new LinkedHashMap<>();
                    hashes.put("otherDictionaryRows",hash(session.getConnection(),"SELECT * FROM base_dict WHERE dict_type NOT IN(4005,4006) ORDER BY id"));
                    hashes.put("subscriptionDictionaryIdentities",hash(session.getConnection(),"SELECT id,user_id,scope_type,family_group_id,parent_id,dict_type,dict_code,deleted FROM base_dict WHERE dict_type IN(4005,4006) ORDER BY id"));
                    java.nio.file.Files.writeString(Path.of(evidence),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(hashes));
                }
            } finally { session.rollback(); }
        }
    }
    private String hash(java.sql.Connection connection,String sql) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var statement=connection.prepareStatement(sql); var result=statement.executeQuery()) {
            int count=result.getMetaData().getColumnCount();
            while(result.next()) for(int i=1;i<=count;i++) {
                Object value=result.getObject(i);
                String item=value==null?"NULL":value.toString();
                digest.update((item.length()+":"+item+";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

}
