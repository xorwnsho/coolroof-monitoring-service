package com.coolroof.monitoring.ai;

import java.util.List;

public record ChatCompletionResponse(List<Choice> choices) {
    public record Choice(ChatMessage message) {
    }
}
