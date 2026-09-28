package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.cluster.BuildingThermalModel;
import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.building.BuildingSource;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 가정한 내 건물 프로필: 단독주택 / 철근콘크리트구조 / 5층 / 옥상면적 150m² / 1990년 준공
    private static final String REFERENCE_STRUCTURE = "철근콘크리트구조";
    private static final String REFERENCE_USAGE = "단독주택";
    // 쿨루프 미시공 일반 건물의 표면-외기 평균 온도차 추정 기준값(환경부 쿨루프 가이드 범위 참고)
    private static final double REFERENCE_BASE_GAP = 10.0;

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;

    public SensorHistoryResponse getHistory(TimeWindow window, int offset, int span) {
        LocalDateTime now = anchorNow(window, offset, liveAnchor());

        if (window == TimeWindow.THIRTY_SEC) {
            List<TempReading> raw = sortedRawReadings(window, now, span);
            List<String> labels = raw.stream().map(r -> formatLabel(r.getMeasuredAt(), window)).toList();
            List<Double> values = raw.stream().map(TempReading::getSurfaceTemp).toList();
            return new SensorHistoryResponse(labels, values);
        }

        List<TempReading> readings = fetchSensorReadings(window, now, span);
        Map<LocalDateTime, Double> byBucket = bucketAverage(readings, window);

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (LocalDateTime t : buildTimeline(window, now, span)) {
            Double v = byBucket.get(t);
            if (v == null) {
                continue; // 실측 없는 시간대는 그래프에서 건너뛴다
            }
            labels.add(formatLabel(t, window));
            values.add(v);
        }
        return new SensorHistoryResponse(labels, values);
    }

    public ClusterComparisonResponse getClusterComparison(TimeWindow window, int offset, int span) {
        LocalDateTime now = anchorNow(window, offset, liveAnchor());
        double referenceFactor = referenceFactor();

        if (window == TimeWindow.THIRTY_SEC) {
            List<TempReading> raw = sortedRawReadings(window, now, span);
            List<String> labels = raw.stream().map(r -> formatLabel(r.getMeasuredAt(), window)).toList();
            List<Double> sensorValues = raw.stream().map(TempReading::getSurfaceTemp).toList();
            List<Double> clusterAverageValues = raw.stream()
                    .map(r -> referenceSurfaceTemp(r.getMeasuredAt(), referenceFactor)).toList();
            return new ClusterComparisonResponse(labels, sensorValues, clusterAverageValues);
        }

        List<TempReading> readings = fetchSensorReadings(window, now, span);
        Map<LocalDateTime, Double> byBucket = bucketAverage(readings, window);

        List<String> labels = new ArrayList<>();
        List<Double> sensorValues = new ArrayList<>();
        List<Double> clusterAverageValues = new ArrayList<>();
        for (LocalDateTime t : buildTimeline(window, now, span)) {
            labels.add(formatLabel(t, window));
            sensorValues.add(byBucket.get(t));
            clusterAverageValues.add(referenceSurfaceTemp(t, referenceFactor));
        }
        return new ClusterComparisonResponse(labels, sensorValues, clusterAverageValues);
    }

    /**
     * 그래프의 "현재 시점" 기준. 센서가 계속 값을 보내고 있으면 실제 지금과 거의 같지만,
     * ESP32 연결이 끊겨서 최근 실측이 없으면 "마지막으로 값이 들어온 시점"을 기준으로 삼는다.
     * 그래야 연결이 끊긴 뒤에도 그래프가 텅 비지 않고 마지막 실측 구간을 그대로 보여준다.
     */
    private LocalDateTime liveAnchor() {
        return buildingRepository.findFirstBySource(BuildingSource.SENSOR)
                .flatMap(b -> tempReadingRepository.findTopByBuildingIdOrderByMeasuredAtDesc(b.getId()))
                .map(TempReading::getMeasuredAt)
                .orElseGet(() -> LocalDateTime.now(KST));
    }

    /** 드래그로 과거 구간을 볼 때 쓰는 기준시점 이동. offset=1이면 현재 창 길이만큼 통째로 뒤로 민다. */
    private LocalDateTime anchorNow(TimeWindow window, int offset, LocalDateTime now) {
        if (offset <= 0) {
            return now;
        }
        return switch (window) {
            case THIRTY_SEC -> now.minusMinutes(10L * offset);
            case HOUR -> now.minusHours(offset);
            case DAY -> now.minusHours(24L * offset);
            case WEEK -> now.minusDays(7L * offset);
            case MONTH -> now.minusDays(30L * offset);
            case YEAR -> now.minusDays(365L * offset);
        };
    }

    double referenceFactor() {
        BuildingThermalModel.StructureInfo structInfo = BuildingThermalModel.STRUCTURE_INFO
                .getOrDefault(REFERENCE_STRUCTURE, BuildingThermalModel.UNSPECIFIED_STRUCTURE_INFO);
        BuildingThermalModel.UsageInfo usageInfo = BuildingThermalModel.USAGE_INFO.get(REFERENCE_USAGE);
        return structInfo.factor() * (usageInfo != null ? usageInfo.factor() : 1.0);
    }

    private List<TempReading> sortedRawReadings(TimeWindow window, LocalDateTime now, int span) {
        List<TempReading> readings = new ArrayList<>(fetchSensorReadings(window, now, span));
        readings.sort(Comparator.comparing(TempReading::getMeasuredAt));
        return readings;
    }

    private List<TempReading> fetchSensorReadings(TimeWindow window, LocalDateTime now, int span) {
        Building building = buildingRepository.findFirstBySource(BuildingSource.SENSOR).orElse(null);
        if (building == null) {
            return List.of();
        }
        return tempReadingRepository.findByBuildingIdAndMeasuredAtBetween(
                building.getId(), lookbackStart(window, now, span), now);
    }

    /**
     * span은 화면에 보이는 창 길이의 배수로 과거 데이터를 통째로 더 받아오는 버퍼 배수다.
     * (프론트에서 드래그로 스크롤할 여유 구간을 미리 확보해서, 드래그할 때마다 재조회하지 않고
     * 받아둔 버퍼 안에서만 화면을 옮기게 하기 위함 — span=1이면 기존과 동일한 창 하나만 반환.)
     */
    private LocalDateTime lookbackStart(TimeWindow window, LocalDateTime now, int span) {
        long s = Math.max(1, span);
        return switch (window) {
            case THIRTY_SEC -> now.minusMinutes(10L * s);
            case HOUR -> now.minusHours(s);
            case DAY -> now.minusHours(24L * s);
            case WEEK -> now.minusDays(7L * s);
            case MONTH -> now.minusDays(30L * s);
            case YEAR -> now.minusDays(365L * s);
        };
    }

    /** WEEK/MONTH는 하루 단위, YEAR는 주 단위, HOUR/DAY는 시간 단위로 평균 묶는다 (센서 자체가 1시간에 1건이라 시간 단위가 곧 원본값). */
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
        if (window == TimeWindow.YEAR) {
            return ChronoUnit.WEEKS;
        }
        return (window == TimeWindow.WEEK || window == TimeWindow.MONTH) ? ChronoUnit.DAYS : ChronoUnit.HOURS;
    }

    private LocalDateTime truncateTo(LocalDateTime dt, ChronoUnit unit) {
        if (unit == ChronoUnit.WEEKS) {
            return dt.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay();
        }
        return unit == ChronoUnit.DAYS ? dt.toLocalDate().atStartOfDay() : dt.truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * 실측 유무와 무관하게 그래프 x축에 표시할 시점 목록을 만든다 (군집 평균선은 이 전체 구간에 다 그려짐).
     * span배만큼 구간을 통째로 늘려서, 화면엔 뒤쪽 1/span만 보여주고 나머지는 드래그용 버퍼로 쓴다.
     */
    private List<LocalDateTime> buildTimeline(TimeWindow window, LocalDateTime now, int span) {
        int s = Math.max(1, span);
        List<LocalDateTime> timeline = new ArrayList<>();
        switch (window) {
            case HOUR -> {
                LocalDateTime start = now.minusHours(s).truncatedTo(ChronoUnit.HOURS);
                for (int i = 0; i <= s; i++) {
                    timeline.add(start.plusHours(i));
                }
            }
            case DAY -> {
                LocalDateTime start = now.minusHours(23L * s).truncatedTo(ChronoUnit.HOURS);
                for (int i = 0; i <= 23 * s; i++) {
                    timeline.add(start.plusHours(i));
                }
            }
            case WEEK -> {
                LocalDate start = now.toLocalDate().minusDays(6L * s);
                for (int i = 0; i <= 6 * s; i++) {
                    timeline.add(start.plusDays(i).atStartOfDay());
                }
            }
            case MONTH -> {
                LocalDate start = now.toLocalDate().minusDays(29L * s);
                for (int i = 0; i <= 29 * s; i++) {
                    timeline.add(start.plusDays(i).atStartOfDay());
                }
            }
            case YEAR -> {
                LocalDate startMonday = now.toLocalDate().with(DayOfWeek.MONDAY).minusWeeks(51L * s);
                for (int i = 0; i <= 51 * s; i++) {
                    timeline.add(startMonday.plusWeeks(i).atStartOfDay());
                }
            }
        }
        return timeline;
    }

    private String formatLabel(LocalDateTime time, TimeWindow window) {
        DateTimeFormatter formatter = switch (window) {
            case WEEK, MONTH, YEAR -> DateTimeFormatter.ofPattern("MM/dd");
            case THIRTY_SEC -> DateTimeFormatter.ofPattern("HH:mm:ss");
            default -> DateTimeFormatter.ofPattern("HH:mm");
        };
        return time.format(formatter);
    }

    /** 계절(일자)·시간대 기반 외기온도 추정 + 가정 건물 프로필 계수를 적용한 참고용 표면온도. */
    double referenceSurfaceTemp(LocalDateTime t, double referenceFactor) {
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
