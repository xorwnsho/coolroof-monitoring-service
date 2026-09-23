package com.coolroof.monitoring.domain.building;

/**
 * 이 건물 데이터가 어디서 왔는지 — 목데이터 재생성 시 실제 센서 데이터를 실수로
 * 지우지 않도록 구분한다.
 */
public enum BuildingSource {
    MOCK,
    SENSOR
}
