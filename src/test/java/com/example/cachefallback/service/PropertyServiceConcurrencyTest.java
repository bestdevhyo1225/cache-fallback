package com.example.cachefallback.service;

import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.lock.RedisDistributedLock;
import com.example.cachefallback.repository.PropertyRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class PropertyServiceConcurrencyTest {

    @Autowired
    private PropertyService propertyService;

    @MockitoSpyBean
    private PropertyRepository propertyRepository;

    @Autowired
    private RedisTemplate<String, PropertyData> redisTemplate;

    @Autowired
    private LocalPropertyCache localPropertyCache;

    @Autowired
    private RedisDistributedLock distributedLock;

    @BeforeEach
    void cleanPropertyData() {
        propertyRepository.deleteAll();
    }

    @Test
    void sameKey100ConcurrentRequests_hitsDbOnlyOnce() throws InterruptedException {
        PropertyData saved = propertyRepository.save(new PropertyData("동시성-DB-값", 700L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<AtomicReference<PropertyData>> results = IntStream.range(0, threadCount)
                .mapToObj(i -> new AtomicReference<PropertyData>())
                .collect(Collectors.toList());

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.get(idx).set(propertyService.getProperty(id));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "100개 요청이 10초 안에 끝나야 한다");
        executor.shutdown();

        for (AtomicReference<PropertyData> result : results) {
            assertEquals(saved.getName(), result.get().getName());
        }
        verify(propertyRepository, times(1)).findById(id);
    }

    @Test
    void differentKeys100ConcurrentMisses_allResolveCorrectlyWithOneDbCallEach() throws InterruptedException {
        int keyCount = 100;
        List<PropertyData> saved = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            PropertyData data = propertyRepository.save(new PropertyData("분산-미스-" + i, (long) i));
            redisTemplate.delete("property:" + data.getId());
            saved.add(data);
        }

        ExecutorService executor = Executors.newFixedThreadPool(keyCount);
        CountDownLatch ready = new CountDownLatch(keyCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(keyCount);
        List<AtomicReference<PropertyData>> results = IntStream.range(0, keyCount)
                .mapToObj(i -> new AtomicReference<PropertyData>())
                .collect(Collectors.toList());

        for (int i = 0; i < keyCount; i++) {
            int idx = i;
            Long id = saved.get(idx).getId();
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.get(idx).set(propertyService.getProperty(id));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "서로 다른 key 100개가 15초 안에 끝나야 한다");
        executor.shutdown();

        for (int i = 0; i < keyCount; i++) {
            assertEquals(saved.get(i).getName(), results.get(i).get().getName());
            verify(propertyRepository, times(1)).findById(saved.get(i).getId());
        }
    }

    @Test
    void manyConcurrentRequestsDuringLockContention_allReturnStaleLocalWithoutTouchingDb()
            throws InterruptedException {
        PropertyData saved = propertyRepository.save(new PropertyData("실제-DB-값", 800L));
        Long id = saved.getId();
        redisTemplate.delete("property:" + id);
        PropertyData stale = new PropertyData("오래된-Local-값", 1L);
        localPropertyCache.put(id, stale);

        String lockKey = "property:" + id;
        Optional<String> token = distributedLock.tryLock(lockKey, Duration.ofSeconds(5));
        assertTrue(token.isPresent(), "테스트 스레드가 먼저 락을 잡아 경합 상황을 만들어야 한다");

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<AtomicReference<PropertyData>> results = IntStream.range(0, threadCount)
                .mapToObj(i -> new AtomicReference<PropertyData>())
                .collect(Collectors.toList());

        try {
            for (int i = 0; i < threadCount; i++) {
                int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        results.get(idx).set(propertyService.getProperty(id));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "50개 요청이 10초 안에 끝나야 한다");
            executor.shutdown();
        } finally {
            distributedLock.unlock(lockKey, token.get());
        }

        for (AtomicReference<PropertyData> result : results) {
            assertEquals(stale.getName(), result.get().getName());
        }
        verify(propertyRepository, never()).findById(id);
    }
}
