package com.itwray.iw.external.zhaogang.skills;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.internal.OSSUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillPackageStoreTest {
    @ParameterizedTest
    @CsvSource({
            "https://example.oss-cn-heyuan.aliyuncs.com, example.oss-cn-heyuan.aliyuncs.com",
            "https://oss-cn-heyuan.aliyuncs.com, example.oss-cn-heyuan.aliyuncs.com",
            "https://example.oss-cn-heyuan-internal.aliyuncs.com/, example.oss-cn-heyuan-internal.aliyuncs.com",
            "https://example.oss-accelerate.aliyuncs.com, example.oss-accelerate.aliyuncs.com"
    })
    void sdkAddressesBucketOnlyOnce(String endpoint, String expectedHost) {
        var store = new SkillPackageStore(endpoint, "example", "test-access-key", "test-secret-key", "");
        try {
            var client = (OSSClient) ReflectionTestUtils.getField(store, "oss");
            // Use the SDK's real request address calculation, without making an external request.
            var requestEndpoint = OSSUtils.determineFinalEndpoint(
                    client.getEndpoint(), "example", client.getClientConfiguration());
            assertEquals(expectedHost, requestEndpoint.getHost());
        } finally {
            store.close();
        }
    }
}
