package com.coolroof.monitoring.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("response_format") ResponseFormat responseFormat
) {

    public ChatCompletionRequest(String model, List<ChatMessage> messages, Double temperature) {
        this(model, messages, temperature, null);
    }

    /** OpenAI JSON 모드 지정용. {@code new ResponseFormat("json_object")}. */
    public record ResponseFormat(String type) {
    }
}
