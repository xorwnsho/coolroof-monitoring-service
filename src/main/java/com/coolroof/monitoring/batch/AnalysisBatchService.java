package com.coolroof.monitoring.batch;

import com.coolroof.monitoring.domain.analysis.AnalysisResult;
import com.coolroof.monitoring.domain.analysis.AnalysisResultRepository;
import com.coolroof.monitoring.domain.analysis.AnalysisStatus;
import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 분석: temp_gap 집계 -> 동종(peer) 비교 -> 추세 분석 -> 판정 -> 재도장 시점 예측.
 * 임계치(NORMAL/REPAINT_THRESHOLD 등)는 건축팀 근거 수치가 아직 없어 목업 데이터 분포를 보고
 * 잡은 잠정값이다 (backend_claude.md 12번 미확정 항목).
 */
@Service
@RequiredArgsConstructor
public class AnalysisBatchService {

    private static final int VALID_HOUR_START = 12;
    private static final int VALID_HOUR_END = 15;
    private static final String VALID_WEATHER = "맑음";
    private static final int ANALYSIS_WINDOW_DAYS = 90;
    private static final int MIN_PEER_COUNT = 3;
    private static final int AGE_BAND_TOLERANCE_YEARS = 1;

    private static final double NORMAL_THRESHOLD = 8.0;
    private static final double REPAINT_THRESHOLD = 15.0;
    private static final double PEER_DEVIATION_THRESHOLD = 5.0;
    private static final double TREND_CAUTION_SLOPE_PER_YEAR = 3.0;

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;
    private final AnalysisResultRepository analysisResultRepository;

    @Transactional
    public List<AnalysisResult> runBatch() {
        LocalDate today = LocalDate.now();
        String period = today.toString();
        LocalDateTime windowStart = today.minusDays(ANALYSIS_WINDOW_DAYS).atStartOfDay();

        List<Building> buildings = buildingRepository.findAll();

        Map<Long, List<TempReading>> fullValidHistoryByBuilding = new HashMap<>();
        Map<Long, Double> avgGapByBuilding = new HashMap<>();

        for (Building building : buildings) {
            List<TempReading> allReadings = tempReadingRepository.findByBuildingIdAndMeasuredAtBetween(
                    building.getId(), building.getCoolroofDate().atStartOfDay(), today.plusDays(1).atStartOfDay());
            List<TempReading> validAll = filterValid(allReadings);
            fullValidHistoryByBuilding.put(building.getId(), validAll);

            List<TempReading> validWindow = validAll.stream()
                    .filter(r -> !r.getMeasuredAt().isBefore(windowStart))
                    .toList();
            avgGapByBuilding.put(building.getId(), average(validWindow));
        }

        analysisResultRepository.deleteByPeriod(period);

        List<AnalysisResult> results = new ArrayList<>();
        for (Building building : buildings) {
            Double avgGap = avgGapByBuilding.get(building.getId());
            if (avgGap == null) {
                continue; // 유효 데이터가 없는 건물은 이번 회차 분석 대상에서 제외
            }

            Double peerGap = resolvePeerGap(building, buildings, avgGapByBuilding, today);
            Double trendSlopePerYear = calculateTrendSlopePerYear(fullValidHistoryByBuilding.get(building.getId()));
            AnalysisStatus status = judge(avgGap, peerGap, trendSlopePerYear);
            LocalDate repaintForecast = forecastRepaintDate(avgGap, trendSlopePerYear, status, today);

            AnalysisResult result = AnalysisResult.builder()
                    .building(building)
                    .period(period)
                    .avgTempGap(round2(avgGap))
                    .peerGap(peerGap == null ? null : round2(peerGap))
                    .status(status)
                    .degradationTrend(trendSlopePerYear == null ? null : round2(trendSlopePerYear))
                    .repaintForecast(repaintForecast)
                    .aiSummary(null)
                    .createdAt(LocalDateTime.now())
                    .build();
            results.add(analysisResultRepository.save(result));
        }
        return results;
    }

