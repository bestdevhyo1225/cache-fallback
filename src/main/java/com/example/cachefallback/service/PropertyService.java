package com.example.cachefallback.service;

import com.example.cachefallback.cache.CacheResult;
import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.lock.RedisDistributedLock;
import com.example.cachefallback.repository.PropertyRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class PropertyService {

  private static final Duration LOCK_TTL = Duration.ofSeconds(3);
  private static final Duration LOCK_WAIT_RETRY_INTERVAL = Duration.ofMillis(100);
  private static final Duration LOCK_WAIT_BUDGET = LOCK_TTL.minus(LOCK_WAIT_RETRY_INTERVAL.multipliedBy(2));

  private final PropertyRepository propertyRepository;
  private final RedisPropertyCache redisPropertyCache;
  private final LocalPropertyCache localPropertyCache;
  private final RedisDistributedLock distributedLock;
  private final ConcurrentHashMap<Long, CompletableFuture<PropertyData>> dbLoadFutures = new ConcurrentHashMap<>();

  public PropertyService(
      PropertyRepository propertyRepository,
      RedisPropertyCache redisPropertyCache,
      LocalPropertyCache localPropertyCache,
      RedisDistributedLock distributedLock
  ) {
    this.propertyRepository = propertyRepository;
    this.redisPropertyCache = redisPropertyCache;
    this.localPropertyCache = localPropertyCache;
    this.distributedLock = distributedLock;
  }

  public PropertyData getProperty(Long id) {
    CacheResult<PropertyData> result = redisPropertyCache.get(id);
    return switch (result) {
      case CacheResult.Hit<PropertyData> hit -> {
        localPropertyCache.put(id, hit.value());
        yield hit.value();
      }
      case CacheResult.Miss<PropertyData> _ -> loadOnRedisMiss(id);
      case CacheResult.Error<PropertyData> _ -> {
        Optional<PropertyData> local = localPropertyCache.get(id);
        if (local.isPresent()) {
          yield local.get();
        }
        PropertyData data = fetchFromDbSingleFlight(id);
        localPropertyCache.put(id, data);
        yield data;
      }
    };
  }

  private PropertyData loadOnRedisMiss(Long id) {
    Optional<PropertyData> loaded = attemptLockedLoad(id);
    return loaded.orElseGet(() -> waitForOtherLoaderOrFallback(id));
  }

  private Optional<PropertyData> attemptLockedLoad(Long id) {
    Optional<String> token = distributedLock.tryLock(lockKey(id), LOCK_TTL);
    if (token.isEmpty()) {
      return Optional.empty();
    }
    try {
      PropertyData data = fetchFromDbSingleFlight(id);
      redisPropertyCache.put(id, data);
      localPropertyCache.put(id, data);
      return Optional.of(data);
    } finally {
      distributedLock.unlock(lockKey(id), token.get());
    }
  }

  private PropertyData waitForOtherLoaderOrFallback(Long id) {
    Optional<PropertyData> local = localPropertyCache.get(id);
    if (local.isPresent()) {
      return local.get();
    }

    long deadline = System.nanoTime() + LOCK_WAIT_BUDGET.toNanos();
    while (System.nanoTime() < deadline) {
      sleep();

      CacheResult<PropertyData> retry = redisPropertyCache.get(id);
      switch (retry) {
        case CacheResult.Hit<PropertyData> hit -> {
          localPropertyCache.put(id, hit.value());
          return hit.value();
        }
        case CacheResult.Miss<PropertyData> _,
             CacheResult.Error<PropertyData> _ -> {
        }
      }

      Optional<PropertyData> loaded = attemptLockedLoad(id);
      if (loaded.isPresent()) {
        return loaded.get();
      }
    }
    return fetchFromDbSingleFlight(id);
  }

  private void sleep() {
    try {
      Thread.sleep(PropertyService.LOCK_WAIT_RETRY_INTERVAL.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for lock", e);
    }
  }

  private String lockKey(Long id) {
    return "property:" + id;
  }

  private PropertyData fetchFromDbSingleFlight(Long id) {
    CompletableFuture<PropertyData> future = dbLoadFutures.computeIfAbsent(id, this::startDbLoad);
    return future.join();
  }

  private CompletableFuture<PropertyData> startDbLoad(Long id) {
    CompletableFuture<PropertyData> future = CompletableFuture.supplyAsync(() -> fetchFromDb(id));
    return future.whenComplete((_, _) -> dbLoadFutures.remove(id, future));
  }

  private PropertyData fetchFromDb(Long id) {
    return propertyRepository.findById(id)
        .orElseThrow(() -> new PropertyNotFoundException(id));
  }
}
