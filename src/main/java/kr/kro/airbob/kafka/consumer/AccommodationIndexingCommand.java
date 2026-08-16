package kr.kro.airbob.kafka.consumer;

import java.util.UUID;

import kr.kro.airbob.outbox.EventType;

record AccommodationIndexingCommand(EventType eventType, UUID accommodationUid) {
}
