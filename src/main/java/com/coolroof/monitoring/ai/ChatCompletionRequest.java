package com.coolroof.monitoring.ai;

import java.util.List;

public record ChatCompletionRequest(String model, List<ChatMessage> messages, Double temperature) {
}
