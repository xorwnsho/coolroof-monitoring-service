package com.coolroof.monitoring.sensor;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ESP32(MLX90614) 센서가 측정한 지붕 표면온도를 받는 엔드포인트.
 * X-Device-Key 헤더로 인증되지 않은 요청은 거부한다 (누구나 접근 가능한 공인 경로이므로).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SensorIngestController {

    private final SensorIngestService sensorIngestService;
    private final SensorHistoryService sensorHistoryService;

    @Value("${sensor.device-key}")
    private String expectedDeviceKey;

    @PostMapping("/api/sensor/readings")
    public void ingest(@RequestHeader("X-Device-Key") String deviceKey,
                        @RequestBody SensorReadingRequest request) {
        if (!expectedDeviceKey.equals(deviceKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 키가 올바르지 않습니다.");
        }
        sensorIngestService.ingest(request);
    }

    @GetMapping("/api/sensor/latest")
    public LatestReadingResponse latest() {
        return sensorIngestService.getLatest();
    }

    /** 개발/시연용 — 실제 센서가 몇 년째 운영 중이라는 가정하에 과거 데이터를 하루 단위로 채운다. */
    @PostMapping("/api/sensor/seed-history")
    public SensorSeedResult seedHistory(@RequestHeader("X-Device-Key") String deviceKey,
                                         @RequestParam(defaultValue = "roof-01") String deviceId,
                                         @RequestParam(defaultValue = "730") int days) {
        if (!expectedDeviceKey.equals(deviceKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 키가 올바르지 않습니다.");
        }
        return sensorIngestService.seedHistory(deviceId, days);
    }

    @GetMapping("/api/sensor/history")
    public SensorHistoryResponse history(@RequestParam(defaultValue = "HOUR") TimeWindow window) {
        return sensorHistoryService.getHistory(window);
    }

    @GetMapping("/api/sensor/cluster-comparison")
    public ClusterComparisonResponse clusterComparison(@RequestParam(defaultValue = "HOUR") TimeWindow window) {
        return sensorHistoryService.getClusterComparison(window);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
