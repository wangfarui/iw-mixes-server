package com.itwray.iw.auth.model.dto;

import com.itwray.iw.web.model.dto.PageDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "密钥分页DTO")
public class ManagedSecretPageDto extends PageDto {

    private String keyword;

    private String secretType;

    private String environment;

    /** 1有效，2即将过期，3已过期。 */
    private Integer expiryStatus;
}
