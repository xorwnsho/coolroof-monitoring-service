package com.coolroof.monitoring.registry;

/**
 * 건축물대장 조회에 필요한 법정동 단위 키.
 * lat/lng은 해당 법정동의 대략적인 중심 좌표(참고용, 실제 건물 정확 좌표 아님).
 */
public record LegalDong(String sigunguCd, String bjdongCd, String label, double lat, double lng) {
}
