package kr.kro.airbob.core.domain.search.repository;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import kr.kro.airbob.core.domain.search.AccommodationDocument;

public interface AccommodationSearchRepository extends ElasticsearchRepository<AccommodationDocument, String> {

}
