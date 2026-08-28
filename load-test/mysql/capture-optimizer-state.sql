-- Deterministic read-model optimizer snapshot. Run with:
--   mysql --batch --raw --skip-column-names \
--     --init-command="SET @airbob_optimizer_snapshot_id='...'" \
--     airbobdb < load-test/mysql/capture-optimizer-state.sql
--
-- The histogram allowlist excludes member identity, free text, payment keys,
-- network coordinates, and every credential-bearing column. Each output line
-- is one JSON record. Capture the bytes and their SHA-256 before measurement.

SET SESSION time_zone = '+00:00';

SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'recordType', 'server',
  'snapshotId', CAST(@airbob_optimizer_snapshot_id AS CHAR),
  'mysqlVersion', VERSION(),
  'versionComment', @@version_comment,
  'optimizerSwitch', @@SESSION.optimizer_switch,
  'sqlMode', @@SESSION.sql_mode,
  'transactionIsolation', @@SESSION.transaction_isolation,
  'sessionTimeZone', @@SESSION.time_zone,
  'innodbStatsPersistent', CAST(@@GLOBAL.innodb_stats_persistent AS CHAR),
  'innodbStatsAutoRecalc', CAST(@@GLOBAL.innodb_stats_auto_recalc AS CHAR),
  'innodbStatsPersistentSamplePages', CAST(@@GLOBAL.innodb_stats_persistent_sample_pages AS CHAR),
  'eqRangeIndexDiveLimit', CAST(@@SESSION.eq_range_index_dive_limit AS CHAR),
  'rangeOptimizerMaxMemSize', CAST(@@SESSION.range_optimizer_max_mem_size AS CHAR)
)
WHERE COALESCE(@airbob_optimizer_snapshot_id, '')
  REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$';

SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'recordType', 'table-stat',
  'snapshotId', CAST(@airbob_optimizer_snapshot_id AS CHAR),
  'databaseName', DATABASE_NAME,
  'tableName', TABLE_NAME,
  'lastUpdate', DATE_FORMAT(LAST_UPDATE, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'rowCount', CAST(N_ROWS AS CHAR),
  'clusteredIndexBytes', CAST(CLUST_INDEX_SIZE * @@GLOBAL.innodb_page_size AS CHAR),
  'secondaryIndexBytes', CAST(SUM_OF_OTHER_INDEX_SIZES * @@GLOBAL.innodb_page_size AS CHAR)
)
FROM mysql.innodb_table_stats
WHERE DATABASE_NAME = 'airbobdb'
  AND TABLE_NAME IN (
    'review',
    'accommodation_review_summary',
    'wishlist',
    'wishlist_accommodation',
    'accommodation_image',
    'daily_revenue_stats',
    'payment_transaction',
    'reservation'
  )
  AND COALESCE(@airbob_optimizer_snapshot_id, '')
    REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
ORDER BY TABLE_NAME;

SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'recordType', 'index-stat',
  'snapshotId', CAST(@airbob_optimizer_snapshot_id AS CHAR),
  'databaseName', DATABASE_NAME,
  'tableName', TABLE_NAME,
  'indexName', INDEX_NAME,
  'lastUpdate', DATE_FORMAT(LAST_UPDATE, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'statName', STAT_NAME,
  'statValue', CAST(STAT_VALUE AS CHAR),
  'sampleSize', IF(SAMPLE_SIZE IS NULL, NULL, CAST(SAMPLE_SIZE AS CHAR)),
  'statDescription', STAT_DESCRIPTION
)
FROM mysql.innodb_index_stats
WHERE DATABASE_NAME = 'airbobdb'
  AND TABLE_NAME IN (
    'review',
    'accommodation_review_summary',
    'wishlist',
    'wishlist_accommodation',
    'accommodation_image',
    'daily_revenue_stats',
    'payment_transaction',
    'reservation'
  )
  AND STAT_NAME IN ('n_diff_pfx01', 'n_diff_pfx02', 'n_diff_pfx03', 'n_leaf_pages', 'size')
  AND COALESCE(@airbob_optimizer_snapshot_id, '')
    REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
ORDER BY TABLE_NAME, INDEX_NAME, STAT_NAME;

SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'recordType', 'index-definition',
  'snapshotId', CAST(@airbob_optimizer_snapshot_id AS CHAR),
  'tableName', TABLE_NAME,
  'indexName', INDEX_NAME,
  'nonUnique', CAST(NON_UNIQUE AS UNSIGNED),
  'sequence', CAST(SEQ_IN_INDEX AS UNSIGNED),
  'columnName', COLUMN_NAME,
  'collation', COLLATION,
  'cardinality', IF(CARDINALITY IS NULL, NULL, CAST(CARDINALITY AS CHAR)),
  'nullable', NULLABLE,
  'indexType', INDEX_TYPE,
  'isVisible', IS_VISIBLE
)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'airbobdb'
  AND TABLE_NAME IN (
    'review',
    'accommodation_review_summary',
    'wishlist',
    'wishlist_accommodation',
    'accommodation_image',
    'daily_revenue_stats',
    'payment_transaction',
    'reservation'
  )
  AND COALESCE(@airbob_optimizer_snapshot_id, '')
    REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'recordType', 'histogram',
  'snapshotId', CAST(@airbob_optimizer_snapshot_id AS CHAR),
  'tableName', TABLE_NAME,
  'columnName', COLUMN_NAME,
  'histogramSha256', LOWER(SHA2(CAST(HISTOGRAM AS CHAR), 256)),
  'bucketCount', JSON_LENGTH(HISTOGRAM, '$."buckets"'),
  'dataType', JSON_UNQUOTE(JSON_EXTRACT(HISTOGRAM, '$."data-type"')),
  'lastUpdated', JSON_UNQUOTE(JSON_EXTRACT(HISTOGRAM, '$."last-updated"')),
  'histogram', HISTOGRAM
)
FROM information_schema.COLUMN_STATISTICS
WHERE SCHEMA_NAME = 'airbobdb'
  AND (
    (TABLE_NAME = 'review' AND COLUMN_NAME IN ('status', 'rating', 'created_at'))
    OR (TABLE_NAME = 'wishlist' AND COLUMN_NAME IN ('status', 'created_at', 'accommodation_count'))
    OR (TABLE_NAME = 'wishlist_accommodation' AND COLUMN_NAME IN ('created_at'))
    OR (TABLE_NAME = 'daily_revenue_stats' AND COLUMN_NAME IN ('stat_date'))
    OR (
      TABLE_NAME = 'payment_transaction'
      AND COLUMN_NAME IN ('transaction_type', 'status', 'created_at', 'canceled_at')
    )
    OR (TABLE_NAME = 'reservation' AND COLUMN_NAME IN ('status', 'created_at'))
  )
  AND COALESCE(@airbob_optimizer_snapshot_id, '')
    REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
ORDER BY TABLE_NAME, COLUMN_NAME;
