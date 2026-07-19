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

## 현재 진행 상태 (2026-07-19 기준)

- Spring Boot 4.1.0 + Java 25 toolchain으로 `./gradlew clean build` 성공
- **1~10단계 구현 완료.** 구현된 클래스:
  - `domain/PropertyData`, `repository/PropertyRepository`, `config/RedisConfig`, `controller/PropertyController` — 1단계 뼈대
  - `cache/CacheResult` — sealed interface(`Hit`/`Miss`/`Error`), Java 25 switch 패턴 매칭으로 소비 (2단계)
  - `cache/RedisPropertyCache` — `get()`/`put()` 모두 `@CircuitBreaker(name = "redisCache")` 적용, `put()`은 TTL 5분
  - `cache/LocalPropertyCache` — Caffeine, TTL 30분, `Optional` 반환 (에러 개념 없어서 CacheResult 미적용)
  - `lock/RedisDistributedLock` — `StringRedisTemplate` + `SET NX PX`(토큰) 획득, Lua 스크립트로 compare-and-delete 해제 (7단계, Redisson 대신 직접 구현 선택)
  - `service/PropertyService` — Hit(Local put 후 응답) / Miss(분산락 시도 → 성공시 DB조회+Redis set+Local put, 실패시 대기 후 폴백) / Error(Local get, hit면 stale 응답, miss면 DB조회 후 Local put만) 3-way 분기. DB 조회는 `ConcurrentHashMap<Long, CompletableFuture<PropertyData>>` 기반 인스턴스 내 single-flight(`fetchFromDbSingleFlight`)로 감쌈 (6단계)
  - `config/CircuitBreakerStateLogger` — `redisCache` circuit breaker의 상태 전이를 WARN 로그로 기록 (9단계)
- **8단계 완료 (락 경합 중 stale 응답 검증).** 로컬 Redis 컨테이너(`docker run -d --name redis-cache-fallback -p 6379:6379 redis:7`, localhost:6379)를 띄우고 `src/test/java/.../service/PropertyServiceLockContentionTest.java`에 3개 시나리오를 결정론적으로 재현하는 통합 테스트 작성, 전부 통과:
  - 락 경합 + Local hit → DB를 건드리지 않고 stale 값 즉시 반환 (Local TTL > Redis TTL 설계 의도 실측 확인)
  - 락 경합 + Local miss → 재시도 창 안에 다른 로더가 채운 Redis 값을 폴링으로 잡아채고 DB 미접근
  - 재시도 창 소진 → DB로 폴백하되 Redis는 채우지 않음
  - 테스트는 테스트 스레드가 직접 `RedisDistributedLock.tryLock`으로 같은 락 키를 선점해 경합을 인위적으로 재현하는 방식(실 스레드 레이스에 의존하지 않음)
- **9단계 완료 (Circuit Breaker).** `resilience4j-spring-boot4`를 `2.4.0`으로 명시적 버전 pin (Spring Boot 4 지원이 2.4.0에서 추가됐지만 resilience4j 자체 BOM 파일에서 해당 아티팩트가 누락된 버그가 있어 BOM에 못 맡김). `RedisPropertyCache.get()`의 기존 내부 try/catch를 제거해야만 서킷 브레이커가 실패를 실제로 카운트한다는 게 핵심 — fallbackMethod가 실제 Redis 예외와 Open 상태의 `CallNotPermittedException`을 동일하게 `CacheResult.Error`/no-op으로 흡수. `application.yml`에서 `sliding-window-size: 10`만 넣고 `minimum-number-of-calls`를 빼먹으면 기본값 100 때문에 실패율 평가가 절대 발동하지 않는 함정이 있어 `minimum-number-of-calls: 10`으로 명시. 실제 Redis 컨테이너를 `docker stop/start`로 끊었다 살리며 Closed→Open→Half-open→Closed 전 구간을 실측 검증.
- **10단계 완료 (종합 통합 테스트).** `PropertyServiceBasicPathsTest`(Redis Hit/Miss/Error x Local hit/miss 4개 기본 분기), `PropertyServiceConcurrencyTest`(동일 key 100 스레드 동시 요청 시 DB 1회만 호출, 서로 다른 key 100개 동시 miss, 락 경합 중 50 스레드 동시 요청 시 전부 stale 반환+DB 미접근) 추가. 테스트 작성 중 실제 버그 2개 발견: 스레드풀 크기가 스레드 수보다 작아 생기는 기아 데드락, 그리고 **Redis는 실제 외부 프로세스라 테스트 실행 사이에 초기화가 안 되는데 H2는 매 JVM마다 id가 1부터 다시 시작**해서 이전 실행의 leftover Redis 값과 새 id가 우연히 겹치는 flaky 버그 (8단계 테스트에도 같은 문제가 있어 `redisTemplate.delete`로 방어 추가).
- **락 재시도 창을 TTL 기반으로 확장 (10단계 이후 개선).** 7단계의 알려진 한계(재시도 창 500ms ≪ 락 TTL 3초)를 실제로 고침:
  - 고정 재시도 횟수 상수를 없애고 `LOCK_WAIT_BUDGET = LOCK_TTL - 재시도간격×2`(≈2.8초)로 TTL에서 유도 - TTL을 바꾸면 대기 예산도 같이 따라와서 둘이 다시 어긋나지 않음
  - 락 획득→DB조회→Redis set→Local put→unlock 로직을 `attemptLockedLoad`로 추출해 최초 시도와 대기 루프 재시도가 공유
  - `waitForOtherLoaderOrFallback`이 매 폴링마다 Redis 값 확인 + 직접 락 재획득 시도를 함께 반복 → 홀더가 살아있는 동안엔 인스턴스 수와 무관하게 DB가 1번만 조회되고, 홀더가 진짜 죽어 TTL이 만료된 경우에만 최종 DB 폴백
  - 트레이드오프: 최악의 지연이 500ms→~2.8초로 늘어남. Pub/Sub 기반 이벤트 알림(폴링 제거)은 논의만 하고 별도 개선안으로 보류
