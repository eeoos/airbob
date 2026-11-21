package kr.kro.airbob.domain.image.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ImageStorageTransactionEvents {

	public record ImageUploaded(String imageUrl) {
	}

	public record ImageDeletionRequested(String imageUrl) {
	}
}
