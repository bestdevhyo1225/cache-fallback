package com.example.cachefallback.cache;

import com.example.cachefallback.domain.PropertyData;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPropertyCache {

  private static final String KEY_PREFIX = "property:";

  private final RedisTemplate<String, PropertyData> redisTemplate;

  public RedisPropertyCache(RedisTemplate<String, PropertyData> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public CacheResult<PropertyData> get(Long id) {
    try {
      PropertyData data = redisTemplate.opsForValue().get(KEY_PREFIX + id);
      if (data == null) {
        return new CacheResult.Miss<>();
      }
      return new CacheResult.Hit<>(data);
    } catch (Exception e) {
      return new CacheResult.Error<>(e);
    }
  }
}
