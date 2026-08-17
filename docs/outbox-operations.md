# Transactional Outbox·Debezium 운영 가이드

## 계약과 전달 의미

도메인 코드는 `messaging.event`의 canonical event 계약을 사용하고,
`messaging.outbox.application.OutboxWriter`를 비즈니스 트랜잭션 안에서 호출한다. MySQL
변경과 outbox INSERT가 함께 commit되거나 함께 rollback된다. Debezium은 commit된 INSERT를
binlog에서 읽어 Kafka로 전달한다.

이 구조가 보장하는 것은 **at-least-once 전달**이다. outbox에 Kafka ack나 delivered
column이 없으므로 DB 행만 보고 전달 완료를 판정할 수 없다. consumer는 중복 wake-up
signal을 처리할 수 있어야 한다.

- 이벤트 계약: [`messaging/event`](../src/main/java/kr/kro/airbob/messaging/event)
- outbox 구현: [`messaging/outbox`](../src/main/java/kr/kro/airbob/messaging/outbox)
- connector 설정: [`debezium-config/outbox-connector.json`](../debezium-config/outbox-connector.json)

## Connect와 topic bootstrap

broker 자동 topic 생성은 꺼져 있다. [`docker/kafka/init-topics.sh`](../docker/kafka/init-topics.sh)는
Connect 내부 topic, schema history, heartbeat와 다음 9개 비즈니스 topic을 먼저 만들고
partition 수를 검증한다.

- `PAYMENT_OPERATION.events`, `.RETRY`, `.DLT`
- `ACCOMMODATION_INDEX.events`, `.RETRY`, `.DLT`
- `OPERATOR_ALERT.events`, `.RETRY`, `.DLT`

[`docker/debezium/register-connector.sh`](../docker/debezium/register-connector.sh)는 connector
config를 idempotent `PUT`으로 등록하고 connector와 적어도 하나의 task가 모두 `RUNNING`일
때만 성공한다. 운영 secret에는 `DEBEZIUM_DATABASE_PASSWORD`가 반드시 있어야 한다.

EventRouter SMT에는 `TopicNameMatches` predicate가 적용된다. source topic
`airbob_outbox.airbobdb.outbox` 레코드만 outbox event로 route하고,
`__debezium-heartbeat.airbob_outbox`와 connector metadata는 transform을 우회한다. 이
predicate를 제거하면 heartbeat가 outbox schema로 해석되어 connector task가 실패할 수
있다.

outbox 컬럼은 다음과 같이 전달된다.

| outbox | Kafka |
| --- | --- |
| `destination` | topic 이름 |
| `partition_key` | record key |
| `payload` | String value |
| `event_id` | event ID |
| `occurred_at` | record timestamp |
| `event_type`, `event_version`, `aggregate_type`, `aggregate_id` | headers |

key/value/header converter는 raw String 계약이다. 애플리케이션 consumer가
`IntegrationEventCodec`으로 envelope descriptor와 payload를 엄격하게 검증한다.

## 전달 건강도 확인

다음 네 신호를 함께 확인한다.

1. `GET /connectors/airbob-outbox-connector/status`에서 connector와 모든 task가
   `RUNNING`인지 확인한다.
2. 10초 주기의 `__debezium-heartbeat.airbob_outbox` 최신 timestamp와 offset이 계속
   전진하는지 확인한다.
3. 대상 main topic으로 새 레코드가 들어오는지 확인한다.
4. 각 consumer group의 lag와 retry/DLT 유입을 확인한다.

```bash
curl --fail --silent --show-error \
  http://<connect-host>:8083/connectors/airbob-outbox-connector/status
```

outbox 행 수가 많거나 가장 오래된 행이 오래됐다는 사실만으로 CDC 장애라고 판단하지
않는다. cleanup이 꺼져 있으면 오래된 행이 계속 남는 것이 정상이다.

## 항상 켜지는 retention footprint 지표

cleanup 설정과 독립된 read-only health refresh가 다음 지표를 갱신한다.

