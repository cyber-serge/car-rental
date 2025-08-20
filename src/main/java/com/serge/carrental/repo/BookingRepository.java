package com.serge.carrental.repo;

import com.serge.carrental.domain.Booking;
import com.serge.carrental.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Query(value = """
        SELECT COUNT(*) 
        FROM bookings b 
        WHERE b.type_id = :typeId 
          AND b.status IN (:statuses)
          AND b.time_range && tsrange(:fromTs, :toTs, '[)')
        """, nativeQuery = true)
    long countOverlapping(@Param("typeId") String typeId,
                          @Param("fromTs") OffsetDateTime fromTs,
                          @Param("toTs") OffsetDateTime toTs,
                          @Param("statuses") List<String> statuses);

    @Query(value = """
        SELECT * FROM bookings b
        WHERE (:status IS NULL OR b.status = :status)
          AND b.start_ts < :toTs AND b.end_ts > :fromTs
        ORDER BY b.created_at DESC
        """, nativeQuery = true)
    List<Booking> findForAdmin(@Param("status") String status,
                               @Param("fromTs") OffsetDateTime fromTs,
                               @Param("toTs") OffsetDateTime toTs);

    List<Booking> findByStatusAndStartTsBeforeAndEndTsAfter(BookingStatus status, OffsetDateTime now1, OffsetDateTime now2);

    /**
     * Atomic CAS insert: inserts a booking row only if overlapping active bookings
     * are still below the car type's total_quantity. Returns 1 on success, 0 if no capacity.
     */
    @Modifying
    @Query(value = """
        
        INSERT INTO bookings (id, user_id, type_id, status, time_range, start_ts, end_ts,
                              days, price_per_day, total, license_key, car_registration_number,
                              created_at, updated_at)
        SELECT :id, :userId, :typeId, :status,
               tsrange(:fromTs, :toTs, '[)'), :fromTs, :toTs,
               :days, :pricePerDay, :total, :licenseKey, NULL,
               :createdAt, :updatedAt
     
        WHERE (
          SELECT COUNT(*)
          FROM bookings b
          WHERE b.type_id = :typeId
            AND b.status IN (:statuses)
            AND b.time_range && tsrange(:fromTs, :toTs, '[)')
        ) < (SELECT c.total_quantity FROM car_types c WHERE c.id = :typeId FOR SHARE)
        """, nativeQuery = true)
    int tryInsertBooking(@Param("id") UUID id,
                         @Param("userId") UUID userId,
                         @Param("typeId") String typeId,
                         @Param("status") String status,
                         @Param("fromTs") OffsetDateTime fromTs,
                         @Param("toTs") OffsetDateTime toTs,
                         @Param("days") int days,
                         @Param("pricePerDay") BigDecimal pricePerDay,
                         @Param("total") BigDecimal total,
                         @Param("licenseKey") String licenseKey,
                         @Param("createdAt") OffsetDateTime createdAt,
                         @Param("updatedAt") OffsetDateTime updatedAt,
                         @Param("statuses") List<String> statuses);

}
