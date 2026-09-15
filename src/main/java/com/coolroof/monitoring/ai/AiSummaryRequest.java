package com.coolroof.monitoring.ai;

import com.coolroof.monitoring.domain.analysis.AnalysisStatus;
import java.time.LocalDate;

/** 배치가 확정한 통계 결과를 AI 요약 프롬프트에 넘기기 위한 값 객체. */
public record AiSummaryRequest(
        String buildingName,
        String usageType,
        LocalDate coolroofDate,
        AnalysisStatus status,
        double avgTempGap,
        Double peerGap,
        Double degradationTrend,
        LocalDate repaintForecast
) {
}
