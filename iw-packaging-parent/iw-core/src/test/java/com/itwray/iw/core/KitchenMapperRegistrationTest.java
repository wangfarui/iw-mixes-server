package com.itwray.iw.core;

import com.itwray.iw.web.core.mybatis.MybatisPlusConfiguration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitchenMapperRegistrationTest {

    @Test
    void productionMapperScanRegistersKitchenSnapshotMapper() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry);
        scanner.registerFilters();
        scanner.scan(MybatisPlusConfiguration.class.getAnnotation(MapperScan.class).basePackages());

        assertTrue(registry.containsBeanDefinition("kitchenSnapshotMapper"),
                "KitchenContractConfiguration requires the snapshot mapper during startup");
        assertEquals(MapperFactoryBean.class.getName(),
                registry.getBeanDefinition("kitchenSnapshotMapper").getBeanClassName());
    }
}
