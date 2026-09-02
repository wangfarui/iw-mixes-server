package com.itwray.iw.external.zhaogang.k8s;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ZhaogangK8sTokenDto {

    @NotBlank
    private String environment;

    @NotBlank
    @Size(max = 4096)
    private String token;
}
