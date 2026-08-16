package kr.kro.airbob.kafka.consumer;

class AccommodationIndexingEventParsingException extends RuntimeException {

	AccommodationIndexingEventParsingException() {
		super("Invalid accommodation-indexing event.");
	}
}
