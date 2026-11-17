package com.itwray.iw.external;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IwExternalConfigurationTest {
    @Test
    void springCanLoadApplicationYamlWithSkillsAndExternalServiceSettings() throws Exception {
        var defaults = new YamlPropertySourceLoader()
                .load("iw-external", new ClassPathResource("application.yml"))
                .get(0);

        assertEquals("http://127.0.0.1:${server.port}", defaults.getProperty("iw.remote.external.base-url"));
        assertEquals("${IW_ZHAOGANG_SKILLS_LOCAL_DIRECTORY:}", defaults.getProperty("iw.zhaogang.skills.local-directory"));
    }

    @Test
    void skillsUseLocalStorageByDefaultOnlyInDev() throws Exception {
        var profiles = new YamlPropertySourceLoader()
                .load("iw-external", new ClassPathResource("application.yml"));
        var dev = profiles.stream()
                .filter(profile -> "dev".equals(profile.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();
        var prod = profiles.stream()
                .filter(profile -> "prod".equals(profile.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();

        assertEquals("${IW_ZHAOGANG_SKILLS_LOCAL_DIRECTORY:logs/local/skills-packages}",
                dev.getProperty("iw.zhaogang.skills.local-directory"));
        assertNull(prod.getProperty("iw.zhaogang.skills.local-directory"));
    }
}
