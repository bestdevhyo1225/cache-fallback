package com.example.cachefallback.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerStateLogger {

  private static final Logger log = LoggerFactory.getLogger(CircuitBreakerStateLogger.class);

  public CircuitBreakerStateLogger(CircuitBreakerRegistry circuitBreakerRegistry) {
    circuitBreakerRegistry.circuitBreaker("redisCache")
        .getEventPublisher()
        .onStateTransition(event ->
            log.warn(
                "redisCache circuit breaker state: {} -> {}",
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState()
            )
        );
  }
}
