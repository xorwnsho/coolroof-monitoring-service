package com.coolroof.monitoring.batch;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisBatchController {

    private final AnalysisBatchService analysisBatchService;

    @PostMapping("/api/batch/analyze")
    public List<AnalysisResultResponse> analyze() {
        return analysisBatchService.runBatch().stream()
                .map(AnalysisResultResponse::from)
                .toList();
    }
}
