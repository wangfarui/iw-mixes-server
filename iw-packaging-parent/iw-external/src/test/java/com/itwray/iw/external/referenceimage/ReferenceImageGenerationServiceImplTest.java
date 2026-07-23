package com.itwray.iw.external.referenceimage;

import com.itwray.iw.external.model.enums.ReferenceImageErrorCode;
import com.itwray.iw.external.referenceimage.config.ReferenceImageProperties;
import com.itwray.iw.external.referenceimage.provider.ProviderContext;
import com.itwray.iw.external.referenceimage.provider.ReferenceImageProvider;
import com.itwray.iw.external.referenceimage.support.SourceImagePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ReferenceImageGenerationServiceImplTest {

    @Test
    void delegatesToSelectedProviderAndReturnsItsSuccessOutcome() {
        ReferenceImageProperties properties = configuredProperties();
        FakeProvider provider = new FakeProvider();
        ReferenceImageGenerationService service = new ReferenceImageGenerationServiceImpl(
                properties, new SourceImagePolicy(properties), List.of(provider));

        GenerationOutcome outcome = service.generate(
                new ReferenceImageCommand("https://images.example.test/wardrobe/item.png", "优化衣物图片"));

        GenerationOutcome.Success success = assertInstanceOf(GenerationOutcome.Success.class, outcome);
        assertEquals(1, provider.calls);
        assertEquals("fake", success.metadata().provider());
        assertEquals("fake-model", success.metadata().model());
        assertArrayEquals(new byte[]{1, 2, 3}, success.image().content());
    }

    @Test
    void invalidInputIsReturnedAsStableFailureWithoutCallingProvider() {
        ReferenceImageProperties properties = configuredProperties();
        FakeProvider provider = new FakeProvider();
        ReferenceImageGenerationService service = new ReferenceImageGenerationServiceImpl(
                properties, new SourceImagePolicy(properties), List.of(provider));

        GenerationOutcome outcome = service.generate(new ReferenceImageCommand("", "优化衣物图片"));

        GenerationOutcome.Failure failure = assertInstanceOf(GenerationOutcome.Failure.class, outcome);
        assertEquals(ReferenceImageErrorCode.INVALID_INPUT, failure.errorCode());
        assertEquals(0, provider.calls);
    }

    private ReferenceImageProperties configuredProperties() {
        ReferenceImageProperties properties = new ReferenceImageProperties();
        properties.setProvider("fake");
        properties.setApiKey("test-key");
        properties.setSourceBaseUrl("https://images.example.test/wardrobe/");
        return properties;
    }

    private static final class FakeProvider implements ReferenceImageProvider {

        private int calls;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String defaultApiBaseUrl() {
            return "https://provider.example.test";
        }

        @Override
        public String defaultModel() {
            return "fake-model";
        }

        @Override
        public GenerationOutcome generate(ReferenceImageCommand command, ProviderContext context) {
            calls += 1;
            return new GenerationOutcome.Success(new GeneratedImage(new byte[]{1, 2, 3}, "image/png", ""),
                    this.metadata(context));
        }
    }
}
