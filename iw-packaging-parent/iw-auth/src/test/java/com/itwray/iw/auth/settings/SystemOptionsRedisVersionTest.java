package com.itwray.iw.auth.settings;

import com.itwray.iw.starter.redis.RedisUtil;
import com.itwray.iw.starter.redis.config.IwRedisAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Redis test uses one disposable, uniquely named key; never a user's cache/version key. */
@EnabledIfEnvironmentVariable(named="IW_SYSTEM_OPTIONS_DB_CHECK",matches="true")
class SystemOptionsRedisVersionTest {
    @Test void productionSerializerSupportsDistinctAtomicNumericVersions() throws Exception {
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of(System.getProperty("iw.settings.dev-config"))));
        Properties p=Objects.requireNonNull(yaml.getObject());
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration(
            System.getenv().getOrDefault("IW_REDIS_HOST",p.getProperty("iw.redis.host","127.0.0.1")),
            Integer.parseInt(System.getenv().getOrDefault("IW_REDIS_PORT",p.getProperty("iw.redis.port","6379"))));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("IW_REDIS_DATABASE",p.getProperty("iw.redis.database","0"))));
        String password=System.getenv().getOrDefault("IW_REDIS_PASSWORD",p.getProperty("iw.redis.password",""));
        if(!password.isBlank()) config.setPassword(password);
        var previousField=RedisUtil.class.getDeclaredField("redisTemplate"); previousField.setAccessible(true);
        @SuppressWarnings("unchecked") RedisTemplate<String,Object> previous=(RedisTemplate<String,Object>)previousField.get(null);
        LettuceConnectionFactory factory=new LettuceConnectionFactory(config); factory.afterPropertiesSet();
        RedisTemplate<String,Object> template=new IwRedisAutoConfiguration().customRedisTemplate(factory); template.afterPropertiesSet();
        String key="iw:test:system-options:"+UUID.randomUUID();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            long seed=System.currentTimeMillis(); template.opsForValue().set(key,seed,60,TimeUnit.SECONDS);
            Set<Long> versions=ConcurrentHashMap.newKeySet();
            Callable<Void> worker=() -> { for(int i=0;i<50;i++) versions.add(template.opsForValue().increment(key,1)); return null; };
            var first=pool.submit(worker); var second=pool.submit(worker);
            first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
            assertEquals(100,versions.size());
            assertEquals(seed+100,((Number)Objects.requireNonNull(template.opsForValue().get(key))).longValue());
            assertTrue(Objects.requireNonNull(template.getExpire(key))>0);
            assertEquals(seed+100,RedisUtil.get(key,Number.class).longValue());
            // INCR on an absent key starts at 1; Jackson reads small values as Integer.
            template.delete(key);
            assertEquals(1L,template.opsForValue().increment(key,1));
            assertEquals(1L,RedisUtil.get(key,Number.class).longValue());
            System.out.println("SYSTEM_OPTIONS_REDIS_VERSION concurrentDistinctVersions=100 numericProtocol=passed smallVersion=passed disposableKeyCleanup=finally");
        } finally {
            pool.shutdownNow(); template.delete(key); RedisUtil.setRedisTemplate(previous); factory.destroy();
        }
    }
}
