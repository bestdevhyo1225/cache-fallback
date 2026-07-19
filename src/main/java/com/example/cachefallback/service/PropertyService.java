package com.example.cachefallback.service;

import com.example.cachefallback.cache.CacheResult;
import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.repository.PropertyRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class PropertyService {

  private final PropertyRepository propertyRepository;
  private final RedisPropertyCache redisPropertyCache;
  private final LocalPropertyCache localPropertyCache;
  private final ConcurrentHashMap<Long, CompletableFuture<PropertyData>> dbLoadFutures = new ConcurrentHashMap<>();

  public PropertyService(
      PropertyRepository propertyRepository,
      RedisPropertyCache redisPropertyCache,
      LocalPropertyCache localPropertyCache
  ) {
    this.propertyRepository = propertyRepository;
    this.redisPropertyCache = redisPropertyCache;
    this.localPropertyCache = localPropertyCache;
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
    PropertyData data = fetchFromDbSingleFlight(id);
    redisPropertyCache.put(id, data);
    localPropertyCache.put(id, data);
    return data;
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
        .orElseThrow(() -> new IllegalArgumentException("PropertyData not found: " + id));
  }
}
