package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.domain.analysis.AnalysisStatus;
import java.time.LocalDate;

/**
 * avgGap = 내 센서 실측 평균 - 군집 평균(추정). 음수일수록 쿨루프가 일반 건물보다
 * 시원하게 유지되고 있다는 뜻이고, 0에 가까워지거나 양수가 되면 성능이 저하됐다는 뜻이다.
 */
public record SensorAnalysisResult(
        AnalysisStatus status,
        double avgGap,
        Double trendSlopePerYear,
        LocalDate repaintForecast,
        int sampleCount,
        String aiText
) {
}
