-- Execute only against the verified benchmark dataset; export the one JSON cell.
-- Replace these two labels with the dataset release ID and its private test account.
-- Session variables and SELECTs do not modify business data.
SET @benchmark_dataset_id = 'REPLACE_WITH_VERIFIED_AWS_DATASET_ID';
SET @benchmark_email = 'REPLACE_WITH_EXISTING_BENCHMARK_ACCOUNT';

SELECT JSON_OBJECT(
  'datasetVersion', 'recently-viewed-v1',
  'datasetId', @benchmark_dataset_id,
  'account', JSON_OBJECT('email', m.email),
  'recentlyViewed', JSON_OBJECT('maxRows', f.row_count, 'accommodationIds', f.ids)
)
FROM member m
CROSS JOIN (
  SELECT COUNT(*) AS row_count, JSON_ARRAYAGG(selected.id) AS ids
  FROM (
    SELECT MIN(a.id) AS id
    FROM accommodation a
    JOIN address ad ON ad.id = a.address_id
    WHERE a.status = 'PUBLISHED'
    GROUP BY ad.id
    ORDER BY MIN(a.id)
    LIMIT 100
  ) selected
) f
WHERE m.email = @benchmark_email AND m.status = 'ACTIVE' AND f.row_count > 0;
