package com.example.cachefallback.service;

import com.example.cachefallback.cache.CacheResult;
import com.example.cachefallback.cache.LocalPropertyCache;
import com.example.cachefallback.cache.RedisPropertyCache;
import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.repository.PropertyRepository;
import org.springframework.stereotype.Service;

@Service
public class PropertyService {

  private final PropertyRepository propertyRepository;
  private final RedisPropertyCache redisPropertyCache;
  private final LocalPropertyCache localPropertyCache;

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
      case CacheResult.Miss<PropertyData> _ -> fetchFromDb(id);
      case CacheResult.Error<PropertyData> _ -> fetchFromDb(id);
    };
  }

  private PropertyData fetchFromDb(Long id) {
    return propertyRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("PropertyData not found: " + id));
  }
}
