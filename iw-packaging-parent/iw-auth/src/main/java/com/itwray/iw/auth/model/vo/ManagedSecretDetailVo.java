package com.itwray.iw.auth.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.model.vo.DetailVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(name = "密钥详情VO")
public class ManagedSecretDetailVo implements DetailVo {

    private Integer id;
    private String name;
    private String serviceName;
    private String secretType;
    private String environment;
    private String address;
    private List<ManagedSecretFieldVo> fields;
    private LocalDateTime expireTime;
    private String tags;
    private String remark;

    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime lastAccessTime;

    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = DateUtils.DATETIME_FORMAT)
    private LocalDateTime updateTime;
}
