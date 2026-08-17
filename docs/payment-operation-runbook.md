# Payment-operation 운영 런북

이 문서는 비동기 결제 승인과 결제 취소를 모두 다룬다. MySQL의 `payment_operation`이
작업 상태의 기준이며 Kafka 레코드는 실행을 깨우는 신호다. Kafka 중복 전달은 정상이고,
작업 UID와 dispatch generation 검증이 중복 provider 호출을 막는다.

## 상태와 실행 규칙

| 상태 | 의미 | 운영 원칙 |
| --- | --- | --- |
| `QUEUED` | 현재 generation의 실행 이벤트가 outbox/Kafka 경로에 있음 | 오래 머물면 DLT, CDC, consumer 순으로 확인한다. |
| `EXECUTING` | worker가 lease를 획득해 provider 호출 중 | lease 만료 전에는 재실행하지 않는다. |
| `WAITING_RETRY` | 다음 재시도 시각까지 대기 | recovery scheduler가 새 generation을 outbox에 기록한다. |
| `APPLIED` | 승인 또는 취소 결과가 로컬 원장과 예약에 반영됨 | 최종 상태다. |
| `DECLINED` | 명시적 최종 거절 또는 검증된 미결제로 종료됨 | 최종 상태다. |
| `MANUAL_REVIEW` | 자동 판정이 중지되고 운영자 확인이 필요함 | DB를 직접 수정하지 않고 관리자 API만 사용한다. |

`next_action`은 `CONFIRM`, `CANCEL`, `INQUIRE_CONFIRM`, `INQUIRE_CANCEL` 중 하나다.
응답 유실 또는 lease 만료 뒤에는 먼저 조회(inquiry)한다. 관리자 reconciliation도 언제나
조회만 수행하며 승인이나 취소 명령을 다시 보내지 않는다.

## 배포 전 조건

broker 자동 topic 생성을 끈 상태에서 다음 topic을 미리 만든다. 세 topic의 partition 수는
같아야 한다.

- `PAYMENT_OPERATION.events`
- `PAYMENT_OPERATION.events.RETRY`
- `PAYMENT_OPERATION.events.DLT`

consumer group은 `payment-operation-execution-group`이고 record key는 reservation UID다.
Debezium의 `PAYMENT_OPERATION` aggregate route가 `PAYMENT_OPERATION.events`로 향하는지
확인한다. 배포 전에 다음 조건도 확인한다.

1. Flyway V18, V19, V20이 모두 적용되었다.
2. Kafka Connect의 `airbob-outbox-connector`와 모든 task가 `RUNNING`이다.
3. `__debezium-heartbeat.airbob_outbox`의 최신 timestamp/offset이 10초 heartbeat 주기에
   맞게 전진한다.
4. provider connect/read timeout이 `payment.operation.lease-duration`보다 짧다.
5. recovery scheduler와 두 health refresh가 적어도 한 인스턴스에서 실행된다.

## 항상 노출되는 지표

결제와 outbox health refresh 기본 주기는 30초다. `last.success`가 오래되면 나머지 gauge도
오래된 값이므로 먼저 freshness를 확인한다. refresh 실패는 직전 성공 snapshot을 지우지
않는다. 어떤 지표도 operation UID, reservation UID, payment key, payload, 오류 메시지를
tag로 사용하지 않는다.

### 결제 작업

| Micrometer 지표 | 의미 |
| --- | --- |
| `airbob.payment.operation.manual.review.count` | `MANUAL_REVIEW` 작업 수 |
| `airbob.payment.operation.manual.review.oldest.age.seconds` | 가장 오래된 수동 검토 작업의 나이 |
| `airbob.payment.operation.reconciliation.pending.count` | 관리자 조회가 진행 중인 작업 수 |
| `airbob.payment.operation.reconciliation.pending.oldest.age.seconds` | 가장 오래된 관리자 조회 요청의 나이 |
| `airbob.payment.operation.queued.stale.count` | stale 기준을 넘긴 `QUEUED` 작업 수 |
| `airbob.payment.operation.queued.stale.oldest.age.seconds` | 가장 오래된 stale `QUEUED` 작업의 나이 |
| `airbob.payment.operation.executing.lease.expired.count` | 현재 시각과 같거나 이전에 lease가 끝난 `EXECUTING` 작업 수 |
| `airbob.payment.operation.health.refresh.last.success.epoch.seconds` | 결제 health snapshot 마지막 성공 시각 |
| `airbob.payment.operation.health.refresh.failures` | 결제 health refresh 실패 누계 |
| `airbob.payment.operation.recovery.scheduler.last.success.epoch.seconds` | `recoverDue`가 정상 반환한 마지막 시각 |
| `airbob.payment.operation.recovery.scheduler.failures` | recovery tick 실패 누계 |
| `airbob.payment.operation.resolution.count{action=...}` | append-only resolution audit의 누적 수 |