    private List<TempReading> filterValid(List<TempReading> readings) {
        return readings.stream()
                .filter(r -> VALID_WEATHER.equals(r.getWeather()))
                .filter(r -> r.getOutdoorTemp() != null)
                .filter(r -> {
                    int hour = r.getMeasuredAt().getHour();
                    return hour >= VALID_HOUR_START && hour <= VALID_HOUR_END;
                })
                .toList();
    }

    private Double average(List<TempReading> readings) {
        if (readings.isEmpty()) {
            return null;
        }
        return readings.stream()
                .mapToDouble(r -> r.getSurfaceTemp() - r.getOutdoorTemp())
                .average()
                .orElse(Double.NaN);
    }

    private String floorAreaBand(Building building) {
        Double area = building.getTotalFloorArea();
        if (area == null) {
            return "UNKNOWN";
        }
        if (area < 300) {
            return "SMALL";
        }
        if (area < 1000) {
            return "MEDIUM";
        }
        return "LARGE";
    }

    private int ageBandYears(Building building, LocalDate today) {
        long days = ChronoUnit.DAYS.between(building.getCoolroofDate(), today);
        return (int) (days / 365);
    }

    /**
     * 1차: 용도+연면적밴드+경과연차(±1년) 모두 일치.
     * 부족하면(3개 미만) 연면적 조건을 풀고, 그래도 부족하면 경과연차 조건까지 풀어서
     * 최소 표본을 확보한다 (backend_claude.md 12번: 동종 군집 기준 미확정에 대한 잠정 구현).
     */
    private Double resolvePeerGap(Building target, List<Building> all,
                                   Map<Long, Double> avgGapByBuilding, LocalDate today) {
        String targetUsage = target.getUsageType();
        String targetAreaBand = floorAreaBand(target);
        int targetAgeBand = ageBandYears(target, today);

        List<Building> candidates = all.stream()
                .filter(b -> !b.getId().equals(target.getId()))
                .filter(b -> avgGapByBuilding.get(b.getId()) != null)
                .filter(b -> targetUsage.equals(b.getUsageType()))
                .toList();

        List<Building> step1 = candidates.stream()
                .filter(b -> targetAreaBand.equals(floorAreaBand(b)))
                .filter(b -> Math.abs(ageBandYears(b, today) - targetAgeBand) <= AGE_BAND_TOLERANCE_YEARS)
                .toList();
        if (step1.size() >= MIN_PEER_COUNT) {
            return averageOf(step1, avgGapByBuilding);
        }

        List<Building> step2 = candidates.stream()
                .filter(b -> Math.abs(ageBandYears(b, today) - targetAgeBand) <= AGE_BAND_TOLERANCE_YEARS)
                .toList();
        if (step2.size() >= MIN_PEER_COUNT) {
            return averageOf(step2, avgGapByBuilding);
        }

        if (!candidates.isEmpty()) {
            return averageOf(candidates, avgGapByBuilding);
        }
        return null;
    }

    private Double averageOf(List<Building> group, Map<Long, Double> avgGapByBuilding) {
        return group.stream()
                .map(b -> avgGapByBuilding.get(b.getId()))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
    }

    /** 최소자승 선형회귀로 temp_gap의 연간 상승률(°C/년)을 구한다. */
    private Double calculateTrendSlopePerYear(List<TempReading> validReadings) {
        if (validReadings.size() < 10) {
            return null;
        }
        LocalDate baseDate = validReadings.stream()
                .map(r -> r.getMeasuredAt().toLocalDate())
                .min(LocalDate::compareTo)
                .orElseThrow();

        double n = 0, sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (TempReading r : validReadings) {
            double x = ChronoUnit.DAYS.between(baseDate, r.getMeasuredAt().toLocalDate());
            double y = r.getSurfaceTemp() - r.getOutdoorTemp();
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

    private AnalysisStatus judge(double avgGap, Double peerGap, Double trendSlopePerYear) {
        boolean peerDeviation = peerGap != null && (avgGap - peerGap) >= PEER_DEVIATION_THRESHOLD;
        if (avgGap >= REPAINT_THRESHOLD || peerDeviation) {
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
        long daysToThreshold = Math.round(yearsToThreshold * 365);
        return today.plusDays(daysToThreshold);
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
