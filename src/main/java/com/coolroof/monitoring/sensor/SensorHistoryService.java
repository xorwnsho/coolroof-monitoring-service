package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.cluster.BuildingThermalModel;
import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.building.BuildingSource;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 센서 페이지의 두 그래프(내 센서 실측 / 군집 평균 비교)에 쓰이는 시계열을 만든다.
 *
 * <p>군집 평균선은 실제 다른 건물의 센서가 없어서, "단독주택·철근콘크리트구조·1990년 준공"
 * 가정 프로필에 계절/시간대 기반 외기온도 추정 + 구조·용도 계수를 적용한 추정 곡선이다
 * (실측이 아니라 참고용 추정치임을 프론트에도 명시해야 한다).
 */
@Service
@RequiredArgsConstructor
public class SensorHistoryService {

    // 가정한 내 건물 프로필: 단독주택 / 철근콘크리트구조 / 5층 / 옥상면적 150m² / 1990년 준공
    private static final String REFERENCE_STRUCTURE = "철근콘크리트구조";
    private static final String REFERENCE_USAGE = "단독주택";
    // 쿨루프 미시공 일반 건물의 표면-외기 평균 온도차 추정 기준값(환경부 쿨루프 가이드 범위 참고)
    private static final double REFERENCE_BASE_GAP = 10.0;

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;

    public SensorHistoryResponse getHistory(TimeWindow window) {
        LocalDateTime now = LocalDateTime.now();
        List<TempReading> readings = fetchSensorReadings(window, now);
        Map<LocalDateTime, Double> byBucket = bucketAverage(readings, window);

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (LocalDateTime t : buildTimeline(window, now)) {
            Double v = byBucket.get(t);
            if (v == null) {
                continue; // 실측 없는 시간대는 그래프에서 건너뛴다
            }
            labels.add(formatLabel(t, window));
            values.add(v);
        }
        return new SensorHistoryResponse(labels, values);
    }

    public ClusterComparisonResponse getClusterComparison(TimeWindow window) {
        LocalDateTime now = LocalDateTime.now();
        List<TempReading> readings = fetchSensorReadings(window, now);
        Map<LocalDateTime, Double> byBucket = bucketAverage(readings, window);

        BuildingThermalModel.StructureInfo structInfo = BuildingThermalModel.STRUCTURE_INFO
                .getOrDefault(REFERENCE_STRUCTURE, BuildingThermalModel.UNSPECIFIED_STRUCTURE_INFO);
        BuildingThermalModel.UsageInfo usageInfo = BuildingThermalModel.USAGE_INFO.get(REFERENCE_USAGE);
        double referenceFactor = structInfo.factor() * (usageInfo != null ? usageInfo.factor() : 1.0);

        List<String> labels = new ArrayList<>();
        List<Double> sensorValues = new ArrayList<>();
        List<Double> clusterAverageValues = new ArrayList<>();
        for (LocalDateTime t : buildTimeline(window, now)) {
            labels.add(formatLabel(t, window));
            sensorValues.add(byBucket.get(t));
            clusterAverageValues.add(referenceSurfaceTemp(t, referenceFactor));
        }
        return new ClusterComparisonResponse(labels, sensorValues, clusterAverageValues);
    }

    private List<TempReading> fetchSensorReadings(TimeWindow window, LocalDateTime now) {
        Building building = buildingRepository.findFirstBySource(BuildingSource.SENSOR).orElse(null);
        if (building == null) {
            return List.of();
        }
        return tempReadingRepository.findByBuildingIdAndMeasuredAtBetween(
                building.getId(), lookbackStart(window, now), now);
    }

    private LocalDateTime lookbackStart(TimeWindow window, LocalDateTime now) {
        return switch (window) {
            case HOUR -> now.minusHours(1);
            case DAY -> now.minusHours(24);
            case WEEK -> now.minusDays(7);
            case MONTH -> now.minusDays(30);
        };
    }

    /** WEEK/MONTH는 하루 단위 평균, HOUR/DAY는 시간 단위 평균으로 묶는다 (센서 자체가 1시간에 1건이라 시간 단위가 곧 원본값). */
    private Map<LocalDateTime, Double> bucketAverage(List<TempReading> readings, TimeWindow window) {
        ChronoUnit unit = bucketUnit(window);
        Map<LocalDateTime, List<Double>> grouped = new TreeMap<>();
        for (TempReading r : readings) {
            LocalDateTime key = truncateTo(r.getMeasuredAt(), unit);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getSurfaceTemp());
        }
        return grouped.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> round1(e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0))));
    }

    private ChronoUnit bucketUnit(TimeWindow window) {
        return (window == TimeWindow.WEEK || window == TimeWindow.MONTH) ? ChronoUnit.DAYS : ChronoUnit.HOURS;
    }

    private LocalDateTime truncateTo(LocalDateTime dt, ChronoUnit unit) {
        return unit == ChronoUnit.DAYS ? dt.toLocalDate().atStartOfDay() : dt.truncatedTo(ChronoUnit.HOURS);
    }

    /** 실측 유무와 무관하게 그래프 x축에 표시할 시점 목록을 만든다 (군집 평균선은 이 전체 구간에 다 그려짐). */
    private List<LocalDateTime> buildTimeline(TimeWindow window, LocalDateTime now) {
        List<LocalDateTime> timeline = new ArrayList<>();
        switch (window) {
            case HOUR -> {
                LocalDateTime start = now.minusHours(1).truncatedTo(ChronoUnit.HOURS);
                for (int i = 0; i <= 1; i++) {
                    timeline.add(start.plusHours(i));
                }
            }
            case DAY -> {
                LocalDateTime start = now.minusHours(23).truncatedTo(ChronoUnit.HOURS);
                for (int i = 0; i <= 23; i++) {
                    timeline.add(start.plusHours(i));
                }
            }
            case WEEK -> {
                LocalDate start = now.toLocalDate().minusDays(6);
                for (int i = 0; i <= 6; i++) {
                    timeline.add(start.plusDays(i).atStartOfDay());
                }
            }
            case MONTH -> {
                LocalDate start = now.toLocalDate().minusDays(29);
                for (int i = 0; i <= 29; i++) {
                    timeline.add(start.plusDays(i).atStartOfDay());
                }
            }
        }
        return timeline;
    }

    private String formatLabel(LocalDateTime time, TimeWindow window) {
        DateTimeFormatter formatter = (window == TimeWindow.WEEK || window == TimeWindow.MONTH)
                ? DateTimeFormatter.ofPattern("MM/dd")
                : DateTimeFormatter.ofPattern("HH:mm");
        return time.format(formatter);
    }

    /** 계절(일자)·시간대 기반 외기온도 추정 + 가정 건물 프로필 계수를 적용한 참고용 표면온도. */
    private double referenceSurfaceTemp(LocalDateTime t, double referenceFactor) {
        int dayOfYear = t.getDayOfYear();
        double angle = 2 * Math.PI * (dayOfYear - 105) / 365.0;
        double seasonalBase = 14 + 16 * Math.sin(angle);
        int hour = t.getHour();
        double hourAdjust = (hour >= 11 && hour <= 16) ? 3 : (hour <= 6 || hour >= 21) ? -4 : 0;
        double outdoor = seasonalBase + hourAdjust;
        double gap = REFERENCE_BASE_GAP * referenceFactor;
        return round1(outdoor + gap);
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
