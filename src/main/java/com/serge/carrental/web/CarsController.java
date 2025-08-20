package com.serge.carrental.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.CarType;
import com.serge.carrental.repo.BookingRepository;
import com.serge.carrental.repo.CarTypeRepository;
import com.serge.carrental.service.AvailabilityService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cars")
@RequiredArgsConstructor
public class CarsController {
    private static final Logger log = LoggerFactory.getLogger(CarsController.class);
    private final CarTypeRepository carTypeRepository;
    private final AvailabilityService availabilityService;

    @GetMapping("/types")
    public List<CarTypeDto> types() {
        List<CarTypeDto> out = carTypeRepository.findAll().stream().map(CarTypeDto::from).collect(Collectors.toList());
        log.debug("cars.types count={}", out.size());
        return out;
    }

    @GetMapping("/search")
    public List<AvailabilityDto> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        OffsetDateTime f = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime t = to.withOffsetSameInstant(ZoneOffset.UTC);
        log.info("cars.search from={} to={}", f, t);
        Map<String,Integer> avail = availabilityService.availabilityAll(f, t);
        int days = AvailabilityService.daysBetweenCeil(f, t);
        List<AvailabilityDto> out = new ArrayList<>();
        for (CarType ct: carTypeRepository.findAll()) {
            int a = avail.getOrDefault(ct.getId(), 0);
            out.add(AvailabilityDto.of(ct, a, days));
        }
        return out;
    }

    @GetMapping("/types/{typeId}")
    public ResponseEntity<?> typeDetail(
            @PathVariable String typeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "false") boolean bypassCache
    ) {
        log.info("cars.typeDetail typeId={} from={} to={} bypassCache={}", typeId, from, to, bypassCache);
        CarType ct = carTypeRepository.findById(typeId).orElseThrow(() -> new NoSuchElementException("No such car type"));
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("typeId", ct.getId());
        body.put("displayName", ct.getDisplayName());
        body.put("description", ct.getDescription());
        body.put("pricePerDay", ct.getPricePerDay());
        body.put("totalQuantity", ct.getTotalQuantity());
        body.put("photoUrl", ct.getPhotoUrl());
        body.put("metadata", ct.getMetadata());
        if (from != null && to != null) {
            OffsetDateTime f = from.withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime t = to.withOffsetSameInstant(ZoneOffset.UTC);
            int available = availabilityService.availabilityForType(ct, f, t, bypassCache);
            int days = AvailabilityService.daysBetweenCeil(f, t);
            log.debug("cars.typeDetail.availability typeId={} available={} days={}", ct.getId(), available, days);
            body.put("available", available);
            body.put("days", days);
            body.put("estimatedTotal", ct.getPricePerDay().multiply(BigDecimal.valueOf(days)));
        }
        return ResponseEntity.ok(body);
    }

    // DTOs
    @Data
    public static class CarTypeDto {
        private String id;
        private String displayName;
        private String photoUrl;
        private String description;
        private Integer totalQuantity;
        private BigDecimal pricePerDay;
        private Map<String,Object> metadata;
        public static CarTypeDto from(CarType ct) {
            CarTypeDto d = new CarTypeDto();
            d.id = ct.getId();
            d.displayName = ct.getDisplayName();
            d.photoUrl = ct.getPhotoUrl();
            d.description = ct.getDescription();
            d.totalQuantity = ct.getTotalQuantity();
            d.pricePerDay = ct.getPricePerDay();
            d.metadata = ct.getMetadata();
            return d;
        }
    }

    @Data
    public static class AvailabilityDto {
        private String typeId;
        private Integer available;
        private BigDecimal pricePerDay;
        private Integer days;
        private BigDecimal estimatedTotal;
        private String photoUrl;
        private Map<String,Object> metadata;

        public static AvailabilityDto of(CarType ct, int available, int days) {
            AvailabilityDto d = new AvailabilityDto();
            d.typeId = ct.getId();
            d.available = available;
            d.pricePerDay = ct.getPricePerDay();
            d.days = days;
            d.estimatedTotal = ct.getPricePerDay().multiply(BigDecimal.valueOf(days));
            d.photoUrl = ct.getPhotoUrl();
            d.metadata = ct.getMetadata();
            return d;
        }
    }
}
