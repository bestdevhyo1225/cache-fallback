# cache-fallback

Redis(L2)와 로컬 Caffeine 캐시(L1)를 조합한 캐시 폴백 구조를 학습 목적으로 직접 구현한 프로젝트입니다. 단순 캐시 조회 순서뿐 아니라 Redis 장애 시 fallback, 분산락, single-flight, circuit breaker까지 단계적으로 구현합니다.

자세한 설계 배경은 [`docs/cache-fallback-design.md`](docs/cache-fallback-design.md)를 참고하세요.

## 아키텍처

```
Redis 정상
├─ Hit  → Local put → 응답
└─ Miss → Redis 분산락
   ├─ 획득 성공 → DB 조회 → Redis set → Local put → 응답
   └─ 획득 실패 → Local hit면 stale 응답, miss면 Redis 재조회/제한 대기 후 DB 폴백

Redis Error / Circuit Open
└─ Local
   ├─ Hit  → 응답 (stale 허용)
   └─ Miss → 서버 내부 single-flight → DB 조회 → Local put → 응답
```

Redis 조회 결과는 `CacheResult` sealed interface(`Hit`/`Miss`/`Error`)로 명시적으로 모델링합니다. TTL은 Redis가 짧고(5분) Local이 더 깁니다(30분) — 락 경합이나 장애 중에도 Local이 stale 응답을 제공할 수 있게 하기 위해서입니다.

Redis 호출에는 [Resilience4j](https://resilience4j.readme.io/) `@CircuitBreaker`가 붙어 있어서, Redis가 계속 실패하면 Open 상태로 전환되어 이후 호출은 실제 Redis에 붙지 않고 즉시 fallback(`CacheResult.Error`)됩니다.

## 요구 사항

- Java 25
- 로컬 Redis (기본 포트 6379) — `docker-compose.yml`로 관리

```bash
docker compose up -d
```

`redis-cache-fallback`(6379) 컨테이너가 뜹니다. `mysql-cache-fallback`(호스트 포트 3307)도 같이 뜨지만, 지금은 애플리케이션이 아직 H2(in-memory)를 쓰고 있어서 실제로 연결돼 있진 않습니다 — 나중에 H2를 실제 DB로 바꿔볼 때를 대비해 미리 띄워둔 것입니다.

## 실행

```bash
./gradlew bootRun
```

`GET /properties/{id}`로 조회할 수 있습니다.

## 테스트

일부 통합 테스트는 위 Redis 컨테이너가 떠 있어야 하고, 그중 일부(`RedisCircuitBreakerFailureTest`)는 `docker stop/start`로 컨테이너를 직접 제어합니다.

```bash
./gradlew test
```

## 구현 단계

10단계 계획을 모두 완료했습니다. 각 단계는 git 커밋으로 분리되어 있습니다 (`git log --oneline`).

1. 기본 도메인/계층 뼈대 (락·폴백 없음)
2. `CacheResult` sealed interface로 Hit/Miss/Error 분리
3. Redis Hit 경로 — Local put 후 응답
4. Redis Miss 기본 경로 — DB 조회 → Redis set → Local put
5. Redis Error 폴백 — Local get, miss면 DB 조회만
6. 서버 내부 single-flight로 DB 중복 조회 방지
7. Redis Miss에 분산락 추가 (SET NX PX 직접 구현)
8. 락 경합 중 stale 응답 검증 통합 테스트
9. Circuit Breaker 추가 (Closed/Open/Half-open)
10. 종합 통합 테스트 (동시성 시나리오 포함)

## 알려진 한계

DB 조회 single-flight(`fetchFromDbSingleFlight`)가 인스턴스 로컬이라, 인스턴스가 여러 대일 때 크로스 인스턴스 방어는 안 됩니다. 다만 `waitForOtherLoaderOrFallback`이 락 TTL에서 유도한 예산(`LOCK_WAIT_BUDGET`, TTL−200ms)만큼 Redis 확인과 락 재획득을 함께 반복하도록 개선되어서, 락 홀더가 살아있는 동안(정상적으로 느리기만 한 경우)엔 인스턴스 수와 무관하게 DB가 1번만 조회됩니다. 크로스 인스턴스 중복 조회는 홀더가 실제로 죽어 TTL이 만료된 경우에만 발생하고, 그마저도 락 재획득 경쟁에서 이긴 하나의 스레드만 DB를 탑니다.

트레이드오프는 지연 시간입니다 — 최악의 경우 응답이 500ms가 아니라 락 TTL 근처(~2.8초)까지 걸릴 수 있습니다. 폴링 대신 Redis Pub/Sub으로 로더 완료를 즉시 통지받는 방식은 지연을 더 줄일 수 있는 추가 개선안으로 논의만 하고 보류했습니다.
