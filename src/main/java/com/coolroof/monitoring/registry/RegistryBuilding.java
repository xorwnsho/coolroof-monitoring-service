package com.coolroof.monitoring.registry;

import java.time.LocalDate;

/**
 * 국토교통부 건축HUB 건축물대장정보 서비스의 표제부 조회(getBrTitleInfo) 결과 항목.
 */
public record RegistryBuilding(
        String platPlc,
        String newPlatPlc,
        String bldNm,
        String mainPurpsCdNm,
        double totalFloorArea,
        int floorCount,
        LocalDate useApprovalDate,
        String roofType
) {
}
