package com.coolroof.monitoring.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 구조/층수/용도로부터 표면온도 전후 비교 곡선(추정 모델)을 계산하는 공용 로직.
 * 자연어 기반 군집 분석({@link ClusterAnalysisService})과 실제 건물 기준 유사 건물 찾기
 * (com.coolroof.monitoring.search.SimilarBuildingService) 양쪽에서 재사용한다.
 */
@Component
public class BuildingThermalModel {

    private static final String[] MONTHS = {
            "1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월"
    };
    private static final double[] BASE_BEFORE = {8, 10, 15, 22, 30, 42, 52, 54, 44, 28, 16, 9};

    public record StructureInfo(double factor, double reduction) {
    }

    public static final Map<String, StructureInfo> STRUCTURE_INFO = Map.of(
            "철근콘크리트구조", new StructureInfo(1.00, 0.36),
            "철골구조", new StructureInfo(1.08, 0.42),
            "철골철근콘크리트구조", new StructureInfo(1.04, 0.39),
            "조적구조", new StructureInfo(0.95, 0.30),
            "목구조", new StructureInfo(0.90, 0.26));

    // 주구조를 알 수 없을 때 쓰는 값 — 5개 구조의 평균(그럴듯한 값을 지어내는 게 아니라,
    // 조건이 없으면 모든 구조를 다 포함한다는 뜻의 중립값).
    public static final StructureInfo UNSPECIFIED_STRUCTURE_INFO = new StructureInfo(1.00, 0.35);
    public static final String UNSPECIFIED_STRUCTURE_LABEL = "전체 구조";

    public record UsageInfo(double factor, String label) {
    }

    public static final Map<String, UsageInfo> USAGE_INFO = Map.of(
            "공동주택", new UsageInfo(0.98, "공동주택(아파트)"),
            "단독주택", new UsageInfo(0.95, "단독주택"),
            "업무시설", new UsageInfo(1.00, "업무시설"),
            "근린생활시설", new UsageInfo(1.02, "근린생활시설"),
            "공장", new UsageInfo(1.06, "공장"),
            "창고시설", new UsageInfo(1.08, "창고시설"),
            "교육연구시설", new UsageInfo(0.97, "교육연구시설"));
    public static final String UNSPECIFIED_USAGE_LABEL = "전체 용도";

    public record FloorBand(String key, double factor) {
    }

    public static final String UNSPECIFIED_FLOOR_LABEL = "전체 층수";

    public record Curve(List<String> months, List<Double> before, List<Double> after,
                         double maxDiff, double avgReduction, double energySavingPct) {
    }

    public FloorBand floorBand(int floors) {
        if (floors <= 4) {
            return new FloorBand("저층", 1.03);
        }
        if (floors <= 15) {
            return new FloorBand("중층", 1.00);
        }
        return new FloorBand("고층", 0.94);
    }

    /**
     * 건축물대장 strctCdNm은 "일반목구조", "경량철골구조", "일반철골구조"처럼 접두사가 붙어
     * 나오므로, 실제 응답 샘플을 확인해 정한 순서(복합구조를 먼저 검사)로 5개 범주 중 하나로
     * 정규화한다. 어디에도 해당하지 않으면 null(제외).
     */
    public String normalizeStructure(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.contains("철골철근콘크리트")) {
            return "철골철근콘크리트구조";
        }
        if (raw.contains("철근콘크리트")) {
            return "철근콘크리트구조";
        }
        if (raw.contains("철골")) {
            return "철골구조";
        }
        if (raw.contains("목구조") || raw.contains("목조")) {
            return "목구조";
        }
        if (raw.contains("벽돌") || raw.contains("블록") || raw.contains("석구조") || raw.contains("조적")) {
            return "조적구조";
        }
        return null;
    }

    public Curve estimate(StructureInfo structInfo, double floorFactor, double usageFactor) {
        double combinedFactor = structInfo.factor() * floorFactor * usageFactor;

        List<Double> before = new ArrayList<>(12);
        List<Double> after = new ArrayList<>(12);
        double maxDiff = 0;
        double sumDiff = 0;
        for (double base : BASE_BEFORE) {
            double b = round1(Math.min(59, base * combinedFactor));
            double a = round1(b * (1 - structInfo.reduction()));
            before.add(b);
            after.add(a);
            double diff = b - a;
            sumDiff += diff;
            maxDiff = Math.max(maxDiff, diff);
        }
        double avgReduction = round1(sumDiff / BASE_BEFORE.length);
        double energySavingPct = round1(Math.min(42, maxDiff * 1.2));
        return new Curve(List.of(MONTHS), before, after, round1(maxDiff), avgReduction, energySavingPct);
    }

    public double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
