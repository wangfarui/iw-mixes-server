package com.itwray.iw.auth.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 密钥中的一个字段。更新时 value 为空表示保留已保存的值。
 */
@Data
@Schema(name = "密钥字段DTO")
public class ManagedSecretFieldDto {

    @NotBlank(message = "字段标识不能为空")
    @Length(max = 32, message = "字段标识不能超过32字符")
    private String code;

    @NotBlank(message = "字段名称不能为空")
    @Length(max = 64, message = "字段名称不能超过64字符")
    private String label;

    @Length(max = 16, message = "输入类型不能超过16字符")
    private String inputType;

    /** 仅新增或替换字段值时传入，详情接口永不返回此字段。 */
    private String value;
}
