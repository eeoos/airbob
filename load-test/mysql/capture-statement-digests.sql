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
ORDER BY DIGEST;
