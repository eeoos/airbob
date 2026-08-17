package kr.kro.airbob.cursor.util;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorPayload;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.dto.ReviewCursorData;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CursorPageInfoCreator {

	private final CursorEncoder cursorEncoder;

	public <T> CursorResponse.PageInfo createPageInfo(
		List<T> content,
		boolean hasNext,
		Function<T, Long> idExtractor,
		Function<T, LocalDateTime> createdAtExtractor) {

		return createPageInfo(
			content,
			hasNext,
			lastEntity -> new CursorData(
				idExtractor.apply(lastEntity),
				createdAtExtractor.apply(lastEntity)
			)
		);
	}

	public <T> CursorResponse.PageInfo createPageInfo(
		List<T> content,
		boolean hasNext,
		Function<T, Long> idExtractor,
		Function<T, LocalDateTime> createdAtExtractor,
		Function<T, Integer> ratingExtractor) {

		return createPageInfo(
			content,
			hasNext,
			lastEntity -> new ReviewCursorData(
				idExtractor.apply(lastEntity),
				createdAtExtractor.apply(lastEntity),
				ratingExtractor.apply(lastEntity)
			)
		);
	}

	private <T> CursorResponse.PageInfo createPageInfo(
		List<T> content,
		boolean hasNext,
		Function<T, ? extends CursorPayload> cursorFactory
	) {
		if (content.isEmpty()) {
			return new CursorResponse.PageInfo(false, null, 0);
		}

		String nextCursor = hasNext
			? cursorEncoder.encode(cursorFactory.apply(content.getLast()))
			: null;

		return new CursorResponse.PageInfo(hasNext, nextCursor, content.size());
	}
}
