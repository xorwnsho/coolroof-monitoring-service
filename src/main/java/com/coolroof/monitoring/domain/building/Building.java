package com.coolroof.monitoring.domain.building;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "building")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(name = "usage_type")
    private String usageType;

    @Column(name = "built_year")
    private Integer builtYear;

    @Column(name = "coolroof_date")
    private LocalDate coolroofDate;

    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified;

    private Double lat;

    private Double lng;

    @Column(name = "total_floor_area")
    private Double totalFloorArea;

    @Column(name = "floor_count")
    private Integer floorCount;

    @Column(name = "roof_type")
    private String roofType;
}