resolution 지표의 유일한 tag는 코드로 닫힌 `action`이며 값은
`RECONCILIATION_REQUESTED`, `RECONCILIATION_APPLIED`, `RECONCILIATION_DECLINED`,
`RECONCILIATION_RETURNED_TO_REVIEW`, `MARKED_NOT_PAID`뿐이다.

기본 stale 기준은 30초다. 필요하면
`payment.operation.monitoring.stale-queued-after`와
`payment.operation.monitoring.fixed-delay`를 조정한다. 기준은 평상시 처리 지연을 측정한
뒤 변경하며, 단순히 경보를 없애기 위해 늘리지 않는다.

### outbox 보관 footprint

다음 지표는 cleanup 설정과 무관하게 항상 갱신된다.

| Micrometer 지표 | 의미 |
| --- | --- |
| `airbob.messaging.outbox.retained.rows` | DB에 보관 중인 outbox 행 수 |
| `airbob.messaging.outbox.oldest.retained.age.seconds` | 가장 오래 보관된 행의 나이 |
| `airbob.messaging.outbox.health.refresh.last.success.epoch.seconds` | outbox health 조회 마지막 성공 시각 |
| `airbob.messaging.outbox.health.refresh.failures` | outbox health 조회 실패 누계 |

기본 refresh 주기는 30초이며 `messaging.outbox.health.fixed-delay`로 조정할 수 있다.
cleanup scheduler의 활성화 여부와 이 health refresh의 실행 여부는 서로 독립적이다.

중요: outbox에는 Kafka 전달 완료/ack column이 없다. 따라서 행 수와 가장 오래된 행의
나이는 **보관 용량 지표**일 뿐, 미전송 backlog나 CDC lag가 아니다. cleanup이 꺼져 있으면
oldest age가 계속 증가하는 것이 정상이다. 전달 건강도는 Connect/task 상태, heartbeat
freshness, 대상 topic 도착, consumer lag를 함께 사용해 판단한다.

## 읽기 전용 점검

운영 DB에서는 읽기 전용 계정만 사용한다. provider payload, payment key,
`failure_message`를 SELECT하거나 티켓·채팅·로그로 복사하지 않는다.

### 결제 작업 집계

```sql
SELECT status, COUNT(*) AS operation_count
FROM payment_operation
GROUP BY status
ORDER BY status;
```

```sql
SELECT
  COUNT(CASE WHEN status = 'MANUAL_REVIEW' THEN 1 END) AS manual_review_count,
  MIN(CASE WHEN status = 'MANUAL_REVIEW' THEN review_required_at END)
    AS oldest_manual_review_at,
  COUNT(CASE WHEN manual_reconciliation_pending = true THEN 1 END)
    AS reconciliation_pending_count,
  COUNT(CASE WHEN status = 'QUEUED'
              AND queued_at <= UTC_TIMESTAMP(6) - INTERVAL 30 SECOND THEN 1 END)
    AS stale_queued_count,
  MIN(CASE WHEN status = 'QUEUED'
            AND queued_at <= UTC_TIMESTAMP(6) - INTERVAL 30 SECOND THEN queued_at END)
    AS oldest_stale_queued_at,
  COUNT(CASE WHEN status = 'EXECUTING'
              AND lease_expires_at <= UTC_TIMESTAMP(6) THEN 1 END)
    AS expired_lease_count
FROM payment_operation;
```

개별 수동 검토 작업은 SQL 대신 다음 관리자 endpoint로 조회한다. 응답에는 안전한 상태
필드와 가능한 action만 들어간다.

```text
GET /api/v1/admin/payment-operations/manual-review?limit=50
```

### outbox 보관 상태

