package com.coolroof.monitoring.geocode;

/**
 * 카카오 주소 검색 결과 1건.
 * sigunguCd/bjdongCd는 카카오가 주는 법정동코드(b_code, 10자리)를 앞5/뒤5로 나눈 값이고,
 * mainAddressNo/subAddressNo는 건축HUB 조회에 필요한 지번(본번/부번)이다.
 */
public record KakaoAddressResult(
        String addressName,
        String buildingName,
        String sigunguCd,
        String bjdongCd,
        String mainAddressNo,
        String subAddressNo
) {
}
