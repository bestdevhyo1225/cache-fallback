package com.example.cachefallback.cache;

import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.repository.PropertyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest
class RedisCircuitBreakerFailureTest {

    private static final String CONTAINER_NAME = "redis-cache-fallback";

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RedisPropertyCache redisPropertyCache;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void redisOutage_opensCircuit_thenRecoversThroughHalfOpen() throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        circuitBreaker.reset();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        PropertyData saved = propertyRepository.save(new PropertyData("서킷-테스트-값", 9_000L));
        Long id = saved.getId();

        stopRedisContainer();
        try {
            for (int i = 0; i < 10; i++) {
                assertInstanceOf(CacheResult.Error.class, redisPropertyCache.get(id),
                        "Redis가 죽어 있으므로 실제 예외가 Error로 변환돼야 한다");
            }
            assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState(),
                    "sliding window(10) 안에서 실패율(50%)을 넘겨 Open으로 전이해야 한다");

            assertInstanceOf(CacheResult.Error.class, redisPropertyCache.get(id),
                    "Open 상태에서는 CallNotPermittedException으로 즉시 fallback돼야 한다");
        } finally {
            startRedisContainer();
        }

        Thread.sleep(5_500);

        assertInstanceOf(CacheResult.Miss.class, redisPropertyCache.get(id),
                "wait-duration 경과 후 첫 호출은 Half-open 시험 호출로 실제 Redis를 다시 타야 한다");
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        assertInstanceOf(CacheResult.Miss.class, redisPropertyCache.get(id),
                "두 번째 시험 호출도 성공해야 Closed로 복귀한다");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    private static void stopRedisContainer() throws IOException, InterruptedException {
        new ProcessBuilder("docker", "stop", CONTAINER_NAME).inheritIO().start().waitFor();
    }

    private static void startRedisContainer() throws IOException, InterruptedException {
        new ProcessBuilder("docker", "start", CONTAINER_NAME).inheritIO().start().waitFor();
        Thread.sleep(500);
    }
}
