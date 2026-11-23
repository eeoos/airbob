package kr.kro.airbob.core.domain.operatoralert;

import java.util.UUID;

public record OperatorAlertPublication(UUID alertUid, boolean appended) {
}
