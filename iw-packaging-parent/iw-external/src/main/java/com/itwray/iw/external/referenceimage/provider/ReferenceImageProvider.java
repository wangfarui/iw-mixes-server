package com.itwray.iw.external.referenceimage.provider;

import com.itwray.iw.external.referenceimage.ExecutionMetadata;
import com.itwray.iw.external.referenceimage.GenerationOutcome;
import com.itwray.iw.external.referenceimage.ReferenceImageCommand;

public interface ReferenceImageProvider {

    String id();

    String defaultApiBaseUrl();

    String defaultModel();

    GenerationOutcome generate(ReferenceImageCommand command, ProviderContext context);

    default ExecutionMetadata metadata(ProviderContext context) {
        return new ExecutionMetadata(this.id(), context.model());
    }
}
