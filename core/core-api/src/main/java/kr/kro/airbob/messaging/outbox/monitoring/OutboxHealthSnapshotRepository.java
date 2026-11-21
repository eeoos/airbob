package kr.kro.airbob.messaging.outbox.monitoring;

import java.time.Instant;

public interface OutboxHealthSnapshotRepository {

	OutboxHealthSnapshot readSnapshot(Instant observedAt);
}