```sql
SELECT
  COUNT(*) AS retained_rows,
  MIN(occurred_at) AS oldest_retained_at,
  TIMESTAMPDIFF(SECOND, MIN(occurred_at), UTC_TIMESTAMP(6))
    AS oldest_retained_age_seconds
FROM outbox;
```

이 SQL 결과만으로 전달 성공/실패를 판정하지 않는다. cleanup을 활성화하기 전에는 별도
outbox 보관 정책과 CDC 복구 가능 시간을 확인한다.

### Debezium/Kafka 전달 상태

```bash
curl --fail --silent --show-error \
  http://<connect-host>:8083/connectors/airbob-outbox-connector/status
```

connector와 모든 task가 `RUNNING`이어야 한다. 이어서 다음을 확인한다.

1. `__debezium-heartbeat.airbob_outbox`의 최신 record timestamp와 offset이 전진한다.
2. `PAYMENT_OPERATION.events`의 최신 입력과 consumer-group lag가 정상 범위다.
3. `PAYMENT_OPERATION.events.RETRY`와 `.DLT` 유입률이 증가하지 않는다.
4. Connect offset이 멈췄다면 cleanup을 켜거나 retention을 줄이지 않는다.

## DLT 처리

main/retry 시도가 소진되면 DLT handler가 한 트랜잭션에서 다음을 수행한다.

1. payload가 canonical V1이면 operation UID, reservation UID, dispatch generation을 검증한다.
2. DB 작업이 여전히 같은 generation의 `QUEUED`일 때만 generation을 정확히 한 번 올리고
   새 실행 이벤트를 outbox에 기록한다.
3. 원본 topic/partition/offset을 키로 중복 제거되는 운영 alert를 outbox에 기록한다.
4. 위 트랜잭션이 커밋된 뒤에만 DLT record를 ACK한다.

stale generation, 이미 진행되거나 종료된 작업, reservation UID 불일치는 재발행하지 않는다.
poison payload는 operation을 재발행하지 않고 좌표 기반 alert만 남긴다. 동일 DLT record를
다시 처리해도 새 generation이 반복 생성되지 않는다. 운영자가 DLT payload나 header를
그대로 main topic에 복사해서는 안 된다.

## 수동 검토 해결

모든 endpoint는 `/api/v1/admin/**` 인가와 `expected_version` 낙관적 락을 요구한다.

1. `GET /api/v1/admin/payment-operations/manual-review?limit=50`으로 오래된 작업부터 본다.
2. provider 상태를 승인된 console/incident 절차로 확인한다. 민감 응답을 증거 문자열에
   넣지 않는다.
3. 상태를 다시 조회해야 하면 아래 endpoint를 호출한다.

   ```text
   POST /api/v1/admin/payment-operations/{operationId}/reconciliation
   {"expected_version": <version>}
   ```

   이 요청은 `INQUIRE_CONFIRM` 또는 `INQUIRE_CANCEL`만 queue한다. 승인/취소를 직접
   재호출하지 않는다.
4. 조회 결과가 확정되면 정상 finalizer가 `APPLIED` 또는 `DECLINED`로 끝낸다. 여전히
   모호하면 audit와 alert를 남기고 `MANUAL_REVIEW`로 돌아온다.
5. CONFIRM 조회가 provider `NotFound`를 확인한 경우에만 목록에 `MARK_NOT_PAID`가
   나타난다. 이때 닫힌 reason code와 256자 이하 내부 증거 reference로 다음 endpoint를
   호출할 수 있다.

   ```text
   POST /api/v1/admin/payment-operations/{operationId}/mark-not-paid
   {
     "expected_version": <version>,
     "reason_code": "PROVIDER_PAYMENT_NOT_FOUND",
     "evidence_reference": "incident/ABC-123"
   }
   ```

`mark-paid` endpoint는 없다. CANCEL 작업에는 `MARK_NOT_PAID`를 사용할 수 없다. provider에
결제가 여전히 활성 상태인 CANCEL reconciliation은 다시 `MANUAL_REVIEW`가 되며, 승인된
provider 운영 절차 이후 다시 조회한다. operation, reservation, payment, ledger, coupon,
resolution audit를 SQL로 직접 변경하지 않는다.

## 초기 경보 기준

