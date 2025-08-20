package com.serge.carrental.domain;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

@Entity
@Table(name = "car_types")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarType {
    @Id
    @Column(length = 20)
    private String id; // e.g., SEDAN, SUV, VAN

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "price_per_day", nullable = false)
    private BigDecimal pricePerDay;

    @Column(length = 3, nullable = false)
    private String currency;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "photo_url")
    private String photoUrl;

    @org.hibernate.annotations.Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
