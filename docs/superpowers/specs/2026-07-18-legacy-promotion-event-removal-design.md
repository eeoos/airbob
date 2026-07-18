# 레거시 프로모션 Event 도메인 제거 설계

## 목표

독립적인 사용처가 없고 선착순 쿠폰과 책임이 중복되는 레거시 프로모션 `event` 도메인을 제거한다. 선착순 보상 발급은 `coupon` 도메인의 단일 책임으로 통합한다.

이 작업은 [선착순 쿠폰 분산 락·Lua 비교 설계](./2026-07-18-coupon-lock-lua-comparison-design.md)와 분리된 선행 정리 작업으로 구현하고 커밋한다.

## 제거 근거

- `Event.maxParticipants`는 `Coupon.totalQuantity`와 같은 수량 제한을 표현한다.
- `Event.startAt`, `endAt`, `status`는 쿠폰의 발급 기간과 활성 상태에 포함된다.
- `EventParticipant`는 선착순 당첨자를 기록하지만 실제 보상을 지급하지 않으며, 쿠폰 도메인의 `MemberCoupon`이 이 역할을 완성한다.
- 다른 도메인에서 프로모션 `Event` 또는 `EventParticipant`를 참조하지 않는다.
- 저장소 내부에 `/api/v1/event/{eventId}` 호출자와 event 도메인 테스트가 없다.
- 초기 데이터는 이미 종료된 데모 이벤트 한 건이다.
- 현재 Redis Queue 길이는 Consumer가 꺼낼 때 감소하므로 전체 참여자 수를 나타내지 못하고, 5분 TTL 이후 중복 신청도 다시 허용될 수 있다.
- API가 비동기 DB 저장 전에 성공을 반환하고 DB 실패를 로그만 남겨 응답과 최종 상태가 달라질 수 있다.

## 제거 범위

### 애플리케이션 코드

다음 패키지 전체를 제거한다.

```text
src/main/java/kr/kro/airbob/domain/event/
```

이에 따라 다음 사용자 API도 제거된다.

```http
POST /api/v1/event/{eventId}
```

호환용 deprecated API나 `410 Gone` 응답은 유지하지 않는다. 저장소 외부에서 이 API를 사용하는 클라이언트가 없다는 승인된 전제를 적용하며, 제거 후 요청은 일반적인 `404 Not Found`가 된다.

### 데이터베이스

이미 적용된 `V1__init.sql`은 수정하지 않는다. 다음 순번의 새 Flyway 마이그레이션을 추가한다.

```text
V13__drop_legacy_event_domain.sql
```

외래 키 순서에 맞춰 테이블을 제거한다.

```sql
DROP TABLE event_participant;
DROP TABLE event;
```

마이그레이션은 두 테이블의 모든 데이터를 영구 삭제한다. 배포 대상 DB에 보존할 프로모션 이벤트 데이터가 없다는 전제를 사용하며, 배포 전 두 테이블의 행 수를 확인한다. 데이터가 존재해 보존이 필요하면 마이그레이션 적용 전에 별도로 내보낸다.

새 데이터베이스는 기존 이력을 순서대로 적용하므로 V1에서 테이블을 생성한 뒤 V13에서 제거한다. 불필요해 보여도 적용된 마이그레이션의 체크섬을 보존하기 위해 V1을 다시 작성하지 않는다.

### 문서

비즈니스 ERD에서 다음을 제거한다.

- `event` 테이블
- `event_participant` 테이블
- 두 테이블의 관계
- Promotional Event 테이블 그룹

Kafka, Debezium, Outbox를 설명하는 문서의 일반적인 `event` 표현은 프로모션 도메인과 무관하므로 유지한다.

## 유지 범위

다음 이벤트 기반 아키텍처 코드는 삭제하지 않는다.

- `kr.kro.airbob.domain.payment.event`
- `kr.kro.airbob.domain.reservation.event`
- `kr.kro.airbob.outbox.EventType`와 Outbox 구성
- Kafka Consumer와 Translator
- Debezium 이벤트 파서와 CDC 설정
- 검색 인덱싱 이벤트
- `failed_indexing_events` 테이블

프로모션 도메인 이름과 통합 이벤트라는 일반 용어가 같을 뿐 서로 다른 책임이다.

## 대체 기능

프로모션 이벤트 API를 다른 형태로 이전하지 않는다. 오전 10시 선착순 발급은 쿠폰 설계의 다음 API가 담당한다.

```http
POST /api/v1/coupons/{couponId}/issue/lock
POST /api/v1/coupons/{couponId}/issue/lua
```

당첨 결과는 별도 이벤트 참가자가 아니라 `member_coupon`으로 저장되어 실제 예약 할인에 사용된다.

## 구현 순서

1. 프로모션 event 패키지를 제거한다.
2. V13 Flyway 마이그레이션을 추가한다.
3. 비즈니스 ERD에서 프로모션 이벤트 모델을 제거한다.
4. 프로모션 event 참조가 남지 않았는지 검사한다.
5. 전체 빌드와 테스트를 실행한다.
6. 이 정리만 별도 커밋한다.
7. 이후 쿠폰 분산 락·Lua 비교 구현을 시작한다.

## 검증

- `kr.kro.airbob.domain.event` import와 참조가 남아 있지 않다.
- `/api/v1/event` 매핑이 남아 있지 않다.
- Flyway가 V13을 적용하고 두 테이블이 존재하지 않는다.
- 결제, 예약, Outbox, Kafka 관련 이벤트 코드는 그대로 컴파일된다.
- 전체 Gradle 테스트가 통과한다.
- 비즈니스 ERD가 실제 V13 이후 스키마와 일치한다.

## 커밋 경계

event 제거는 쿠폰 기능 변경과 같은 커밋에 섞지 않는다. 이 커밋은 레거시 프로모션 도메인 삭제와 관련 마이그레이션·ERD 갱신만 포함한다. 쿠폰 기능은 후속 구현 계획과 별도 커밋으로 진행한다.
