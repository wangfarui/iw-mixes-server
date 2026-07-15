package com.itwray.iw.auth.model.dto;

import com.itwray.iw.web.model.dto.UpdateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "密钥更新DTO")
public class ManagedSecretUpdateDto extends ManagedSecretAddDto implements UpdateDto {

    @NotNull(message = "id不能为空")
    private Integer id;
}
