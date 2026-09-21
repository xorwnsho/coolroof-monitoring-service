package com.coolroof.monitoring.cluster;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "2번째 페이지 — 군집별 비교"용 텍스트 질의 엔드포인트.
 * 문장이든 "대전 / 철근콘크리트구조 / 8층" 같은 키워드 나열이든 그대로 받아서
 * OpenAI가 알아서 지역/구조/층수를 뽑아낸다 (형식을 코드에서 강제하지 않는다).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClusterAnalysisController {

    private final ClusterAnalysisService clusterAnalysisService;

    @PostMapping("/api/cluster/analyze")
    public ClusterAnalysisResult analyze(@RequestBody ClusterAnalyzeRequest request) {
        return clusterAnalysisService.analyze(request.query());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleUpstreamFailure(IllegalStateException e) {
        log.warn("군집 분석 처리 실패", e);
        return Map.of("error", e.getMessage());
    }
}
