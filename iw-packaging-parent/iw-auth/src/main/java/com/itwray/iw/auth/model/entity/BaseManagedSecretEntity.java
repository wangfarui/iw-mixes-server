package com.itwray.iw.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户密钥管理表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("base_managed_secret")
public class BaseManagedSecretEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    private String serviceName;

    private String secretType;

    private String environment;

    private String address;

    /** 非敏感字段定义 JSON。 */
    private String fieldSchema;

    /** AES-GCM 加密后的字段值 JSON。 */
    private String secretCiphertext;

    private LocalDateTime expireTime;

    private String tags;

    private String remark;

    private LocalDateTime lastAccessTime;

    private Integer encryptionVersion;
}
