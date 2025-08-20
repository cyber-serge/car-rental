package com.serge.carrental.repo;

import com.serge.carrental.domain.Booking;
import com.serge.carrental.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
}
