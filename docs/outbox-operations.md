# Transactional Outbox 보관 및 정리 운영 가이드

## 기본 원칙

`outbox` 행은 비즈니스 트랜잭션과 같은 트랜잭션에서 추가된다. Debezium은 MySQL binlog의 `INSERT`를 읽어 Kafka로 전달하지만, 애플리케이션 데이터베이스에는 개별 행의 Kafka 발행 완료 여부가 기록되지 않는다.

따라서 이 cleanup은 **발행 확인(ack) 기반이 아니라 생성 시각 기반**이다. 오래되었다는 이유만으로 발행이 확인된 것은 아니다. 안전을 위해 cleanup은 기본적으로 꺼져 있으며, 아래 점검을 완료한 운영자만 활성화해야 한다.

Debezium Outbox Event Router는 outbox의 `DELETE`를 이벤트로 변환하지 않고 필터링한다. cleanup은 기존 행을 갱신하지 않으며, `(occurred_at, id)` 인덱스 순서로 제한된 수의 행만 삭제한다.

동작 기준은 [Debezium Outbox Event Router 공식 문서](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)를 따른다.

## 활성화 전 필수 점검

1. Kafka Connect REST API에서 connector 상태와 모든 task 상태가 `RUNNING`인지 확인한다.
2. connector offset이 계속 전진하는지, MySQL binlog 위치와의 지연이 안정적으로 감소하거나 정상 범위인지 확인한다.
3. Kafka broker와 대상 topic이 정상이고 consumer 지연이 운영 허용 범위인지 확인한다.
4. snapshot 또는 장애 복구가 진행 중이지 않은지 확인한다.
5. 보관 기간을 다음 합보다 길게 잡는다.

   `최대 예상 Connect 장애 시간 + 최대 복구/snapshot 시간 + 최악의 CDC 지연 + 운영 안전 여유`

위 시간을 신뢰할 근거가 없으면 cleanup을 활성화하지 않는다. 기본 보관 기간 30일은 모든 환경에 자동으로 안전하다는 뜻이 아니다.

## 설정

cleanup은 다음 프로퍼티를 명시해야만 실행된다.

```yaml
messaging:
  outbox:
    cleanup:
      enabled: true
      retention: 30d
      fixed-delay: 1h
      batch-size: 1000
```

- `enabled`: 기본값 `false`. `true`일 때만 cleanup scheduler와 관련 bean을 만든다.
- `retention`: 기본값 30일, 최소 1일. 활성화 전 필수 점검에서 계산한 안전 기간보다 길어야 한다.
- `fixed-delay`: 기본값 1시간, 최소 1분.
- `batch-size`: 기본값 1,000, 허용 범위 1~10,000.

한 tick의 삭제 트랜잭션에는 최대 한 배치의 `DELETE` 문 하나만 포함한다. backlog 개수와 가장 오래된 시각 조회는 삭제 트랜잭션이 끝난 다음 실행한다. 대량 backlog를 빠르게 지우려고 배치 크기나 주기를 먼저 공격적으로 변경하지 말고, DB lock wait·replication lag·애플리케이션 지연을 관찰하면서 단계적으로 조정한다.

## 관측 지표

활성화된 cleanup은 메시지 본문, aggregate ID, partition key를 label이나 로그에 포함하지 않고 다음 Micrometer 지표만 제공한다.

| 지표 | 의미 |
| --- | --- |
| `airbob.messaging.outbox.backlog.count` | 마지막 성공 tick 직후 남은 outbox 행 수 |
| `airbob.messaging.outbox.oldest.age.seconds` | 마지막 성공 tick에서 관측한 가장 오래된 행의 나이 |
| `airbob.messaging.outbox.cleanup.last.success.epoch.seconds` | 마지막 성공 tick의 Unix epoch 초 |
| `airbob.messaging.outbox.cleanup.last.deleted.count` | 마지막 성공 tick에서 삭제한 행 수 |
| `airbob.messaging.outbox.cleanup.failures` | cleanup 실패 누적 횟수 |

지표는 cleanup이 성공할 때 snapshot으로 갱신된다. `last.success`가 멈추거나 failure가 증가하면 cleanup을 비활성화하고 DB 상태를 먼저 점검한다. backlog 증가만으로 삭제를 강행해서는 안 된다. connector/task 상태와 CDC 지연을 함께 확인해야 한다.

## 장애 시 대응

1. `messaging.outbox.cleanup.enabled=false`로 cleanup을 중단한다.
2. Kafka Connect connector와 task 상태, connector 로그, MySQL binlog 보존 상태를 확인한다.
3. connector offset과 실제 topic 도착 이벤트를 비교해 CDC가 따라잡았는지 검증한다.
4. 보관 기간 내 미발행 가능성이 남아 있으면 cleanup을 재활성화하지 않는다.
5. 발행 유실 가능성이 있으면 payload를 직접 로그로 출력하지 말고, 제한된 운영 절차로 DB와 Kafka의 `event_id`를 대조한다.

삭제는 복구할 수 없는 운영 작업이다. connector가 지연 중이거나 상태를 확인할 수 없는 상황에서는 보관 기간이 지났더라도 기다리는 것이 기본 선택이다.
