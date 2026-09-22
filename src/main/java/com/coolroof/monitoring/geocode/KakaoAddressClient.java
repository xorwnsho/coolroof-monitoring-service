package com.coolroof.monitoring.geocode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 로컬 API(주소 검색)로 건물명/주소 문자열을 법정동코드+지번으로 변환한다.
 * 건축HUB API는 자유 텍스트 검색을 지원하지 않고 법정동코드+지번이 있어야 조회되므로,
 * 이 클라이언트가 그 앞단(주소 해석) 역할을 한다 — 지역명을 코드에 나열하지 않는다.
 */
@Slf4j
@Component
public class KakaoAddressClient {

    private static final String ENDPOINT = "https://dapi.kakao.com/v2/local/search/address.json";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kakao.rest-api-key}")
    private String restApiKey;

    public List<KakaoAddressResult> search(String query) {
        String uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("query", query)
                .build()
                .toUriString();

        String body = restClient.get()
                .uri(uri)
                .header("Authorization", "KakaoAK " + restApiKey)
                .retrieve()
                .body(String.class);

        List<KakaoAddressResult> results = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return results;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode doc : root.path("documents")) {
                JsonNode address = doc.path("address");
                if (address.isMissingNode()) {
                    continue;
                }
                String bCode = textOrNull(address, "b_code");
                if (bCode == null || bCode.length() != 10) {
                    continue; // 법정동코드를 못 얻으면 건축HUB 조회가 불가능하므로 제외
                }
                String buildingName = textOrNull(doc.path("road_address"), "building_name");
                results.add(new KakaoAddressResult(
                        textOrNull(doc, "address_name"),
                        buildingName,
                        bCode.substring(0, 5),
                        bCode.substring(5, 10),
                        textOrNull(address, "main_address_no"),
                        textOrNull(address, "sub_address_no")));
            }
        } catch (Exception e) {
            log.warn("카카오 주소 검색 응답 파싱 실패", e);
        }
        return results;
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
