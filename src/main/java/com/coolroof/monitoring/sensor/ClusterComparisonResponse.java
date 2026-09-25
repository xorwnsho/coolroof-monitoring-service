package com.coolroof.monitoring.sensor;

import java.util.List;

/**
 * sensorValues는 그 시간대에 실측이 없으면 null(그래프에서 끊어 그림).
 * clusterAverageValues는 항상 값이 있다 — 가정한 건물 프로필(단독주택/철근콘크리트구조)
 * 기준 추정 곡선이라 실측 유무와 무관하게 매 시점 계산된다.
 */
public record ClusterComparisonResponse(List<String> labels, List<Double> sensorValues, List<Double> clusterAverageValues) {
}
