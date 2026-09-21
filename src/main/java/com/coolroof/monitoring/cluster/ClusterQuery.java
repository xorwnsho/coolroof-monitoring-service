package com.coolroof.monitoring.cluster;

/**
 * 자연어 문장에서 OpenAI가 추출한 군집 조건. 값이 확인되지 않으면 null.
 */
public record ClusterQuery(String region, String structure, Integer floors, String usage) {
}
