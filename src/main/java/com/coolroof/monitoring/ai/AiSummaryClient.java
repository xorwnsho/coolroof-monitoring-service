package com.coolroof.monitoring.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI Chat Completions API로 배치 분석 결과를 자연어로 요약한다.
 * 수치·판정은 배치가 이미 확정한 값이며, AI는 해석·문장화만 담당한다 (환각 방지 원칙).
 */
@Component
public class AiSummaryClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            너는 쿨루프(옥상 반사도료) 시공 건물의 성능 모니터링 결과를 요약하는 어시스턴트다.
            아래 규칙을 반드시 지켜라.
            - 입력으로 주어진 수치와 판정(status)은 이미 통계 로직이 확정한 값이다. 새로운 수치를 만들어내거나 판정을 스스로 바꾸지 마라.
            - 네 역할은 그 수치를 해석해서 한국어 2~3문장으로 요약하고 행동 방향을 제안하는 것뿐이다.
            - 정확한 비용, 시공업체 등 주어지지 않은 정보를 지어내지 마라.
            """;

    private final RestClient restClient = RestClient.create();

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    public String summarize(AiSummaryRequest request) {
        ChatCompletionRequest body = new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessage("system", SYSTEM_PROMPT),
                        new ChatMessage("user", buildUserPrompt(request))),
                0.3);

        ChatCompletionResponse response = restClient.post()
                .uri(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        return response.choices().get(0).message().content();
    }

    private String buildUserPrompt(AiSummaryRequest r) {
        return """
                건물명: %s
                용도: %s
                쿨루프 시공일: %s
                판정(status): %s
                최근 90일 평균 표면-외기 온도차(avgTempGap): %.2f℃
                동종 건물군 평균 온도차(peerGap): %s
                연간 온도차 상승률(degradationTrend): %s
                예상 재도장 필요 시점(repaintForecast): %s

                위 정보를 바탕으로 이 건물의 쿨루프 성능 상태와 권장 행동방향을 한국어 2~3문장으로 요약해줘.
                """.formatted(
                r.buildingName(),
                r.usageType(),
                r.coolroofDate(),
                r.status(),
                r.avgTempGap(),
                r.peerGap() == null ? "비교 대상 없음" : r.peerGap() + "℃",
                r.degradationTrend() == null ? "산출 불가" : r.degradationTrend() + "℃/년",
                r.repaintForecast() == null ? "해당 없음" : r.repaintForecast().toString());
    }
}
