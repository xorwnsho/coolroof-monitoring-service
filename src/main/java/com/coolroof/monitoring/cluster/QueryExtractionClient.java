package com.coolroof.monitoring.cluster;

import com.coolroof.monitoring.ai.ChatCompletionRequest;
import com.coolroof.monitoring.ai.ChatCompletionResponse;
import com.coolroof.monitoring.ai.ChatMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI Chat Completions API(JSON 모드)로 자연어 문장에서 군집 조건(지역/주구조/층수/주용도)을
 * 추출한다. 지역명·구조명을 정규식/키워드로 하드코딩해 파싱하지 않기 위한 용도 — 문장 표현이
 * 어떻게 오든 LLM이 구조화된 값으로 정리한다.
 */
@Slf4j
@Component
public class QueryExtractionClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            너는 건물 정보가 담긴 한국어 입력에서 아래 조건을 JSON으로 추출하는 어시스턴트다.
            입력은 완전한 문장("대전에 철근콘크리트구조의 8층짜리 빌딩...")일 수도 있고,
            "대전 / 철근콘크리트구조 / 8층"처럼 슬래시나 쉼표로 구분된 키워드 나열일 수도 있다.
            형식과 무관하게 의미상 같은 조건이면 동일하게 추출하라.
            반드시 아래 형식의 JSON 객체 하나만 출력하라. 다른 설명 텍스트를 절대 붙이지 마라.

            {"region": string|null, "structure": string|null, "floors": number|null, "usage": string|null}

            추출 규칙:
            - region: 문장에 언급된 지역명(시/도/구/동 등)을 원문 그대로 추출한다. 언급이 없으면 null.
            - structure: 반드시 아래 5개 값 중 하나로 정규화한다. 문장에서 명확히 판단할 수 없으면 null로 남기고 추측해서 채우지 마라.
              ["철근콘크리트구조", "철골구조", "철골철근콘크리트구조", "조적구조", "목구조"]
            - floors: 건물 층수를 정수로 추출한다. 언급이 없으면 null.
            - usage: 반드시 아래 6개 값 중 하나로 정규화하거나, 명확히 해당하지 않으면 null로 남긴다.
              ["공동주택", "업무시설", "근린생활시설", "공장", "창고시설", "교육연구시설"]
            """;

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    public ClusterQuery extract(String userText) {
        ChatCompletionRequest body = new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessage("system", SYSTEM_PROMPT),
                        new ChatMessage("user", userText)),
                0.0,
                new ChatCompletionRequest.ResponseFormat("json_object"));

        ChatCompletionResponse response = restClient.post()
                .uri(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI 응답이 비어 있습니다.");
        }

        String content = response.choices().get(0).message().content();
        try {
            JsonNode node = objectMapper.readTree(content);
            String region = textOrNull(node, "region");
            String structure = textOrNull(node, "structure");
            Integer floors = node.hasNonNull("floors") ? node.get("floors").asInt() : null;
            String usage = textOrNull(node, "usage");
            return new ClusterQuery(region, structure, floors, usage);
        } catch (Exception e) {
            log.warn("자연어 추출 결과 파싱 실패: {}", content, e);
            throw new IllegalStateException("문장을 이해하지 못했습니다. 다시 표현해 주세요.", e);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString().trim();
        return text.isEmpty() ? null : text;
    }
}
