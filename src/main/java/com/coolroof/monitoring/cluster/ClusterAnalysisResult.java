package com.coolroof.monitoring.cluster;

import java.util.List;

/**
 * 군집 비교 분석 결과. sampleCount/dongsScanned/buildingsScanned는 실제 건축물대장 API를
 * 조회해 얻은 실측치이고, months/before/after 곡선과 파생 통계치는 구조·층수·용도 계수를
 * 적용한 추정 모델이다 (실측 표면온도 시계열이 없는 임의 건물이라 추정치임을 명시).
 *
 * <p>region 외에는 전부 사용자가 실제로 언급한 조건만 값이 채워진다. floors는 언급이
 * 없으면 null이고, structure/floorBand/usageLabel은 언급이 없으면 "전체 OO"로 표시된다
 * (값을 지어내 채우지 않는다).
 */
public record ClusterAnalysisResult(
        String region,
        String structure,
        Integer floors,
        String floorBand,
        String usageLabel,
        int sampleCount,
        int buildingsScanned,
        int dongsScanned,
        int dongsTotal,
        List<String> months,
        List<Double> before,
        List<Double> after,
        double maxDiff,
        double avgReduction,
        double energySavingPct,
        String aiText
) {
}
