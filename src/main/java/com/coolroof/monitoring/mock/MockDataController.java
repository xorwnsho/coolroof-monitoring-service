package com.coolroof.monitoring.mock;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연·개발용 목업 데이터 생성 엔드포인트.
 * 실제 센서(ESP32) 연동 전까지 배치 분석 로직을 검증하는 용도로만 사용한다.
 */
@RestController
@RequiredArgsConstructor
public class MockDataController {

    private final MockDataGenerator mockDataGenerator;

    @PostMapping("/api/mock/generate")
    public MockGenerateResult generate(@RequestParam(defaultValue = "30") int buildingCount) {
        return mockDataGenerator.generate(buildingCount);
    }
}
