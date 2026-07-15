package com.itwray.iw.auth.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "密钥字段VO")
public class ManagedSecretFieldVo {

    private String code;

    private String label;

    private String inputType;

    /** 仅表示服务端已保存值，真实值不会在详情接口中返回。 */
    private Boolean hasValue;
}
