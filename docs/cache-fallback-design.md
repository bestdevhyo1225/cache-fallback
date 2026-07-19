---
name: project-cache-fallback-design
description: cache-fallback 프로젝트의 Redis+Local(Caffeine) 캐시 폴백 아키텍처 설계와 구현 진행 상태
metadata: 
  node_type: memory
  type: project
  originSessionId: 64a7d316-2933-45aa-81e1-97a052888828
---

cache-fallback 프로젝트(`/Users/janghyoseog/Documents/cache-fallback`)는 Redis(L2)와 로컬 Caffeine 캐시(L1)를 조합한 캐시 폴백 구조를 학습 목적으로 직접 구현하는 프로젝트다. ChatGPT와의 설계 논의 내용을 바탕으로 진행 중.

**Why:** 단순 L1/L2 캐시 조회 순서뿐 아니라 Redis 장애 시 fallback, 분산락, single-flight, circuit breaker까지 단계적으로 직접 구현해보며 학습하려는 목적. 서킷 브레이커는 아직 배우지 않은 개념이라 마지막 단계로 미뤄둠.

## 목표 아키텍처

```
Redis 정상
├─ Hit → Local put → 응답
└─ Miss → Redis 분산락
   ├─ 획득 성공 → DB 조회 → Redis set → Local put → 응답
   └─ 획득 실패 → Local hit면 stale 응답, miss면 Redis 재조회/제한 대기

Redis Error/Timeout/Circuit Open
└─ Local
   ├─ Hit → 응답
   └─ Miss → 서버 내부 single-flight → DB 조회 → Local put → 응답
```

핵심 개념: Redis 결과를 null 하나로 뭉개지 않고 `CacheResult` sealed interface(Hit/Miss/Error)로 명시적으로 분리해서 처리한다. TTL은 Redis가 짧고(예: 5분) Local이 더 길게(예: 30분) 두어, 락 경합 중에도 Local이 stale 응답을 제공할 수 있게 한다.

## 구현 10단계 계획

1. 기본 도메인/계층 경계 (controller/service/cache/repository/domain/config), 락·fallback 없는 뼈대만
2. Redis Hit/Miss/Error를 `CacheResult` sealed interface로 명시적 분리 (설계의 핵심)
3. Redis Hit 경로: Local put 후 응답
4. Redis Miss 기본 경로 (분산락 없이): DB 조회 → Redis set → Local put
5. Redis Error fallback: Local get (hit면 반환, miss면 DB 조회 후 Local put만, Redis는 건드리지 않음)
6. 서버 내부 single-flight (`ConcurrentHashMap<K, CompletableFuture<V>>`로 동일 key 중복 DB 조회 방지)
7. Redis Miss에 분산락 추가 (Redisson vs RedisTemplate SET NX PX 직접 구현 — 학습 목적이라 직접 구현 먼저)
8. 락 경합 중 stale 응답 검증 (Local TTL > Redis TTL의 이유가 여기서 드러남)
9. Circuit Breaker 추가 (Closed/Open/Half-open)
10. 테스트: Redis hit/miss/error+local hit/error+local miss, 동일 key 100개 single-flight, miss 100개+분산락, 락 실패+stale hit, 재기동 후 local miss

**How to apply:** 다음 세션에서 이 프로젝트 작업을 재개할 때, 이 단계 순서를 따라 진행 상황을 확인하고 이어서 구현.

## 현재 진행 상태 (2026-07-14 기준)

- Spring Boot 4.1.0 + Java 25 toolchain으로 `./gradlew clean build` 성공
- `resilience4j-spring-boot3` 의존성은 Boot 4와 미호환이라 제거함 (Boot 3 전용 스타터, Boot 4용 버전 아직 미확정). Circuit breaker 단계(9단계)에서 다음 중 택1 재검토 필요: Boot 4 호환 Resilience4j 버전 확인 / resilience4j-circuitbreaker 코어 모듈만 사용 / Boot 3.x로 다운그레이드
- **1~7단계 구현 완료.** 구현된 클래스:
  - `domain/PropertyData`, `repository/PropertyRepository`, `config/RedisConfig`, `controller/PropertyController` — 1단계 뼈대
  - `cache/CacheResult` — sealed interface(`Hit`/`Miss`/`Error`), Java 25 switch 패턴 매칭으로 소비 (2단계)
  - `cache/RedisPropertyCache` — `get()`이 `CacheResult<PropertyData>` 반환, `put()`은 TTL 5분
  - `cache/LocalPropertyCache` — Caffeine, TTL 30분, `Optional` 반환 (에러 개념 없어서 CacheResult 미적용)
  - `lock/RedisDistributedLock` — `StringRedisTemplate` + `SET NX PX`(토큰) 획득, Lua 스크립트로 compare-and-delete 해제 (7단계, Redisson 대신 직접 구현 선택)
  - `service/PropertyService` — Hit(Local put 후 응답) / Miss(분산락 시도 → 성공시 DB조회+Redis set+Local put, 실패시 대기 후 폴백) / Error(Local get, hit면 stale 응답, miss면 DB조회 후 Local put만) 3-way 분기. DB 조회는 `ConcurrentHashMap<Long, CompletableFuture<PropertyData>>` 기반 인스턴스 내 single-flight(`fetchFromDbSingleFlight`)로 감쌈 (6단계)
- **7단계의 알려진 한계 (의도적으로 보류, 실측만 8단계에서 완료, 수치 튜닝은 미착수):** `waitForOtherLoaderOrFallback`의 재시도 창(5회×100ms=500ms)이 락 TTL(3초)보다 훨씬 짧음. 또한 `dbLoadFutures` single-flight가 인스턴스 로컬이라 인스턴스가 여러 대일 때 크로스 인스턴스 방어가 안 됨. 결과적으로 락 홀더가 느릴 때(부하가 몰릴 때일수록) 대기 스레드들이 재시도를 다 소진하고 각자 `fetchFromDbSingleFlight`로 폴백 → 인스턴스 수만큼 DB 동시 조회가 날 수 있음. 락 홀더가 죽은 경우엔 오히려 짧은 재시도 창이 안전망 역할을 하므로 트레이드오프 있음.
- **8단계 완료 (락 경합 중 stale 응답 검증).** 로컬 Redis 컨테이너(`docker run -d --name redis-cache-fallback -p 6379:6379 redis:7`, localhost:6379)를 띄우고 `src/test/java/.../service/PropertyServiceLockContentionTest.java`에 3개 시나리오를 결정론적으로 재현하는 통합 테스트 작성, 전부 통과:
  - 락 경합 + Local hit → DB를 건드리지 않고 stale 값 즉시 반환 (Local TTL > Redis TTL 설계 의도 실측 확인)
  - 락 경합 + Local miss → 재시도 창(500ms) 안에 다른 로더가 채운 Redis 값을 폴링으로 잡아채고 DB 미접근
  - 재시도 창 소진 → DB로 폴백하되 Redis는 채우지 않음 (7단계 한계가 실측으로도 재현됨, 수치 튜닝은 여전히 미해결 상태로 남겨둠)
  - 테스트는 테스트 스레드가 직접 `RedisDistributedLock.tryLock`으로 같은 락 키를 선점해 경합을 인위적으로 재현하는 방식(실 스레드 레이스에 의존하지 않음)
- 다음 실제 구현 착수 지점: **9단계** — Circuit Breaker 추가 (Closed/Open/Half-open). 착수 전에 Boot 4 호환 Resilience4j 버전 확인 필요 (위 항목 참고)
