package com.itwray.iw.external.service.impl;

import com.itwray.iw.external.model.vo.AiImageReferenceGenerateVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIServiceImplTest {

    @Test
    void resolveOpenAiImagesEditsUrlNormalizesChatCompletionEndpoint() {
        AIServiceImpl service = createOpenAiService("https://ai.example.com/v1/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveOpenAiImagesEditsUrl");

        assertEquals("https://ai.example.com/v1/images/edits", url);
    }

    @Test
    void resolveOpenAiImagesEditsUrlNormalizesResponsesEndpoint() {
        AIServiceImpl service = createOpenAiService("https://ai.example.com/v1/responses");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveOpenAiImagesEditsUrl");

        assertEquals("https://ai.example.com/v1/images/edits", url);
    }

    @Test
    void resolveOpenAiImagesEditsUrlFallsBackFromDefaultDeepSeekEndpoint() {
        AIServiceImpl service = createOpenAiService("https://api.deepseek.com/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveOpenAiImagesEditsUrl");

        assertEquals("https://api.openai.com/v1/images/edits", url);
    }

    @Test
    void resolveOpenAiImageModelDefaultsToImageModel() {
        AIServiceImpl service = createOpenAiService("https://ai.example.com/v1/images/edits");

        String model = ReflectionTestUtils.invokeMethod(service, "resolveOpenAiImageModel");

        assertEquals("gpt-image-2", model);
    }

    @Test
    void parseOpenAiImageResponseReadsB64Json() {
        AIServiceImpl service = new AIServiceImpl();
        String body = """
                {
                  "created": 1780000000,
                  "output_format": "png",
                  "data": [
                    {
                      "b64_json": "base64-openai-image",
                      "revised_prompt": "优化后的提示词"
                    }
                  ]
                }
                """;

        AiImageReferenceGenerateVo vo = ReflectionTestUtils.invokeMethod(service, "parseOpenAiImageResponse", body);

        assertEquals("completed", vo.getStatus());
        assertEquals("base64-openai-image", vo.getImageBase64());
        assertEquals("image/png", vo.getMimeType());
        assertEquals("优化后的提示词", vo.getRevisedPrompt());
    }

    @Test
    void resolveGeminiGenerateContentUrlNormalizesInteractionsEndpoint() {
        AIServiceImpl service = createGeminiService("https://ai.example.com/v1/interactions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveGeminiGenerateContentUrl");

        assertEquals("https://ai.example.com/v1beta/models/gemini-2.5-flash:generateContent", url);
    }

    @Test
    void resolveGeminiGenerateContentUrlNormalizesOpenAiCompatibleEndpoint() {
        AIServiceImpl service = createGeminiService("https://ai.example.com/v1/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveGeminiGenerateContentUrl");

        assertEquals("https://ai.example.com/v1beta/models/gemini-2.5-flash:generateContent", url);
    }

    @Test
    void resolveGeminiGenerateContentUrlNormalizesGeminiOpenAiEndpoint() {
        AIServiceImpl service = createGeminiService("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveGeminiGenerateContentUrl");

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", url);
    }

    @Test
    void resolveGeminiGenerateContentUrlFallsBackFromDefaultDeepSeekEndpoint() {
        AIServiceImpl service = createGeminiService("https://api.deepseek.com/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveGeminiGenerateContentUrl");

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", url);
    }

    @Test
    void resolveGeminiGenerateContentUrlKeepsCustomChatCompletionDomain() {
        AIServiceImpl service = createGeminiService("https://ai.example.com/v1/chat/completions");
        ReflectionTestUtils.setField(service, "defaultApiUrl", "https://ai.example.com/v1/chat/completions");

        String url = ReflectionTestUtils.invokeMethod(service, "resolveGeminiGenerateContentUrl");

        assertEquals("https://ai.example.com/v1beta/models/gemini-2.5-flash:generateContent", url);
    }

    @Test
    void parseGeminiImageResponseReadsGenerateContentInlineData() {
        AIServiceImpl service = new AIServiceImpl();
        String body = """
                {
                  "responseId": "response-1",
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "已优化提示词" },
                          {
                            "inlineData": {
                              "mimeType": "image/png",
                              "data": "base64-image"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        AiImageReferenceGenerateVo vo = ReflectionTestUtils.invokeMethod(service, "parseGeminiImageResponse", body);

        assertEquals("response-1", vo.getTaskId());
        assertEquals("completed", vo.getStatus());
        assertEquals("base64-image", vo.getImageBase64());
        assertEquals("image/png", vo.getMimeType());
        assertEquals("已优化提示词", vo.getRevisedPrompt());
    }

    private AIServiceImpl createGeminiService(String imageApiUrl) {
        AIServiceImpl service = new AIServiceImpl();
        ReflectionTestUtils.setField(service, "defaultApiUrl", "https://api.deepseek.com/chat/completions");
        ReflectionTestUtils.setField(service, "imageApiUrl", imageApiUrl);
        ReflectionTestUtils.setField(service, "imageModel", "gemini-2.5-flash");
        return service;
    }

    private AIServiceImpl createOpenAiService(String imageApiUrl) {
        AIServiceImpl service = new AIServiceImpl();
        ReflectionTestUtils.setField(service, "defaultApiUrl", "https://api.deepseek.com/chat/completions");
        ReflectionTestUtils.setField(service, "imageApiUrl", imageApiUrl);
        return service;
    }
}
