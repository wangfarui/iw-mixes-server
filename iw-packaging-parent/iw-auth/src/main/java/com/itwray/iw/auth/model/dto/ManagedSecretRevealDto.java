package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "密钥明文查询DTO")
public class ManagedSecretRevealDto {

    @NotNull(message = "id不能为空")
    private Integer id;

    @NotBlank(message = "字段标识不能为空")
    private String fieldCode;
}
