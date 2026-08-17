package kr.kro.airbob.kafka.consumer;

class AccommodationDetailCacheInvalidationEventParsingException extends RuntimeException {

	AccommodationDetailCacheInvalidationEventParsingException() {
		super("Invalid accommodation-detail cache invalidation event.");
	}

	AccommodationDetailCacheInvalidationEventParsingException(Throwable cause) {
		super("Invalid accommodation-detail cache invalidation event.", cause);
	}
}
