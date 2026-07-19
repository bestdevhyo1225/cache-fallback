package com.example.cachefallback.service;

import com.example.cachefallback.cache.CacheResult;
import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.lock.RedisDistributedLock;
import com.example.cachefallback.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DisplayName("분산락 경합 중 stale 응답 검증")
class PropertyServiceLockContentionTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RedisPropertyCache redisPropertyCache;

    @Autowired
    private LocalPropertyCache localPropertyCache;

    @Autowired
    private RedisDistributedLock distributedLock;

    @Autowired
    private RedisTemplate<String, PropertyData> redisTemplate;

    private static String lockKeyFor(Long id) {
        return "property:" + id;
    }

    @BeforeEach
    void cleanPropertyData() {
        propertyRepository.deleteAll();
    }

    @Test
    @DisplayName("락 경합 + Local hit -> DB를 건드리지 않고 stale 값을 즉시 반환한다")
    void lockContentionWithLocalHit_returnsStaleValueWithoutTouchingDb() {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 1_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);
        PropertyData stale = new PropertyData("오래된-Local-값", 1L);
        localPropertyCache.put(id, stale);

        Optional<String> token = distributedLock.tryLock(lockKeyFor(id), Duration.ofSeconds(5));
        assertTrue(token.isPresent(), "테스트 스레드가 먼저 락을 잡아 경합 상황을 만들어야 한다");
        try {
            PropertyData result = propertyService.getProperty(id);

            assertEquals(stale.getName(), result.getName());
            assertNotEquals(saved.getName(), result.getName());
        } finally {
            distributedLock.unlock(lockKeyFor(id), token.get());
        }
    }

    @Test
    @DisplayName("락 경합 + Local miss -> 다른 로더가 채운 Redis 값을 폴링으로 잡아챈다")
    void lockContentionWithLocalMiss_pollsRedisAndPicksUpOtherLoaderResult() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 2_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);
        PropertyData filledByOtherLoader = new PropertyData("다른-인스턴스가-채운-값", 2L);

        Optional<String> token = distributedLock.tryLock(lockKeyFor(id), Duration.ofSeconds(5));
        assertTrue(token.isPresent());

        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(() -> {
                redisPropertyCache.put(id, filledByOtherLoader);
                distributedLock.unlock(lockKeyFor(id), token.get());
            }, 150, TimeUnit.MILLISECONDS);

            PropertyData result = propertyService.getProperty(id);

            assertEquals(filledByOtherLoader.getName(), result.getName());
            assertNotEquals(saved.getName(), result.getName());
        }
    }

    @Test
    @DisplayName("락 경합 + 대기 예산 소진 -> DB로 폴백하되 Redis는 채우지 않는다")
    void lockContentionRetriesExhausted_fallsBackToDbWithoutPopulatingRedis() {
        PropertyData saved = propertyRepository.save(new PropertyData("재시도-소진-후-DB-값", 3_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        Optional<String> token = distributedLock.tryLock(lockKeyFor(id), Duration.ofSeconds(5));
        assertTrue(token.isPresent(), "재시도 창(500ms)이 다 지나도록 락을 계속 붙잡아 둔다");
        try {
            PropertyData result = propertyService.getProperty(id);

            assertEquals(saved.getName(), result.getName());
            assertInstanceOf(CacheResult.Miss.class, redisPropertyCache.get(id),
                    "재시도 소진 후 폴백 경로는 Redis를 채우지 않는다 (7단계에서 짚은 한계)");
        } finally {
            distributedLock.unlock(lockKeyFor(id), token.get());
        }
    }
}
