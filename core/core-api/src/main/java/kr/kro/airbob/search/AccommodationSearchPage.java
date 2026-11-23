package kr.kro.airbob.search;

import java.util.List;

import org.springframework.data.domain.Pageable;

/** 숙소 검색 한 페이지. 다음 페이지는 MAX_PAGE_NUMBER(0부터 센 14번째)까지만 있다고 본다. */
public record AccommodationSearchPage(
	List<AccommodationSearchItem> items,
	int pageSize,
	int currentPage,
	int totalPages,
	long totalElements,
	boolean hasNext
) {

	public static final int MAX_PAGE_NUMBER = 14;

	public AccommodationSearchPage {
		items = List.copyOf(items);
	}

	/** 검색을 실행하지 않았거나 결과가 없을 때. 요청한 페이지 번호와 크기는 유지한다. */
	public static AccommodationSearchPage empty(Pageable pageable) {
		return of(List.of(), pageable, 0);
	}

	public static AccommodationSearchPage of(List<AccommodationSearchItem> items, Pageable pageable, long total) {
		int size = pageable.getPageSize();
		int page = pageable.getPageNumber();
		int pages = (int)Math.ceil((double)total / size);
		boolean hasNext = page < Math.min(pages - 1, MAX_PAGE_NUMBER);
		return new AccommodationSearchPage(items, size, page, pages, total, hasNext);
	}
}
