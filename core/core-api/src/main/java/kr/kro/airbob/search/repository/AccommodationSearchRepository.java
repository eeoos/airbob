package kr.kro.airbob.search.repository;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import kr.kro.airbob.search.document.AccommodationDocument;

public interface AccommodationSearchRepository extends ElasticsearchRepository<AccommodationDocument, String> {

}
