package com.itwray.iw.auth.model.dto;

import com.itwray.iw.web.model.dto.AddDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(name = "密钥新增DTO")
public class ManagedSecretAddDto implements AddDto {

    @NotBlank(message = "密钥名称不能为空")
    @Length(max = 64, message = "密钥名称不能超过64字符")
    private String name;

    @NotBlank(message = "所属服务不能为空")
    @Length(max = 64, message = "所属服务不能超过64字符")
    private String serviceName;

    @NotBlank(message = "密钥类型不能为空")
    @Length(max = 32, message = "密钥类型不能超过32字符")
    private String secretType;

    @NotBlank(message = "使用环境不能为空")
    @Length(max = 16, message = "使用环境不能超过16字符")
    private String environment;

    @Length(max = 255, message = "服务地址不能超过255字符")
    private String address;

    @Valid
    @NotEmpty(message = "至少需要一个密钥字段")
    private List<ManagedSecretFieldDto> fields;

    private LocalDateTime expireTime;

    @Length(max = 1024, message = "标签不能超过1024字符")
    private String tags;

    @Length(max = 500, message = "备注不能超过500字符")
    private String remark;
}