- **docker-compose 도입.** 수동 `docker run`으로 띄우던 `redis-cache-fallback`을 `docker-compose.yml`로 옮기고, `mysql-cache-fallback`(호스트 포트 3307) 서비스도 함께 정의. 호스트에 이미 네이티브 MySQL(3306)과 다른 프로젝트용 `mysql` 컨테이너(43306)가 떠 있어서 포트/이름 충돌을 피해 3307, `mysql-cache-fallback`으로 구분.
- **H2 → 실제 MySQL로 전환.** `build.gradle`에서 `spring-boot-h2console`/`com.h2database:h2`를 제거하고 `com.mysql:mysql-connector-j` 추가. `application.yml`의 datasource를 `jdbc:mysql://localhost:3307/cachefallback`(`root`/`cachefallback`)로 교체, `h2.console` 설정 제거. `ddl-auto: update`가 그대로 스키마를 자동 생성해줘서 별도 마이그레이션 없이 전환 완료, 전체 테스트 통과 확인. 데이터를 만드는 테스트 클래스마다 `@BeforeEach`에서 `propertyRepository.deleteAll()`로 정리(TRUNCATE는 auto-increment를 리셋해서 flaky 버그가 재발하므로 안 씀).
- **GET /properties/{id} 인수(acceptance) 테스트 추가.** 기존 테스트는 전부 `PropertyService`/`RedisPropertyCache`를 자바 메서드로 직접 호출했는데, 실제 HTTP 엔드포인트를 `MockMvc`(`@AutoConfigureMockMvc`)로 블랙박스처럼 두드려서 모든 분기+예외 케이스를 검증하는 `controller/PropertyControllerAcceptanceTest`를 추가 (Hit/Miss/락경합 3종/CircuitOpen 2종/존재하지 않는 id(404)/타입 불일치(400), 9개 시나리오).
  - **결함 발견:** `PropertyController`가 예외를 전혀 처리 안 해서 존재하지 않는 id 조회 시 500이 나가고 있었음. 처음엔 `IllegalArgumentException`을 바로 404에 매핑하려 했으나, 범용 예외라 다른 이유로 던져져도 전부 404로 둔갑할 위험이 있어 전용 `PropertyNotFoundException`(신규)을 만들어 `PropertyService.fetchFromDb`가 이것만 던지도록 교체, `ApiExceptionHandler`(`@RestControllerAdvice`)가 이 타입만 404로 매핑하도록 함.
  - Boot 4.1.0에서 `@AutoConfigureMockMvc` 패키지가 `org.springframework.boot.test.autoconfigure.web.servlet` → `org.springframework.boot.webmvc.test.autoconfigure`로 이동한 것도 확인.
  - 기존 테스트 전부에 한국어 `@DisplayName` 추가, `ExecutorService`/`ScheduledExecutorService`를 try-with-resources로 정리.
- 다음 실제 구현 착수 지점 없음 — 10단계 계획 전체 완료. 이후 방향은 다음 세션에서 새로 정함.
