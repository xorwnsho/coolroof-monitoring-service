package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.ai.AiSummaryClient;
import com.coolroof.monitoring.ai.AiSummaryRequest;
import com.coolroof.monitoring.domain.analysis.AnalysisStatus;
import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.building.BuildingSource;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "센서 연동" 페이지의 그래프를 해석해서 상태 판정 + AI 요약을 만든다.
 * AnalysisBatchService와 같은 원칙(통계가 수치·판정을 확정하고 AI는 해석·문장화만 담당,
 * backend_claude.md 6번)을 따르되, 실제 기상 API 병합이 없어서 외기온도 대신
 * SensorHistoryService의 "군집 평균(추정)" 곡선을 기준선으로 쓴다.
 *
 * <p>gap = 내 센서 실측 - 군집 평균(추정): 음수면 쿨루프가 일반 건물보다 시원하게
 * 유지되고 있다는 뜻이고, 0에 가까워지거나 양수가 되면 성능이 저하됐다는 뜻이다.
 * 유효 데이터는 낮 10~17시 구간만 사용한다(일사량이 뚜렷한 시간대).
 * 판정 임계치는 backend_claude.md 12번과 같은 성격의 잠정값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorAnalysisService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int VALID_HOUR_START = 10;
    private static final int VALID_HOUR_END = 17;
    private static final int ANALYSIS_WINDOW_DAYS = 365;

    private static final double NORMAL_THRESHOLD = -3.0;
    private static final double REPAINT_THRESHOLD = 0.0;
    private static final double TREND_CAUTION_SLOPE_PER_YEAR = 3.0;

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;
    private final SensorHistoryService sensorHistoryService;
    private final AiSummaryClient aiSummaryClient;

    public SensorAnalysisResult analyze() {
        Building building = buildingRepository.findFirstBySource(BuildingSource.SENSOR)
                .orElseThrow(() -> new IllegalStateException("센서 데이터가 아직 없습니다."));

        LocalDateTime now = LocalDateTime.now(KST);
        List<TempReading> all = tempReadingRepository.findByBuildingIdAndMeasuredAtBetween(
                building.getId(), now.minusDays(ANALYSIS_WINDOW_DAYS), now);

        List<TempReading> valid = all.stream()
                .filter(r -> {
                    int hour = r.getMeasuredAt().getHour();
                    return hour >= VALID_HOUR_START && hour <= VALID_HOUR_END;
                })
                .toList();

        if (valid.isEmpty()) {
            throw new IllegalStateException("분석 가능한 데이터(10~17시 구간 실측)가 아직 없습니다.");
        }

        double referenceFactor = sensorHistoryService.referenceFactor();
        double avgGap = round2(valid.stream()
                .mapToDouble(r -> r.getSurfaceTemp() - sensorHistoryService.referenceSurfaceTemp(r.getMeasuredAt(), referenceFactor))
                .average().orElse(0));

        Double trendSlopePerYear = calculateTrendSlopePerYear(valid, referenceFactor);
        AnalysisStatus status = judge(avgGap, trendSlopePerYear);
        LocalDate repaintForecast = forecastRepaintDate(avgGap, trendSlopePerYear, status, now.toLocalDate());
        Double roundedTrend = trendSlopePerYear == null ? null : round2(trendSlopePerYear);

        String aiText = generateAiSummary(building, status, avgGap, roundedTrend, repaintForecast);

        return new SensorAnalysisResult(status, avgGap, roundedTrend, repaintForecast, valid.size(), aiText);
    }

    private String generateAiSummary(Building building, AnalysisStatus status, double avgGap,
                                      Double trendSlopePerYear, LocalDate repaintForecast) {
        try {
            return aiSummaryClient.summarize(new AiSummaryRequest(
                    building.getName(),
                    building.getUsageType() != null ? building.getUsageType() : "단독주택",
                    building.getCoolroofDate(),
                    status,
                    avgGap,
                    null,
                    trendSlopePerYear,
                    repaintForecast,
                    ANALYSIS_WINDOW_DAYS));
        } catch (Exception e) {
            log.warn("센서 AI 요약 생성 실패", e);
            return null;
        }
    }

    /** 최소자승 선형회귀로 gap의 연간 변화율(°C/년)을 구한다 — 양수면 점점 성능이 저하되는 추세. */
    private Double calculateTrendSlopePerYear(List<TempReading> valid, double referenceFactor) {
        if (valid.size() < 5) {
            return null;
        }
        LocalDate baseDate = valid.stream()
                .map(r -> r.getMeasuredAt().toLocalDate())
                .min(LocalDate::compareTo)
                .orElseThrow();

        double n = 0, sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (TempReading r : valid) {
            double x = ChronoUnit.DAYS.between(baseDate, r.getMeasuredAt().toLocalDate());
            double y = r.getSurfaceTemp() - sensorHistoryService.referenceSurfaceTemp(r.getMeasuredAt(), referenceFactor);
            n++;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) {
            return 0.0;
        }
        double slopePerDay = (n * sumXY - sumX * sumY) / denominator;
        return slopePerDay * 365;
    }

    private AnalysisStatus judge(double avgGap, Double trendSlopePerYear) {
        if (avgGap >= REPAINT_THRESHOLD) {
            return AnalysisStatus.REPAINT_RECOMMENDED;
        }
        boolean risingTrend = trendSlopePerYear != null && trendSlopePerYear >= TREND_CAUTION_SLOPE_PER_YEAR;
        if (avgGap >= NORMAL_THRESHOLD || risingTrend) {
            return AnalysisStatus.CAUTION;
        }
        return AnalysisStatus.NORMAL;
    }

    private LocalDate forecastRepaintDate(double avgGap, Double trendSlopePerYear, AnalysisStatus status, LocalDate today) {
        if (status == AnalysisStatus.NORMAL || trendSlopePerYear == null || trendSlopePerYear <= 0) {
            return null;
        }
        if (avgGap >= REPAINT_THRESHOLD) {
            return today;
        }
        double yearsToThreshold = (REPAINT_THRESHOLD - avgGap) / trendSlopePerYear;
        return today.plusDays(Math.round(yearsToThreshold * 365));
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
