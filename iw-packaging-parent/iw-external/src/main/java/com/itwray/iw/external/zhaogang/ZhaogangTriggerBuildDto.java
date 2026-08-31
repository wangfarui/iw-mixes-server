package com.itwray.iw.external.zhaogang;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ZhaogangTriggerBuildDto {

    @NotBlank(message = "请选择构建分支")
    @Size(max = 255)
    private String branch;

    @NotBlank(message = "请选择发布环境")
    @Size(max = 100)
    private String environment;
}
