package com.serge.carrental.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.Booking;
import com.serge.carrental.domain.BookingStatus;
import com.serge.carrental.domain.CarType;
import com.serge.carrental.repo.BookingRepository;
import com.serge.carrental.repo.CarTypeRepository;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final BookingRepository bookingRepository;
    private final CarTypeRepository carTypeRepository;

    @GetMapping("/bookings")
    public List<AdminBookingItem> listBookings(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        OffsetDateTime f = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime t = to.withOffsetSameInstant(ZoneOffset.UTC);
        log.info("admin.bookings.list status={} from={} to={}", status, f, t);
        return bookingRepository.findForAdmin(status == null ? null : status.name(), f, t)
                .stream().map(AdminBookingItem::from).collect(Collectors.toList());
    }

    @PostMapping("/bookings/{id}/confirm")
    @Transactional
    public ResponseEntity<?> confirm(@PathVariable UUID id, @RequestBody ConfirmBody body) {
        Booking b = bookingRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Not found"));
        if (b.getStatus() != BookingStatus.TO_CONFIRM) return ResponseEntity.badRequest().body(Map.of("error","INVALID_STATE"));
        b.setCarRegistrationNumber(body.getCarRegistrationNumber());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(b.getStartTs()) && now.isBefore(b.getEndTs())) {
            b.setStatus(BookingStatus.OCCUPIED);
        } else {
            b.setStatus(BookingStatus.BOOKED);
        }
        b.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        bookingRepository.save(b);
        log.info("admin.bookings.confirm.success id={} newStatus={}", id, b.getStatus());
        return ResponseEntity.ok(Map.of(
                "status", b.getStatus().name(),
                "carRegistrationNumber", b.getCarRegistrationNumber()
        ));
    }

    @PostMapping("/bookings/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(@PathVariable UUID id) {
        log.info("admin.bookings.reject id={}", id);
        Booking b = bookingRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Not found"));
        if (b.getStatus() == BookingStatus.CANCELLED || b.getStatus() == BookingStatus.REJECTED || b.getStatus() == BookingStatus.FINISHED)
            return ResponseEntity.badRequest().body(Map.of("error","INVALID_STATE"));
        b.setStatus(BookingStatus.REJECTED);
        b.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        bookingRepository.save(b);
        log.info("admin.bookings.reject.success id={}", id);
        return ResponseEntity.ok(Map.of("status", b.getStatus().name()));
    }

    @GetMapping("/stats")
    public List<AdminTypeStats> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        OffsetDateTime f = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime t = to.withOffsetSameInstant(ZoneOffset.UTC);
        log.info("admin.stats from={} to={}", f, t);
        long windowHours = Math.max(1, Duration.between(f, t).toHours());
        Map<String, AdminTypeStats> out = new LinkedHashMap<>();
        for (CarType ct : carTypeRepository.findAll()) {
            out.put(ct.getId(), new AdminTypeStats(ct.getId(), 0d, 0d, 0d));
        }
        // naive aggregation in-memory
        for (Booking b : bookingRepository.findForAdmin(null, f, t)) {
            double pastOverlapHours = overlapHours(b.getStartTs(), b.getEndTs(), f, OffsetDateTime.now(ZoneOffset.UTC).isAfter(t)? t : OffsetDateTime.now(ZoneOffset.UTC));
            double futureOverlapHours = overlapHours(b.getStartTs(), b.getEndTs(), OffsetDateTime.now(ZoneOffset.UTC), t);
            AdminTypeStats s = out.get(b.getCarType().getId());
            s.setHoursBookedPast(s.getHoursBookedPast() + Math.max(0, pastOverlapHours));
            s.setHoursBookedFuture(s.getHoursBookedFuture() + Math.max(0, futureOverlapHours));
        }
        for (CarType ct : carTypeRepository.findAll()) {
            AdminTypeStats s = out.get(ct.getId());
            double denom = windowHours * ct.getTotalQuantity();
            s.setPastUtilizationPercent(denom == 0 ? 0 : (s.getHoursBookedPast() / denom) * 100.0);
        }
        return new ArrayList<>(out.values());
    }

    private static long overlapHours(OffsetDateTime aStart, OffsetDateTime aEnd, OffsetDateTime bStart, OffsetDateTime bEnd) {
        OffsetDateTime start = aStart.isAfter(bStart) ? aStart : bStart;
        OffsetDateTime end = aEnd.isBefore(bEnd) ? aEnd : bEnd;
        if (end.isBefore(start)) return 0;
        return Math.max(0, Duration.between(start, end).toHours());
    }

    // DTOs
    @Data
    public static class ConfirmBody {
        private String carRegistrationNumber;
    }
    @Data
    public static class AdminBookingItem {
        private UUID bookingId;
        private String status;
        private String userEmail;
        private String userPhone;
        private String typeId;
        private OffsetDateTime start;
        private OffsetDateTime end;
        private String licenseImageUrlOrKey;
        private String carRegistrationNumber;
        private Integer days;
        private BigDecimal pricePerDay;
        private BigDecimal total;

        public static AdminBookingItem from(Booking b) {
            AdminBookingItem i = new AdminBookingItem();
            i.bookingId = b.getId();
            i.status = b.getStatus().name();
            i.userEmail = b.getUser().getEmail();
            i.userPhone = b.getUser().getPhone();
            i.typeId = b.getCarType().getId();
            i.start = b.getStartTs();
            i.end = b.getEndTs();
            i.licenseImageUrlOrKey = b.getLicenseKey();
            i.carRegistrationNumber = b.getCarRegistrationNumber();
            i.days = b.getDays();
            i.pricePerDay = b.getPricePerDay();
            i.total = b.getTotal();
            return i;
        }
    }
    @Data
    public static class AdminTypeStats {
        @NotNull
        private String typeId;
        private Double hoursBookedPast;
        private Double hoursBookedFuture;
        private Double pastUtilizationPercent;

        public AdminTypeStats(String typeId, Double past, Double future, Double util) {
            this.typeId = typeId;
            this.hoursBookedPast = past;
            this.hoursBookedFuture = future;
            this.pastUtilizationPercent = util;
        }
    }
}
