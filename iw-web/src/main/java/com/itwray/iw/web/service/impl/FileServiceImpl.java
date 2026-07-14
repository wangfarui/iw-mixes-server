package com.itwray.iw.web.service.impl;

import cn.hutool.core.io.file.FileNameUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.PutObjectRequest;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.config.IwAliyunProperties;
import com.itwray.iw.web.exception.IwWebException;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.web.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件服务实现层
 *
 * @author wray
 * @since 2024/5/11
 */
@Service
@Slf4j
public class FileServiceImpl implements FileService {

    private IwAliyunProperties iwAliyunProperties;

    @Autowired
    public void setIwAliyunProperties(IwAliyunProperties iwAliyunProperties) {
        this.iwAliyunProperties = iwAliyunProperties;
    }

    @Override
    public FileRecordVo upload(MultipartFile file) {
        OSS ossClient = null;
        try {
            // 创建OSSClient实例。
            ossClient = new OSSClientBuilder().build(this.getOSS().getEndpoint(), this.getOSS().getAccessKeyId(), this.getOSS().getAccessKeySecret());
            // 填写Object完整路径，完整路径中不能包含Bucket名称，例如exampledir/exampleobject.txt。
            String uuid = UUID.randomUUID().toString();
            String fileSuffix = FileNameUtil.getSuffix(file.getOriginalFilename());
            String mainName = FileNameUtil.mainName(file.getOriginalFilename());
            String objectName = this.getOSS().getUploadParentDir() + "/" + getNowDateDir() + "/" + mainName + "_" + uuid + "." + fileSuffix;
            // 创建PutObjectRequest对象。
            PutObjectRequest putObjectRequest = new PutObjectRequest(this.getOSS().getBucketName(), objectName, file.getInputStream());
            // 发起PutObject请求。
            ossClient.putObject(putObjectRequest);

            // 构建文件记录VO对象
            FileRecordVo fileRecordVo = new FileRecordVo();
            fileRecordVo.setFileName(file.getOriginalFilename());
            fileRecordVo.setFileUri("/" + objectName);
            fileRecordVo.setFilePrefix(this.getOSS().getBaseUrl());
            fileRecordVo.setFileSuffix(fileSuffix);
            fileRecordVo.setFileUrl(fileRecordVo.getFilePrefix() + fileRecordVo.getFileUri());
            return fileRecordVo;
        } catch (OSSException oe) {
            log.error("OSS文件上传客户端异常", oe);
            throw new IwWebException("文件上传异常");
        } catch (IOException ce) {
            log.error("OSS文件上传文件流异常", ce);
            throw new IwWebException("文件上传异常");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    @Override
    public void delete(String fileUrl) {
        if (StringUtils.isBlank(fileUrl)) {
            return;
        }
        OSS ossClient = null;
        try {
            String objectName = this.resolveObjectName(fileUrl);
            ossClient = new OSSClientBuilder().build(this.getOSS().getEndpoint(), this.getOSS().getAccessKeyId(), this.getOSS().getAccessKeySecret());
            ossClient.deleteObject(this.getOSS().getBucketName(), objectName);
        } catch (OSSException e) {
            log.error("OSS文件删除客户端异常, fileUrl: {}", fileUrl, e);
            throw new IwWebException("文件删除异常");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    protected IwAliyunProperties.OSS getOSS() {
        return iwAliyunProperties.getOss();
    }

    private String resolveObjectName(String fileUrl) {
        String normalizedUrl = StringUtils.substringBefore(StringUtils.substringBefore(fileUrl.trim(), "?"), "#");
        String baseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(this.getOSS().getBaseUrl()), "/");
        String objectName;
        if (StringUtils.isNotBlank(baseUrl) && normalizedUrl.startsWith(baseUrl + "/")) {
            objectName = normalizedUrl.substring(baseUrl.length() + 1);
        } else if (normalizedUrl.startsWith("/")) {
            objectName = normalizedUrl.substring(1);
        } else {
            throw new IwWebException("文件地址不属于当前OSS");
        }
        if (StringUtils.isBlank(objectName)) {
            throw new IwWebException("文件地址无效");
        }
        return objectName;
    }

    /**
     * 获取当天日期的字符串
     *
     * @return {@link DateUtils#DATE_FORMAT}
     */
    private String getNowDateDir() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
