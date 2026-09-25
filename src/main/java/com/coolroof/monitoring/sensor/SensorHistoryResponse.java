package com.coolroof.monitoring.sensor;

import java.util.List;

public record SensorHistoryResponse(List<String> labels, List<Double> values) {
}
