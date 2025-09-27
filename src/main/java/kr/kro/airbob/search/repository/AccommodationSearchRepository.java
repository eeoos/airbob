package kr.kro.airbob.search.repository;

import java.util.UUID;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import kr.kro.airbob.search.document.AccommodationDocument;

public interface AccommodationSearchRepository extends ElasticsearchRepository<AccommodationDocument, UUID> {

}