아래 값은 첫 운영 기준이며 실제 p95 지연과 호출량을 관찰한 뒤 조정한다. counter 경보는
절대값이 아니라 증가량으로 만든다. 배포 직후 gauge가 아직 0인 초기 구간은 제외한다.

| 조건 | 초기 대응 기준 |
| --- | --- |
| recovery `last.success` | 현재 시각보다 30초 이상 오래되면 경고 |
| payment/outbox health `last.success` | 현재 시각보다 120초 이상 오래되면 경고 |
| recovery 또는 health failure counter | 5분 증가량이 1 이상이면 경고 |
| stale `QUEUED` | 2회 연속 count > 0이면 조사, oldest age > 60초면 긴급 대응 |
| expired `EXECUTING` lease | 2회 recovery 주기 동안 count > 0이면 조사 |
| `MANUAL_REVIEW` | 새 진입은 operator alert, oldest age 15분 경고/30분 긴급 대응 |
| reconciliation pending | oldest age 5분 경고/15분 긴급 대응 |
| payment DLT | 새 record 또는 quarantine alert가 하나라도 생기면 즉시 조사 |
| Connect/task | `RUNNING`이 아니면 즉시 긴급 대응 |
| Debezium heartbeat | 기본 10초 주기의 3배인 30초 이상 전진하지 않으면 조사 |
| Kafka consumer lag | 서비스의 정상 p95를 넘는 증가가 2회 이상 지속되면 조사 |
| outbox retained rows/age | delivery 경보로 사용하지 않고 DB 용량·보관 정책 임계치만 적용 |

## 장애 대응 순서

### stale `QUEUED` 또는 recovery freshness 저하

1. recovery/health failure 증가와 DB 연결 상태를 본다.
2. 해당 이벤트가 DLT에 갔다면 좌표 기반 alert와 단일 generation 재발행 여부를 확인한다.
3. Connect task, heartbeat, payment topic 도착, consumer lag 순으로 좁힌다.
4. DB 상태나 generation을 직접 수정하지 않는다.

### lease 만료 또는 provider 응답 유실

1. `lease_expires_at <= UTC_TIMESTAMP(6)`인지 확인한다.
2. recovery가 새 generation을 만들고 inquiry action으로 전환하는지 본다.
3. inquiry 전 승인/취소 명령이 다시 나가지 않는지 확인한다.
4. 계속 모호하면 `MANUAL_REVIEW`와 관리자 reconciliation으로 넘긴다.

### Connect/outbox 이상

1. cleanup을 활성화하거나 retention을 줄이지 않는다.
2. connector/task와 heartbeat를 확인한다.
3. Connect offset, 대상 topic 입력, consumer lag를 비교한다.
4. `retained.rows` 증가는 delivery 실패의 증거가 아님을 유지한다.
5. 유실 가능성이 있으면 payload를 출력하지 말고 제한된 절차로 `event_id`를 대조한다.

## 롤백 경계

첫 새 결제 작업을 받기 전에는 `payment_operation`, payment-operation outbox 이벤트,
main/retry/DLT topic에 처리할 명령이 없음을 확인한 뒤 이전 binary로 돌아갈 수 있다.

첫 작업을 받은 뒤에는 승인과 취소가 모두 새 orchestration 상태·generation·audit schema에
의존한다. 이전 choreography binary로 자동 롤백하면 작업을 끝내지 못하거나 provider
명령을 중복 실행할 수 있으므로 안전하지 않다. 이 시점의 절차는 다음과 같다.

1. 새 승인/취소 요청 유입을 중단한다.
2. 현재 schema와 event contract를 이해하는 consumer, recovery scheduler, Debezium을 유지한다.
3. `QUEUED`, `EXECUTING`, `WAITING_RETRY`를 모두 소진하고 `MANUAL_REVIEW`를 명시적으로
   해결한다.
4. outbox retention footprint가 아니라 Connect/heartbeat/topic/consumer 상태로 전달
   완료 가능성을 검증한다.
5. 문제가 있으면 pre-orchestration binary가 아니라 동일 contract와 V18~V20을 이해하는
   수정 binary로 roll-forward한다.

operation 또는 연결된 payment ledger/audit가 남아 있는 동안 V18~V20을 되돌리지 않는다.
Kafka topic, Connect offset/schema history, heartbeat topic도 기존 binary 롤백과 함께
삭제하지 않는다.
