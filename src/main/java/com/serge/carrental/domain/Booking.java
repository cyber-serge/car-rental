package com.serge.carrental.domain;

import io.hypersistence.utils.hibernate.type.range.Range;
import io.hypersistence.utils.hibernate.type.range.PostgreSQLRangeType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", nullable = false)
    private CarType carType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "time_range", columnDefinition = "tsrange", nullable = false)
    @org.hibernate.annotations.Type(PostgreSQLRangeType.class)
    private Range<LocalDateTime> timeRange;

    @Column(name = "start_ts", nullable = false)
    private OffsetDateTime startTs;
    @Column(name = "end_ts", nullable = false)
    private OffsetDateTime endTs;

    @Column(nullable = false)
    private Integer days;

    @Column(name = "price_per_day", nullable = false)
    private BigDecimal pricePerDay;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(name = "license_key")
    private String licenseKey;

    @Column(name = "car_registration_number")
    private String carRegistrationNumber;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
