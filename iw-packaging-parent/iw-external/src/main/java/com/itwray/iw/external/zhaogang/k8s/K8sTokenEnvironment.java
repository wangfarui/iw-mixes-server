package com.itwray.iw.external.zhaogang.k8s;

import java.util.Locale;

public enum K8sTokenEnvironment {
    TEST("test"), UAT("uat"), PRD("prd");

    private final String code;

    K8sTokenEnvironment(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static K8sTokenEnvironment parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("环境必须是 test、uat 或 prd");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (K8sTokenEnvironment item : values()) {
            if (item.code.equals(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("环境必须是 test、uat 或 prd");
    }
}
