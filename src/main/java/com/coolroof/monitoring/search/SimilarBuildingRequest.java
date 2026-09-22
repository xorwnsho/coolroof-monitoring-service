package com.coolroof.monitoring.search;

/**
 * "02. 유사 조건" — 01에서 선택한 실제 건물을 기준으로 유사 건물을 찾기 위한 요청.
 * region은 필수(기준 건물 주소에서 프론트가 뽑아서 보냄)이고, 나머지 조건들은
 * 각각의 boolean 토글이 켜져 있을 때만 필터로 적용된다 (토글이 꺼져 있으면 그 조건은 무시).
 */
public record SimilarBuildingRequest(
        String region,
        String structureType,
        String usage,
        boolean sameUsage,
        Integer builtYear,
        boolean yearTolerance,
        Integer floors,
        boolean floorTolerance,
        Double roofArea,
        boolean roofAreaTolerance,
        String roofType,
        boolean sameRoofType
) {
}
