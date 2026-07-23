package com.itwray.iw.external.referenceimage;

import com.itwray.iw.external.model.enums.ReferenceImageErrorCode;

public sealed interface GenerationOutcome permits GenerationOutcome.Success, GenerationOutcome.Failure {

    ExecutionMetadata metadata();

    record Success(GeneratedImage image, ExecutionMetadata metadata) implements GenerationOutcome {
    }

    record Failure(ReferenceImageErrorCode errorCode, String message,
                   ExecutionMetadata metadata) implements GenerationOutcome {
    }
}
