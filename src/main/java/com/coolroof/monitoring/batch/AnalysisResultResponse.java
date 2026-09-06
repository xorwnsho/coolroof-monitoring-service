package com.coolroof.monitoring.batch;

import com.coolroof.monitoring.domain.analysis.AnalysisResult;
import com.coolroof.monitoring.domain.analysis.AnalysisStatus;
import java.time.LocalDate;

public record AnalysisResultResponse(
        Long buildingId,
        String buildingName,
        String period,
        Double avgTempGap,
        Double peerGap,
        AnalysisStatus status,
        Double degradationTrend,
        LocalDate repaintForecast
) {
    public static AnalysisResultResponse from(AnalysisResult result) {
        return new AnalysisResultResponse(
                result.getBuilding().getId(),
                result.getBuilding().getName(),
                result.getPeriod(),
                result.getAvgTempGap(),
                result.getPeerGap(),
                result.getStatus(),
                result.getDegradationTrend(),
                result.getRepaintForecast());
    }
}
