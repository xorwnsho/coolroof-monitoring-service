package com.coolroof.monitoring.sensor;

/**
 * ESP32(MLX90614)가 보내는 지붕 표면온도 측정값.
 * temperature는 MLX90614의 Object Temp(To) — 지붕 표면을 향한 IR 측정값이다.
 */
public record SensorReadingRequest(String deviceId, Double temperature) {
}
