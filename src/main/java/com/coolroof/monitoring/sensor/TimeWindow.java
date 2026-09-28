package com.coolroof.monitoring.sensor;

/** 센서 그래프 조회 기준. THIRTY_SEC은 로컬 실시간 테스트용 — 묶지 않고 실측을 그대로 보여준다. */
public enum TimeWindow {
    THIRTY_SEC, HOUR, DAY, WEEK, MONTH
}
