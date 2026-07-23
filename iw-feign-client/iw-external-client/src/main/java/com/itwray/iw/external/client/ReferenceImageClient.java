package com.itwray.iw.external.client;

import com.itwray.iw.external.core.InternalFeignConfig;
import com.itwray.iw.external.model.ExternalClientConstants;
import com.itwray.iw.external.model.dto.ReferenceImageGenerateDto;
import com.itwray.iw.external.model.vo.ReferenceImageGenerateVo;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Validated
@FeignClient(
        value = ExternalClientConstants.SERVICE_NAME,
        contextId = "reference-image-client",
        url = "${iw.remote.external.base-url}",
        path = ExternalClientConstants.INTERNAL_PATH_PREFIX,
        configuration = InternalFeignConfig.class
)
public interface ReferenceImageClient {

    @PostMapping("/ai/reference-image/generate")
    ReferenceImageGenerateVo generate(@RequestBody @Valid ReferenceImageGenerateDto dto);
}
