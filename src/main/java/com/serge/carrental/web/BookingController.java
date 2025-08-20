package com.serge.carrental.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.*;
import com.serge.carrental.repo.BookingRepository;
import com.serge.carrental.repo.CarTypeRepository;
import com.serge.carrental.repo.UserAccountRepository;
import com.serge.carrental.service.AvailabilityService;
import com.serge.carrental.service.EmailService;
import com.serge.carrental.service.StorageService;
import io.hypersistence.utils.hibernate.type.range.Range;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {
    private static final Logger log = LoggerFactory.getLogger(BookingController.class);
    private final BookingRepository bookingRepository;
    private final CarTypeRepository carTypeRepository;
    private final UserAccountRepository userRepo;
    private final AvailabilityService availabilityService;
    private final StorageService storageService;
    private final EmailService emailService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> create(
            @RequestParam("typeId") String typeId,
            @RequestParam("start") String startIso,
            @RequestParam("end") String endIso,
            @RequestParam("driverLicense") MultipartFile driverLicense,
            @AuthenticationPrincipal Jwt jwt
    ) throws Exception {
        try {
            log.info("bookings.create typeId={} start={} end={}", typeId, startIso, endIso);
            // Payload hash for idempotency

            CarType type = carTypeRepository.findById(typeId).orElseThrow(() -> new NoSuchElementException("No such car type"));
            OffsetDateTime start = OffsetDateTime.parse(startIso).withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime end = OffsetDateTime.parse(endIso).withOffsetSameInstant(ZoneOffset.UTC);
            if (!end.isAfter(start))
                return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_ERROR", "message", "end must be after start"));



            // Upload license
            String licenseKey = storageService.uploadLicense(driverLicense.getBytes(), driverLicense.getOriginalFilename(), driverLicense.getContentType());
            log.debug("bookings.create.license_uploaded key={}", licenseKey);

            // Resolve user by JWT subject (email in "sub" or "email")
            String email = jwt.getClaimAsString("email");
            if (email == null) email = jwt.getSubject();
            UserAccount user =
                    userRepo.findByEmail(email).orElseThrow(() -> new NoSuchElementException("User not found"));
            if (!user.isEmailVerified()) return ResponseEntity.status(403).body(Map.of("error", "EMAIL_NOT_VERIFIED"));
            log.debug("bookings.create.user_resolved userId={}", user.getId());

            int days = AvailabilityService.daysBetweenCeil(start, end);
            BigDecimal total = type.getPricePerDay().multiply(BigDecimal.valueOf(days));
            OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
            UUID id = UUID.randomUUID();
            // Active statuses aligned with AvailabilityService
            List<String> active = List.of("TO_CONFIRM","BOOKED","OCCUPIED");

            int rows = bookingRepository.tryInsertBooking(
                    id,
                    user.getId(),
                    type.getId(),
                    BookingStatus.TO_CONFIRM.name(),
                    start, end,
                    days,
                    type.getPricePerDay(),
                    total,
                    licenseKey,
                    nowUtc, nowUtc,
                    active
            );
            if (rows == 0) {
                log.warn("bookings.create.no_availability (atomic) typeId={} start={} end={}", typeId, start, end);
                return ResponseEntity.status(409).body(Map.of("error", "NO_AVAILABILITY", "message", "No cars available for the requested range"));
            }

            // Fetch inserted row to build the response (and for logging)
            Booking b = bookingRepository.findById(id).orElseThrow(() -> new IllegalStateException("Inserted booking not found"));
            log.info("bookings.create.saved bookingId={} status={}", b.getId(), b.getStatus());
            // Send email (booking received)
            emailService.send(user.getEmail(), "Booking received (To Confirm)",
                    "<p>We received your booking for type <b>" + type.getDisplayName() + "</b></p>" +
                            "<p>From: " + start + "<br/>To: " + end + "<br/>Days: " + days + "<br/>Total: $" + total + "</p>" +
                            "<p>Status: TO_CONFIRM</p>");
            log.debug("bookings.create.email_sent bookingId={}", b.getId());

            // Optionally invalidate cache (we rely on TTL to keep patch shorter)
            return ResponseEntity.status(201).body(toResponse(b));
        } catch (Exception e){
            log.error("Error in service", e);
            throw e;
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        log.debug("bookings.get id={}", id);
        return bookingRepository.findById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        log.info("bookings.cancel id={}", id);
        Booking b = bookingRepository.findById(id).orElseThrow();
        if (b.getStatus() == BookingStatus.CANCELLED || b.getStatus() == BookingStatus.REJECTED || b.getStatus() == BookingStatus.FINISHED)
            return ResponseEntity.badRequest().body(Map.of("error","INVALID_STATE"));
        b.setStatus(BookingStatus.CANCELLED);
        b.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        bookingRepository.save(b);
        log.info("bookings.cancel.success id={}", id);
        return ResponseEntity.ok(Map.of("status","CANCELLED"));
    }

    private Map<String,Object> toResponse(Booking b) {
        return Map.of(
                "bookingId", b.getId(),
                "status", b.getStatus().name(),
                "typeId", b.getCarType().getId(),
                "start", b.getStartTs().toString(),
                "end", b.getEndTs().toString(),
                "days", b.getDays(),
                "pricePerDay", b.getPricePerDay(),
                "estimatedTotal", b.getTotal(),
                "licenseImageKey", b.getLicenseKey(),
                "createdAt", b.getCreatedAt().toString()
        );
    }
}
