package com.itwray.iw.auth.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.model.vo.PageRecordVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(name = "密钥分页VO")
public class ManagedSecretPageVo implements PageRecordVo {

    private Integer id;
    private String name;
    private String serviceName;
    private String secretType;
    private String environment;
    private String fieldSummary;
    private LocalDateTime expireTime;
    private String tags;

    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime lastAccessTime;

    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime updateTime;
}
