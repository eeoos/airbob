# 정산 상세의 지연 원장 반영 재현

백엔드 `29550b11`의 정산 서비스·저장소를 사용했다. 기존
`src/test/java/kr/kro/airbob/domain/settlement/SettlementServiceIntegrationTest.java`를
임시 `SettlementReadAuditProbeTest` 클래스로 복사하고 아래 메서드만 추가해 실행했다.
Testcontainers의 MySQL 이미지는 작업트리 기준 `mysql:8.4.11`, Redis는 `7.2-alpine`이다.
기존 원본 테스트는 수정하지 않았다. 정산 서비스·저장소도 변경하지 않았다.

fixture의 host1은 5월 결제 150,000원, 취소 20,000원으로 정산 net 130,000원이다.
R1은 이 호스트의 숙소 예약이다. 아래 추가 거래는 5월 31일에 PG가 취소했지만
6월 1일 DB에 뒤늦게 기록된 경우를 표현한다. 취소 자체가 다음 달에 발생한 경우와 다르다.

```java
@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
void lateCancellationChangesOnlyDetailLines(boolean paid) {
    settlementService.generateMonth(MAY);
    long id = settlementId(host1);
    if (paid) {
        settlementService.markPaid(id);
    }
    Long reservationId = jdbc.queryForObject(
        "SELECT id FROM reservation WHERE reservation_code = 'R1'", Long.class);
    jdbc.update("""
        INSERT INTO payment_transaction
            (reservation_id, transaction_type, status, cancel_amount, canceled_at, created_at, updated_at)
        VALUES (?, 'PARTIAL_CANCEL', 'PARTIAL_CANCELED', 2000,
            '2026-05-31 23:59:00', '2026-06-01 00:01:00', NOW(6))
        """, reservationId);
    var detail = settlementService.getSettlementDetail(id, host1);
    long lineNet = detail.items().stream().mapToLong(SettlementResponse.LineItem::netAmount).sum();
    assertThat(detail.netAmount()).isEqualTo(130000L);
    assertThat(lineNet).isEqualTo(128000L);
    settlementService.generateMonth(MAY);
    var refreshed = settlementService.getSettlementDetail(id, host1);
    assertThat(refreshed.netAmount()).isEqualTo(paid ? 130000L : 128000L);
    System.out.printf("AUDIT paid=%s header=%d lines=%d afterRecomputeHeader=%d%n",
        paid, detail.netAmount(), lineNet, refreshed.netAmount());
}
```

실행:

```bash
./gradlew test --tests '*SettlementReadAuditProbeTest.lateCancellationChangesOnlyDetailLines'
```

실제 결과: `BUILD SUCCESSFUL in 21s`, JUnit tests=2, failures=0, errors=0, skipped=0.

```text
AUDIT paid=false header=130000 lines=128000 afterRecomputeHeader=128000
AUDIT paid=true header=130000 lines=128000 afterRecomputeHeader=130000
```

이 assertion은 결함이 존재함을 확인하는 진단이다. 올바른 서비스 계약으로 영구 테스트에
추가하지 않았다. 수정 시에는 정산 상세의 헤더와 lines가 동일한 집계 시점을 사용한다는
회귀 테스트로 대체해야 한다. 진단은 실행 후 제거했다.

결제 gateway나 실제 복구 worker를 실행한 것은 아니다. 원장에 해당 지연 거래가
존재하는 조건에서 실제 정산 생성·지급 처리·상세 조회·재집계의 결과를 확인했다.
정산의 지급 상태 변경은 일회성 Testcontainers DB 안에서만 수행했다.
