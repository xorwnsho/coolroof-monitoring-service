package com.coolroof.monitoring.search;

import com.coolroof.monitoring.cluster.ClusterAnalysisResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "01. 내 건물 선택" / "02. 유사 조건" 엔드포인트.
 * 예: GET /api/buildings/search?query=서울 강남구 테헤란로 152
 *     POST /api/buildings/similar
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BuildingSearchController {

    private final BuildingSearchService buildingSearchService;
    private final SimilarBuildingService similarBuildingService;

    @GetMapping("/api/buildings/search")
    public List<BuildingSearchResult> search(@RequestParam String query) {
        return buildingSearchService.search(query);
    }

    @PostMapping("/api/buildings/similar")
    public ClusterAnalysisResult similar(@RequestBody SimilarBuildingRequest request) {
        return similarBuildingService.findSimilar(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleUpstreamFailure(IllegalStateException e) {
        log.warn("건물 검색 처리 실패", e);
        return Map.of("error", e.getMessage());
    }
}
