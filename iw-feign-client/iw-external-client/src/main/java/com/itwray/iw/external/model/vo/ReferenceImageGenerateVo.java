package com.itwray.iw.external.model.vo;

import com.itwray.iw.external.model.enums.ReferenceImageErrorCode;
import com.itwray.iw.external.model.enums.ReferenceImageOutcomeType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "参考图生成响应VO")
public class ReferenceImageGenerateVo {

    @Schema(title = "结果类型")
    private ReferenceImageOutcomeType outcome;

    @Schema(title = "生成图片内容")
    private byte[] imageContent;

    @Schema(title = "图片MIME类型")
    private String mimeType;

    @Schema(title = "供应商修订提示词")
    private String revisedPrompt;

    @Schema(title = "错误码")
    private ReferenceImageErrorCode errorCode;

    @Schema(title = "安全错误信息")
    private String message;

    @Schema(title = "实际供应商")
    private String provider;

    @Schema(title = "实际模型")
    private String model;

    public boolean succeeded() {
        return ReferenceImageOutcomeType.SUCCEEDED == this.outcome;
    }
}
