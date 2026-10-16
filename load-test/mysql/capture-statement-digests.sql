SELECT JSON_OBJECT(
  'schemaName', SCHEMA_NAME,
  'digest', LOWER(DIGEST),
  'digestText', DIGEST_TEXT,
  'count', CAST(COUNT_STAR AS CHAR),
  'timerWait', CAST(SUM_TIMER_WAIT AS CHAR),
  'rowsExamined', CAST(SUM_ROWS_EXAMINED AS CHAR),
  'rowsSent', CAST(SUM_ROWS_SENT AS CHAR)
)
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'airbobdb'
  AND DIGEST IS NOT NULL
  AND DIGEST_TEXT IS NOT NULL
  AND COALESCE(@airbob_evidence_window_id, '') = ''
ORDER BY DIGEST;

-- Read-model evidence mode. The caller sets a closed measurement timer range
-- and stable public IDs before sourcing this file. It intentionally retains
-- normalized digest text, never the raw statement text that contains bind values.
--
-- Required:
--   @airbob_evidence_window_id
--   @airbob_evidence_target_id
--   @airbob_evidence_timer_start
--   @airbob_evidence_timer_end
-- Optional:
--   @airbob_evidence_thread_id (one Performance Schema thread)
--   @airbob_evidence_event_floor (exclusive event-id floor)
SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'schemaName', CURRENT_SCHEMA,
  'windowId', CAST(@airbob_evidence_window_id AS CHAR),
  'targetId', CAST(@airbob_evidence_target_id AS CHAR),
  'threadId', CAST(THREAD_ID AS CHAR),
  'eventId', CAST(EVENT_ID AS CHAR),
  'digest', LOWER(DIGEST),
  'digestText', DIGEST_TEXT,
  'timerStart', CAST(TIMER_START AS CHAR),
  'timerEnd', CAST(TIMER_END AS CHAR),
  'timerWait', CAST(TIMER_WAIT AS CHAR),
  'lockTime', CAST(LOCK_TIME AS CHAR),
  'rowsAffected', CAST(ROWS_AFFECTED AS CHAR),
  'rowsSent', CAST(ROWS_SENT AS CHAR),
  'rowsExamined', CAST(ROWS_EXAMINED AS CHAR),
  'createdTmpDiskTables', CAST(CREATED_TMP_DISK_TABLES AS CHAR),
  'noIndexUsed', CAST(NO_INDEX_USED AS UNSIGNED),
  'noGoodIndexUsed', CAST(NO_GOOD_INDEX_USED AS UNSIGNED),
  'errorNumber', CAST(MYSQL_ERRNO AS UNSIGNED)
)
FROM performance_schema.events_statements_history_long
WHERE CURRENT_SCHEMA = 'airbobdb'
  AND DIGEST IS NOT NULL
  AND DIGEST_TEXT IS NOT NULL
  AND TIMER_START IS NOT NULL
  AND TIMER_END IS NOT NULL
  AND COALESCE(@airbob_evidence_window_id, '') REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
  AND COALESCE(@airbob_evidence_target_id, '') REGEXP '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
  AND CAST(@airbob_evidence_timer_start AS UNSIGNED) > 0
  AND CAST(@airbob_evidence_timer_end AS UNSIGNED)
    > CAST(@airbob_evidence_timer_start AS UNSIGNED)
  AND TIMER_START >= CAST(@airbob_evidence_timer_start AS UNSIGNED)
  AND TIMER_END <= CAST(@airbob_evidence_timer_end AS UNSIGNED)
  AND (
    @airbob_evidence_thread_id IS NULL
    OR THREAD_ID = CAST(@airbob_evidence_thread_id AS UNSIGNED)
  )
  AND (
    @airbob_evidence_event_floor IS NULL
    OR EVENT_ID > CAST(@airbob_evidence_event_floor AS UNSIGNED)
  )
ORDER BY THREAD_ID, EVENT_ID;
