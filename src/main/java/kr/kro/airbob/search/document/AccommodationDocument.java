package kr.kro.airbob.search.document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Builder;

@Document(indexName = "accommodations")
@Setting(settingPath = "/elastic/es-settings.json")
@Builder
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
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

	@Field(type = FieldType.Long)
	Long basePrice,

	@Field(type = FieldType.Keyword)
	String currency,

	@Field(type = FieldType.Keyword)
	String type,

	@Field(type = FieldType.Keyword)
	String status,

	@Field(type = FieldType.Date, format = {DateFormat.date_time})
	Instant createdAt,

	// 위치 정보
	@GeoPointField
	Location location,

	@Field(type = FieldType.Keyword)
	String country,

	@MultiField(
		mainField = @Field(type = FieldType.Keyword),
		otherFields = {
			@InnerField(suffix = "lower", type = FieldType.Keyword, normalizer = "lowercase_normalizer")
		}
	)
	String state,

	@MultiField(
		mainField = @Field(type = FieldType.Text, analyzer = "nori"),
		otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
			@InnerField(suffix = "keyword", type = FieldType.Keyword),
			@InnerField(suffix = "lower", type = FieldType.Keyword, normalizer = "lowercase_normalizer")
		}
	)
	String city,

	@MultiField(
		mainField = @Field(type = FieldType.Text, analyzer = "nori"),
		otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard")
		}
	)
	String district,

	@MultiField(
		mainField = @Field(type = FieldType.Text, analyzer = "nori"),
		otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard")
		}
	)
	String street,

	/*@Field(type = FieldType.Keyword)
	String addressDetail,*/

	@Field(type = FieldType.Keyword)
	String postalCode,

	// 인원 정책
	@Field(type = FieldType.Integer)
	Integer maxGuests,

	@Field(type = FieldType.Integer)
	Integer maxInfants,

	@Field(type = FieldType.Integer)
	Integer maxPets,

	// 편의시설
	@Field(type = FieldType.Keyword)
	List<String> amenityTypes,

	// 숙소 썸네일
	@Field(type = FieldType.Keyword)
	String thumbnailUrl,

	// 예약
	@Field(type = FieldType.Date_Range)
	List<DateRange> reservationRanges,

	// 리뷰
	@Field(type= FieldType.Double)
	Double averageRating,

	@Field(type = FieldType.Long)
	Integer reviewCount

	// 호스트
	/*@Field(type = FieldType.Long)
	Long hostId,

	@Field(type = FieldType.Keyword)
	String hostNickname*/
) {

	@Builder
	public record Location(
		@Field(type = FieldType.Double)
		Double lat,

		@Field(type = FieldType.Double)
		Double lon
	){}

	@Builder
	public record DateRange(
		@Field(type = FieldType.Date)
		LocalDate gte,

		@Field(type = FieldType.Date)
		LocalDate lt
	) {}
}


