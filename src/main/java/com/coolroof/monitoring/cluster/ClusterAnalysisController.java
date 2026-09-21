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
 * "2번째 페이지 — 군집별 비교"용 자연어 질의 엔드포인트.
 * 예: {"query": "대전에 철근콘크리트구조의 8층짜리 빌딩 하나 있는데 쿨루프 설치하면 어떻게 돼?"}
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