| 지표 | 의미 |
| --- | --- |
| `airbob.messaging.outbox.retained.rows` | DB에 보관 중인 outbox 행 수 |
| `airbob.messaging.outbox.oldest.retained.age.seconds` | 가장 오래 보관된 행의 나이 |
| `airbob.messaging.outbox.health.refresh.last.success.epoch.seconds` | health 조회 마지막 성공 시각 |
| `airbob.messaging.outbox.health.refresh.failures` | health 조회 실패 누계 |

기본 health 주기는 30초이며 `messaging.outbox.health.fixed-delay`로 조정할 수 있다. 앞의 두
지표는 delivery backlog나 CDC lag가 아니라 **DB retention footprint**다. DB 용량과 보관
정책 경보에는 사용할 수 있지만 메시지 유실·미전송 경보로 사용해서는 안 된다.

읽기 전용 SQL도 같은 의미다.

```sql
SELECT
  COUNT(*) AS retained_rows,
  MIN(occurred_at) AS oldest_retained_at,
  TIMESTAMPDIFF(SECOND, MIN(occurred_at), UTC_TIMESTAMP(6))
    AS oldest_retained_age_seconds
FROM outbox;
```

## cleanup 안전 경계

Debezium Outbox EventRouter는 outbox `DELETE`를 비즈니스 이벤트로 route하지 않는다.
cleanup은 기존 행을 UPDATE하지 않고 `(occurred_at, id)` 순서의 오래된 행을 제한된
batch로 삭제한다. 단, 오래됐다는 사실은 발행 확인 증거가 아니다. cleanup은 기본적으로
꺼져 있으며 다음을 모두 확인한 운영자만 켠다.

1. connector와 모든 task가 `RUNNING`이다.
2. heartbeat와 Connect offset이 전진한다.
3. broker, 대상 topic, consumer lag가 정상이다.
4. snapshot이나 장애 복구가 진행 중이지 않다.
5. retention이 아래 시간보다 길다.

   `최대 Connect 장애 + 최대 복구/snapshot + 최악 CDC 지연 + 운영 안전 여유`

판단 근거가 없으면 cleanup을 활성화하지 않는다. 기본 30일도 모든 환경에 자동으로
안전하다는 뜻이 아니다.

```yaml
messaging:
  outbox:
    cleanup:
      enabled: true
      retention: 30d
      fixed-delay: 1h
      batch-size: 1000
```

- `enabled`: 명시적으로 `true`일 때만 cleanup bean과 scheduler 생성
- `retention`: 기본 30일, 최소 1일
- `fixed-delay`: 기본 1시간, 최소 1분
- `batch-size`: 기본 1,000, 허용 범위 1~10,000

한 tick은 최대 한 batch만 삭제한다. backlog를 빠르게 줄이겠다는 이유로 batch나 주기를
먼저 공격적으로 바꾸지 않는다. DB lock wait, replica/binlog 상태와 애플리케이션 지연을
관찰하면서 단계적으로 조정한다.

cleanup 자체 지표는 다음과 같다.

| 지표 | 의미 |
| --- | --- |
| `airbob.messaging.outbox.cleanup.last.success.epoch.seconds` | 마지막 성공 cleanup 시각 |
| `airbob.messaging.outbox.cleanup.last.deleted.count` | 마지막 tick 삭제 행 수 |
| `airbob.messaging.outbox.cleanup.failures` | cleanup 실패 누계 |

## 장애 대응

1. cleanup이 켜져 있다면 `messaging.outbox.cleanup.enabled=false`로 먼저 중단한다.
2. connector/task와 heartbeat freshness를 확인한다.
3. Connect offset, 대상 topic ingress, consumer lag를 비교한다.
4. binlog가 필요한 장애 복구 구간을 보존한다.
5. 유실 가능성이 있으면 payload를 출력하지 말고 제한된 절차로 DB와 Kafka의 `event_id`를
   대조한다.

삭제는 복구할 수 없다. connector 상태나 발행 여부를 검증할 수 없는 동안에는 retention이
지났더라도 기다리는 것이 기본 선택이다. EventRouter 동작 기준은
[Debezium 공식 문서](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)를
따른다.
