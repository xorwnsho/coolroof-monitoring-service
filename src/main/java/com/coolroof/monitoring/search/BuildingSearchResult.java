package com.coolroof.monitoring.search;

import com.coolroof.monitoring.registry.RegistryBuilding;

/**
 * "01. 내 건물 선택" 검색 결과 1건. 카카오가 준 표시용 주소(addressName)와
 * 건축HUB에서 조회한 실제 건물 정보(building)를 함께 담는다.
 */
public record BuildingSearchResult(String addressName, RegistryBuilding building) {
}
