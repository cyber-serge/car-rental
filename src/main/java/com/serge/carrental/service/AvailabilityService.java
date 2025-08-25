package com.serge.carrental.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.CarType;
import com.serge.carrental.repo.BookingRepository;
import com.serge.carrental.repo.CarTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
    private final CarTypeRepository carTypeRepository;
    private final BookingRepository bookingRepository;
    private final StringRedisTemplate redis;

    private static final List<String> ACTIVE_STATUSES = List.of("TO_CONFIRM","BOOKED","OCCUPIED");
    private static final Duration TTL = Duration.ofMinutes(5);

    private String key(String typeId, OffsetDateTime from, OffsetDateTime to) {
        return "avail:%s:%d:%d".formatted(typeId, from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli());
    }

    private String keyAll(OffsetDateTime from, OffsetDateTime to) {
        return "availAll:%d:%d".formatted(from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli());
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> availabilityAll(OffsetDateTime from, OffsetDateTime to) {
        log.debug("availability.all from={} to={}", from, to);
        String cacheKey = keyAll(from, to);

        // 1) Try bulk cache (Redis HASH: typeId -> available)
        try {
            Map<Object, Object> cached = redis.opsForHash().entries(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                Map<String, Integer> hit = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : cached.entrySet()) {
                    hit.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue())));
                }
                log.trace("availability.all.cache.hit key={} size={}", cacheKey, hit.size());
                return hit;
            }
        } catch (Exception e) {
            log.warn("availability.all.cache.read_failed key={} err={}", cacheKey, e.toString());
        }

        // 2) Cache miss: compute using per-type (which itself caches per-type keys)
        Map<String, Integer> result = new LinkedHashMap<>();
        for (CarType ct : carTypeRepository.findAll()) {
            result.put(ct.getId(), availabilityForType(ct, from, to, false));
        }

        // 3) Write bulk cache (best effort) with TTL
        try {
            Map<String, String> toCache = new HashMap<>();
            result.forEach((k, v) -> toCache.put(k, String.valueOf(v)));
            if (!toCache.isEmpty()) {
                redis.opsForHash().putAll(cacheKey, toCache);
                redis.expire(cacheKey, TTL);
            }
            log.trace("availability.all.cache.write key={} size={}", cacheKey, result.size());
        } catch (Exception e) {
            log.warn("availability.all.cache.write_failed key={} err={}", cacheKey, e.toString());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public int availabilityForType(CarType type, OffsetDateTime from, OffsetDateTime to, boolean bypassCache) {
        String cacheKey = key(type.getId(), from, to);
        if (!bypassCache) {
            try {
                String v = redis.opsForValue().get(cacheKey);
                if (v != null) {
                    log.trace("availability.cache.hit typeId={} key={} value={}", type.getId(), cacheKey, v);
                    return Integer.parseInt(v);
                }
            } catch (Exception e) {
                log.warn("availability.cache.read_failed typeId={} key={} err={}", type.getId(), cacheKey, e.toString());
            }
        }
        long overlapping = bookingRepository.countOverlapping(type.getId(), from, to, ACTIVE_STATUSES);
        int available = Math.max(0, type.getTotalQuantity() - (int) overlapping);
        try {
            redis.opsForValue().set(cacheKey, String.valueOf(available), TTL);
        } catch (Exception e) {
            log.warn("availability.cache.write_failed typeId={} key={} err={}", type.getId(), cacheKey, e.toString());
        }
        log.trace("availability.cache.miss typeId={} from={} to={} overlapping={} total={} available={}",
                type.getId(), from, to, overlapping, type.getTotalQuantity(), available);
        return available;
    }

    public void invalidateAvailability(String typeId) {
        // Simple strategy: delete keys by prefix (requires Redis KEYS). Safer production approach: publish/subscribe or versioned keys.
        // Here we best-effort: do nothing, rely on TTL. Optionally, publish a "bump" version key.
        // For explicit invalidation, we can delete known keys if stored; to keep it simple, we skip heavy KEYS operations.
    }

    public static int daysBetweenCeil(OffsetDateTime from, OffsetDateTime to) {
        long hours = Duration.between(from, to).toHours();
        long days = (hours + 23) / 24;
        return (int)Math.max(days, 1);
    }

    public static OffsetDateTime utc(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().atOffset(ZoneOffset.UTC);
    }
}
