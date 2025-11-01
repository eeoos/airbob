package kr.kro.airbob.search.document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

import lombok.Builder;

@Document(indexName = "accommodations")
@Builder
public record AccommodationDocument(

	@Id
	String id, // ES Document ID (accommodationUid)

	Long accommodationId,

	// accommodation
	@MultiField(
		mainField = @Field(type = FieldType.Text, analyzer = "nori"),
		otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard")
		}
	)
	String name,

	@MultiField(
		mainField = @Field(type = FieldType.Text, analyzer = "nori"),
		otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard")
		}
	)
	String description,

	@Field(type = FieldType.Integer)
	Integer basePrice,

	@Field(type = FieldType.Keyword)
	String type,

	@Field(type = FieldType.Keyword)
	String status,

	@Field(type = FieldType.Date)
	LocalDateTime createdAt,

	// 위치 정보
	@GeoPointField
	Location location,

	@Field(type = FieldType.Keyword)
	String country,

	@Field(type = FieldType.Keyword)
	String city,

	@Field(type = FieldType.Keyword)
	String district,

	@Field(type = FieldType.Text)
	String street,

	@Field(type = FieldType.Text)
	String addressDetail,

	@Field(type = FieldType.Keyword)
	String postalCode,

	// 인원 정책
	@Field(type = FieldType.Integer)
	Integer maxOccupancy,

	@Field(type = FieldType.Integer)
	Integer adultOccupancy,

	@Field(type = FieldType.Integer)
	Integer childOccupancy,

	@Field(type = FieldType.Integer)
	Integer infantOccupancy,

	@Field(type = FieldType.Integer)
	Integer petOccupancy,

	// 편의시설
	@Field(type = FieldType.Keyword)
	List<String> amenityTypes,

	// 숙소 썸네일
	@Field(type = FieldType.Keyword)
	String thumbnailUrl,

	// 예약
	@Field(type = FieldType.Date)
	List<LocalDate> reservedDates,

	// 리뷰
	@Field(type= FieldType.Double)
	Double averageRating,

	@Field(type = FieldType.Integer)
	Integer reviewCount,

	// 호스트
	@Field(type = FieldType.Long)
	Long hostId,

	@Field(type = FieldType.Keyword)
	String hostNickname
) {

	@Builder
	public record Location(
		@Field(type = FieldType.Double)
		Double lat,

		@Field(type = FieldType.Double)
		Double lon
	){}
}


