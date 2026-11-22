package kr.kro.airbob.core.domain.image;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ImageStorageTransactionEvents {

	public record ImageUploaded(String imageUrl) {
	}

	public record ImageDeletionRequested(String imageUrl) {
	}
}
