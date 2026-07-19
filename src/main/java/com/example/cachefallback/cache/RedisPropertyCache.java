package com.example.cachefallback.cache;

import com.example.cachefallback.domain.PropertyData;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPropertyCache {

  private static final String KEY_PREFIX = "property:";
  private static final Duration TTL = Duration.ofMinutes(5);

  private final RedisTemplate<String, PropertyData> redisTemplate;

  public RedisPropertyCache(RedisTemplate<String, PropertyData> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @CircuitBreaker(name = "redisCache", fallbackMethod = "getFallback")
  public CacheResult<PropertyData> get(Long id) {
    PropertyData data = redisTemplate.opsForValue().get(KEY_PREFIX + id);
    if (data == null) {
      return new CacheResult.Miss<>();
    }
    return new CacheResult.Hit<>(data);
  }

  private CacheResult<PropertyData> getFallback(Long id, Throwable t) {
    return new CacheResult.Error<>(t);
  }

  @CircuitBreaker(name = "redisCache", fallbackMethod = "putFallback")
  public void put(Long id, PropertyData data) {
    redisTemplate.opsForValue().set(KEY_PREFIX + id, data, TTL);
  }

  private void putFallback(Long id, PropertyData data, Throwable t) {
  }
}
