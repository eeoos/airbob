package kr.kro.airbob.domain.wishlist.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkVerification;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class WishlistDeleteBenchmarkFixtureService {

	private final JdbcTemplate jdbcTemplate;

	public WishlistDeleteBenchmarkFixtureService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Fixture createFixture(long ownerId, int datasetSize) {
		validateDatasetSize(datasetSize);

		List<Long> accommodationIds = new ArrayList<>(Math.max(datasetSize, 1));
		for (int index = 0; index < Math.max(datasetSize, 1); index++) {
			accommodationIds.add(insertAccommodation(ownerId, index));
		}

		Long targetRepresentativeId = datasetSize == 0
			? null
			: accommodationIds.get(datasetSize - 1);
		long targetWishlistId = insertWishlist(
			ownerId,
			"bulk-write-target",
			datasetSize,
			targetRepresentativeId
		);
		long controlAccommodationId = accommodationIds.getFirst();
		long controlWishlistId = insertWishlist(
			ownerId,
			"bulk-write-control",
			1,
			controlAccommodationId
		);

		List<Long> targetMembershipIds = new ArrayList<>(datasetSize);
		for (int index = 0; index < datasetSize; index++) {
			targetMembershipIds.add(insertMembership(
				targetWishlistId,
				accommodationIds.get(index),
				ownerId
			));
		}
		long controlMembershipId = insertMembership(
			controlWishlistId,
			controlAccommodationId,
			ownerId
		);

		WishlistRow controlBefore = findWishlist(controlWishlistId);
		return new Fixture(
			ownerId,
			datasetSize,
			targetWishlistId,
			controlWishlistId,
			targetRepresentativeId,
			controlAccommodationId,
			targetMembershipIds,
			controlMembershipId,
			accommodationIds,
			controlBefore.updatedAt(),
			controlBefore.updatedBy()
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public WishlistDeleteBenchmarkVerification verify(Fixture fixture) {
		Objects.requireNonNull(fixture, "fixture must not be null");

		WishlistRow target = findWishlistOrNull(fixture.targetWishlistId());
		WishlistRow control = findWishlistOrNull(fixture.controlWishlistId());
		long targetRowsAfter = countByWishlistId(fixture.targetWishlistId());
		long controlRowsAfter = countExactIds(
			FixtureTable.WISHLIST_ACCOMMODATION,
			List.of(fixture.controlMembershipId())
		);
		long accommodationsAfter = countExactIds(FixtureTable.ACCOMMODATION, fixture.accommodationIds());

		boolean targetWishlistDeleted = target != null && target.status() == WishlistStatus.DELETED;
		boolean targetMembershipsDeleted = targetRowsAfter == 0;
		boolean targetDenormalizedStatePreserved = target != null
			&& target.accommodationCount() == fixture.datasetSize()
			&& Objects.equals(target.representativeAccommodationId(), fixture.targetRepresentativeId())
			&& Objects.equals(target.updatedBy(), fixture.ownerId());
		boolean controlWishlistPreserved = control != null
			&& control.status() == WishlistStatus.ACTIVE
			&& control.accommodationCount() == 1
			&& Objects.equals(control.representativeAccommodationId(), fixture.controlAccommodationId())
			&& Objects.equals(control.updatedAt(), fixture.controlUpdatedAtBefore())
			&& Objects.equals(control.updatedBy(), fixture.controlUpdatedByBefore());
		boolean controlMembershipPreserved = controlRowsAfter == 1;
		boolean accommodationsPreserved = accommodationsAfter == fixture.accommodationIds().size();
		long verifiedRows = fixture.datasetSize() - targetRowsAfter;
		boolean succeeded = verifiedRows == fixture.datasetSize()
			&& targetWishlistDeleted
			&& targetMembershipsDeleted
			&& targetDenormalizedStatePreserved
			&& controlWishlistPreserved
			&& controlMembershipPreserved
			&& accommodationsPreserved;

		return new WishlistDeleteBenchmarkVerification(
			verifiedRows,
			succeeded,
			targetWishlistDeleted,
			targetMembershipsDeleted,
			targetDenormalizedStatePreserved,
			controlWishlistPreserved,
			controlMembershipPreserved,
			accommodationsPreserved
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cleanup(Fixture fixture) {
		if (fixture == null) {
			return;
		}

		List<Long> membershipIds = new ArrayList<>(fixture.targetMembershipIds());
		membershipIds.add(fixture.controlMembershipId());
		deleteExactIds(FixtureTable.WISHLIST_ACCOMMODATION, membershipIds);
		deleteExactIds(
			FixtureTable.WISHLIST,
			List.of(fixture.targetWishlistId(), fixture.controlWishlistId())
		);
		deleteExactIds(FixtureTable.ACCOMMODATION, fixture.accommodationIds());
	}

	private long insertAccommodation(long ownerId, int index) {
		String sql = """
			INSERT INTO accommodation (
				member_id, check_in_time, check_out_time, accommodation_uid, status,
				name, base_price, currency, created_at, updated_at, created_by, updated_by
			) VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(?), 'PUBLISHED', ?, 100000, 'KRW',
				NOW(6), NOW(6), ?, ?)
			""";
		return insertAndReturnKey(sql, statement -> {
			statement.setLong(1, ownerId);
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, "bulk-write-accommodation-" + index);
			statement.setLong(4, ownerId);
			statement.setLong(5, ownerId);
		});
	}

	private long insertWishlist(
		long ownerId,
		String name,
		int accommodationCount,
		Long representativeAccommodationId
	) {
		String sql = """
			INSERT INTO wishlist (
				name, member_id, status, accommodation_count, representative_accommodation_id,
				created_at, updated_at, created_by, updated_by
			) VALUES (?, ?, 'ACTIVE', ?, ?, NOW(6), NOW(6), ?, ?)
			""";
		return insertAndReturnKey(sql, statement -> {
			statement.setString(1, name);
			statement.setLong(2, ownerId);
			statement.setInt(3, accommodationCount);
			if (representativeAccommodationId == null) {
				statement.setNull(4, Types.BIGINT);
			} else {
				statement.setLong(4, representativeAccommodationId);
			}
			statement.setLong(5, ownerId);
			statement.setLong(6, ownerId);
		});
	}

	private long insertMembership(long wishlistId, long accommodationId, long ownerId) {
		String sql = """
			INSERT INTO wishlist_accommodation (
				wishlist_id, accommodation_id, memo, created_at, updated_at, created_by, updated_by
			) VALUES (?, ?, ?, NOW(6), NOW(6), ?, ?)
			""";
		return insertAndReturnKey(sql, statement -> {
			statement.setLong(1, wishlistId);
			statement.setLong(2, accommodationId);
			statement.setString(3, "bulk-write-fixture");
			statement.setLong(4, ownerId);
			statement.setLong(5, ownerId);
		});
	}

	private long insertAndReturnKey(String sql, StatementBinder binder) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			binder.bind(statement);
			return statement;
		}, keyHolder);

		Number key = keyHolder.getKey();
		if (key == null) {
			throw new IllegalStateException("벌크 쓰기 벤치마크 fixture 생성 키를 얻지 못했습니다.");
		}
		return key.longValue();
	}

	private WishlistRow findWishlist(long wishlistId) {
		WishlistRow row = findWishlistOrNull(wishlistId);
		if (row == null) {
			throw new IllegalStateException("벌크 쓰기 벤치마크 fixture 생성에 실패했습니다.");
		}
		return row;
	}

	private WishlistRow findWishlistOrNull(long wishlistId) {
		return jdbcTemplate.query("""
			SELECT status, accommodation_count, representative_accommodation_id, updated_at, updated_by
			FROM wishlist
			WHERE id = ?
			""", (resultSet, rowNumber) -> new WishlistRow(
			WishlistStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("accommodation_count"),
			resultSet.getObject("representative_accommodation_id", Long.class),
			resultSet.getObject("updated_at", LocalDateTime.class),
			resultSet.getObject("updated_by", Long.class)
		), wishlistId).stream().findFirst().orElse(null);
	}

	private long countByWishlistId(long wishlistId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?",
			Long.class,
			wishlistId
		);
		return Objects.requireNonNull(count);
	}

	private long countExactIds(FixtureTable table, List<Long> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		String sql = "SELECT COUNT(*) FROM " + table.tableName()
			+ " WHERE id IN (" + placeholders(ids.size()) + ")";
		Long count = jdbcTemplate.queryForObject(sql, Long.class, ids.toArray());
		return Objects.requireNonNull(count);
	}

	private void deleteExactIds(FixtureTable table, List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		String sql = "DELETE FROM " + table.tableName()
			+ " WHERE id IN (" + placeholders(ids.size()) + ")";
		jdbcTemplate.update(sql, ids.toArray());
	}

	private String placeholders(int size) {
		return String.join(", ", Collections.nCopies(size, "?"));
	}

	private void validateDatasetSize(int datasetSize) {
		if (datasetSize < 0 || datasetSize > WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE);
		}
	}

	private enum FixtureTable {
		WISHLIST_ACCOMMODATION("wishlist_accommodation"),
		WISHLIST("wishlist"),
		ACCOMMODATION("accommodation");

		private final String tableName;

		FixtureTable(String tableName) {
			this.tableName = tableName;
		}

		String tableName() {
			return tableName;
		}
	}

	@FunctionalInterface
	private interface StatementBinder {
		void bind(PreparedStatement statement) throws java.sql.SQLException;
	}

	private record WishlistRow(
		WishlistStatus status,
		int accommodationCount,
		Long representativeAccommodationId,
		LocalDateTime updatedAt,
		Long updatedBy
	) {
	}

	public record Fixture(
		long ownerId,
		int datasetSize,
		long targetWishlistId,
		long controlWishlistId,
		Long targetRepresentativeId,
		long controlAccommodationId,
		List<Long> targetMembershipIds,
		long controlMembershipId,
		List<Long> accommodationIds,
		LocalDateTime controlUpdatedAtBefore,
		Long controlUpdatedByBefore
	) {

		public Fixture {
			targetMembershipIds = List.copyOf(targetMembershipIds);
			accommodationIds = List.copyOf(accommodationIds);
		}
	}

}
