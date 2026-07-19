package com.example.cachefallback.cache;

import com.example.cachefallback.domain.PropertyData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LocalPropertyCache {

  private final Cache<Long, PropertyData> cache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(30))
      .maximumSize(10_000)
      .build();

  public Optional<PropertyData> get(Long id) {
    return Optional.ofNullable(cache.getIfPresent(id));
  }

  public void put(Long id, PropertyData data) {
    cache.put(id, data);
  }
}
