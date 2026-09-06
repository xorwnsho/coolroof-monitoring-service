package com.coolroof.monitoring.registry;

import java.util.List;

/**
 * 건축물대장 API 호출을 위해 실제 존재를 확인한 서울시 법정동 목록.
 * (sigunguCd, bjdongCd 조합으로 호출해서 결과가 있는 것을 확인함)
 */
public final class SeoulLegalDongs {

    public static final List<LegalDong> TARGETS = List.of(
            new LegalDong("11620", "10200", "관악구 신림동", 37.484, 126.929),
            new LegalDong("11680", "10100", "강남구 역삼동", 37.500, 127.036),
            new LegalDong("11440", "10100", "마포구 아현동", 37.557, 126.956),
            new LegalDong("11200", "10100", "성동구 상왕십리동", 37.564, 127.028),
            new LegalDong("11350", "10500", "노원구 상계동", 37.671, 127.056)
    );

    private SeoulLegalDongs() {
    }
}
