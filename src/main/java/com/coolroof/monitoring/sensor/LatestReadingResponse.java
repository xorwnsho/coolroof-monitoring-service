package com.coolroof.monitoring.sensor;

import java.time.LocalDateTime;

/** 아직 센서 데이터가 하나도 없으면 두 필드 다 null. */
public record LatestReadingResponse(Double temperature, LocalDateTime measuredAt) {
}
