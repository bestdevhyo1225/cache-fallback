package com.example.cachefallback.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisDistributedLock {

  private static final String KEY_PREFIX = "lock:";

  private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
      if redis.call('get', KEYS[1]) == ARGV[1] then
          return redis.call('del', KEYS[1])
      else
          return 0
      end
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  public RedisDistributedLock(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public Optional<String> tryLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, token, ttl);
    return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
  }

  public void unlock(String key, String token) {
    redisTemplate.execute(UNLOCK_SCRIPT, List.of(KEY_PREFIX + key), token);
  }
}
