package com.example.cachefallback.service;

import com.example.cachefallback.cache.CacheResult;
import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.repository.PropertyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PropertyServiceBasicPathsTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RedisPropertyCache redisPropertyCache;

    @Autowired
    private LocalPropertyCache localPropertyCache;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RedisTemplate<String, PropertyData> redisTemplate;

    @BeforeEach
    void cleanPropertyData() {
        propertyRepository.deleteAll();
    }

    @Test
    void redisHit_returnsRedisValueAndWarmsLocal() {
        circuitBreakerRegistry.circuitBreaker("redisCache").reset();
        PropertyData saved = propertyRepository.save(new PropertyData("DB-값", 100L));
        Long id = saved.getId();
        PropertyData fromRedis = new PropertyData("Redis-값", 200L);
        redisPropertyCache.put(id, fromRedis);

        PropertyData result = propertyService.getProperty(id);

        assertEquals(fromRedis.getName(), result.getName());
        assertNotEquals(saved.getName(), result.getName());
        assertEquals(fromRedis.getName(), localPropertyCache.get(id).orElseThrow().getName());
    }

    @Test
    void redisMissWithoutContention_fetchesDbAndPopulatesRedisAndLocal() {
        circuitBreakerRegistry.circuitBreaker("redisCache").reset();
        PropertyData saved = propertyRepository.save(new PropertyData("DB-only-값", 300L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        PropertyData result = propertyService.getProperty(id);

        assertEquals(saved.getName(), result.getName());
        assertInstanceOf(CacheResult.Hit.class, redisPropertyCache.get(id),
                "Miss 경로는 DB 조회 후 Redis를 채워야 한다");
        assertTrue(localPropertyCache.get(id).isPresent(),
                "Miss 경로는 DB 조회 후 Local도 채워야 한다");
    }

    @Test
    void redisError_withLocalHit_returnsStaleWithoutTouchingDb() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 400L));
        Long id = saved.getId();
        PropertyData stale = new PropertyData("오래된-Local-값", 1L);
        localPropertyCache.put(id, stale);

        circuitBreaker.transitionToOpenState();
        try {
            PropertyData result = propertyService.getProperty(id);

            assertEquals(stale.getName(), result.getName());
            assertNotEquals(saved.getName(), result.getName());
        } finally {
            circuitBreaker.reset();
        }
    }

    @Test
    void redisError_withLocalMiss_fetchesDbAndPopulatesLocalOnlyNotRedis() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        PropertyData saved = propertyRepository.save(new PropertyData("Error-경로-DB-값", 500L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        circuitBreaker.transitionToOpenState();
        try {
            PropertyData result = propertyService.getProperty(id);

            assertEquals(saved.getName(), result.getName());
            Optional<PropertyData> local = localPropertyCache.get(id);
            assertTrue(local.isPresent(), "Local miss였다면 DB 조회 후 Local엔 채워야 한다");
            assertEquals(saved.getName(), local.get().getName());
        } finally {
            circuitBreaker.reset();
        }

        assertInstanceOf(CacheResult.Miss.class, redisPropertyCache.get(id),
                "Error 경로는 Redis를 절대 건드리지 않아야 한다");
    }
}
