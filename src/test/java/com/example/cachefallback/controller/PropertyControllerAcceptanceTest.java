package com.example.cachefallback.controller;

import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.lock.RedisDistributedLock;
import com.example.cachefallback.repository.PropertyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GET /properties/{id} 인수 테스트")
class PropertyControllerAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void cleanUp() {
        propertyRepository.deleteAll();
        circuitBreakerRegistry.circuitBreaker("redisCache").reset();
    }

    @Test
    @DisplayName("Redis Hit -> 200, Redis 값을 그대로 반환하고 Local도 채운다")
    void redisHit_returns200WithRedisValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("DB-값", 100L));
        Long id = saved.getId();
        PropertyData fromRedis = new PropertyData("Redis-값", 200L);
        redisPropertyCache.put(id, fromRedis);

        mockMvc.perform(get("/properties/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Redis-값"))
                .andExpect(jsonPath("$.price").value(200));
    }

    @Test
    @DisplayName("Redis Miss(경합 없음) -> 200, DB 조회 후 Redis/Local을 채운다")
    void redisMissWithoutContention_returns200WithDbValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("DB-only-값", 300L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        mockMvc.perform(get("/properties/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DB-only-값"));

        assertTrue(localPropertyCache.get(id).isPresent(), "Miss 경로는 Local도 채워야 한다");
    }

    @Test
    @DisplayName("락 경합 + Local hit -> 200, DB를 건드리지 않고 stale 값을 즉시 반환한다")
    void lockContentionWithLocalHit_returns200WithStaleValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 1_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);
        PropertyData stale = new PropertyData("오래된-Local-값", 1L);
        localPropertyCache.put(id, stale);

        String lockKey = "property:" + id;
        Optional<String> token = distributedLock.tryLock(lockKey, Duration.ofSeconds(5));
        assertTrue(token.isPresent(), "테스트 스레드가 먼저 락을 잡아 경합 상황을 만들어야 한다");
        try {
            mockMvc.perform(get("/properties/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("오래된-Local-값"));
        } finally {
            distributedLock.unlock(lockKey, token.get());
        }
    }

    @Test
    @DisplayName("락 경합 + Local miss -> 200, 다른 로더가 채운 Redis 값을 폴링으로 잡아챈다")
    void lockContentionWithLocalMiss_returns200WithOtherLoaderValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 2_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);
        PropertyData filledByOtherLoader = new PropertyData("다른-인스턴스가-채운-값", 2L);

        String lockKey = "property:" + id;
        Optional<String> token = distributedLock.tryLock(lockKey, Duration.ofSeconds(5));
        assertTrue(token.isPresent());

        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(() -> {
                redisPropertyCache.put(id, filledByOtherLoader);
                distributedLock.unlock(lockKey, token.get());
            }, 150, TimeUnit.MILLISECONDS);

            mockMvc.perform(get("/properties/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("다른-인스턴스가-채운-값"));
        }
    }

    @Test
    @DisplayName("락 경합 + 대기 예산 소진 -> 200, DB로 폴백한다 (~2.8초 소요)")
    void lockContentionRetriesExhausted_returns200WithDbValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("재시도-소진-후-DB-값", 3_000L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        String lockKey = "property:" + id;
        Optional<String> token = distributedLock.tryLock(lockKey, Duration.ofSeconds(5));
        assertTrue(token.isPresent(), "재시도 예산이 다 지나도록 락을 계속 붙잡아 둔다");
        try {
            mockMvc.perform(get("/properties/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("재시도-소진-후-DB-값"));
        } finally {
            distributedLock.unlock(lockKey, token.get());
        }
    }

    @Test
    @DisplayName("Redis Error(Circuit Open) + Local hit -> 200, stale 값을 반환한다")
    void redisErrorWithLocalHit_returns200WithStaleValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 400L));
        Long id = saved.getId();
        PropertyData stale = new PropertyData("오래된-Local-값", 1L);
        localPropertyCache.put(id, stale);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        circuitBreaker.transitionToOpenState();
        try {
            mockMvc.perform(get("/properties/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("오래된-Local-값"));
        } finally {
            circuitBreaker.reset();
        }
    }

    @Test
    @DisplayName("Redis Error(Circuit Open) + Local miss -> 200, DB로 폴백하되 Redis는 건드리지 않는다")
    void redisErrorWithLocalMiss_returns200WithDbValue() throws Exception {
        PropertyData saved = propertyRepository.save(new PropertyData("Error-경로-DB-값", 500L));
        Long id = saved.getId();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        circuitBreaker.transitionToOpenState();
        try {
            mockMvc.perform(get("/properties/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Error-경로-DB-값"));
        } finally {
            circuitBreaker.reset();
        }
    }

    @Test
    @DisplayName("존재하지 않는 id -> 404 (PropertyNotFoundException)")
    void nonExistentId_returns404() throws Exception {
        mockMvc.perform(get("/properties/{id}", 999_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("path variable 타입 불일치 -> 400 (스프링 기본 처리)")
    void nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/properties/{id}", "abc"))
                .andExpect(status().isBadRequest());
    }
}
