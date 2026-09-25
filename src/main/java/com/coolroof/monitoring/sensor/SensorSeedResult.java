package com.coolroof.monitoring.sensor;

import java.time.LocalDate;

public record SensorSeedResult(String deviceId, int daysSeeded, LocalDate from, LocalDate to) {
}
