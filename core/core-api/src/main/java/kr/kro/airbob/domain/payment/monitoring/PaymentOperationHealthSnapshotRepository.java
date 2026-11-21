package kr.kro.airbob.domain.payment.monitoring;

import java.time.Duration;
import java.time.Instant;

public interface PaymentOperationHealthSnapshotRepository {

	PaymentOperationHealthSnapshot readSnapshot(Instant observedAt, Duration staleQueuedAfter);
}
