package kr.kro.airbob.messaging.alert.application;

import java.util.UUID;

public record OperatorAlertPublication(UUID alertUid, boolean appended) {
}
