# Logstash Accommodation Reindex

This job rebuilds the `accommodations` Elasticsearch index from the current Airbob MySQL schema after an ETL dump/import.

## Prerequisites

Create the Logstash read-only MySQL user when the MySQL volume already exists:

```sql
CREATE USER IF NOT EXISTS 'logstash'@'%' IDENTIFIED BY 'logstash';
GRANT SELECT ON airbobdb.* TO 'logstash'@'%';
FLUSH PRIVILEGES;
```

If MySQL is initialized from `docker/mysql/init`, the user is created automatically.

## Local Reindex

Start MySQL and Elasticsearch:

```bash
docker compose up -d mysql elasticsearch
```

If the index may have old dynamic mappings, delete it and start Airbob once so Spring Data Elasticsearch recreates the mapping from `AccommodationDocument`:

```bash
curl -X DELETE http://localhost:9200/accommodations
```

Then run Logstash as a one-shot job:

```bash
docker compose --profile reindex run --rm logstash
```

Check the result:

```bash
curl "http://localhost:9200/accommodations/_count?pretty"
curl "http://localhost:9200/accommodations/_search?size=1&pretty"
```

## OCI Reindex

Create the same read-only `logstash` user in the target MySQL database, then run:

```bash
docker compose -f docker-compose.oci.yml --profile reindex run --rm logstash
```

Override credentials through environment variables when needed:

```bash
LOGSTASH_JDBC_USER=logstash \
LOGSTASH_JDBC_PASSWORD='...' \
docker compose -f docker-compose.oci.yml --profile reindex run --rm logstash
```

## Indexed Fields

The pipeline reads from:

- `accommodation`, `address`, `occupancy_policy`
- `accommodation_review_summary`
- `accommodation_amenity.amenity_code`
- future `reservation` rows with `status = 'CONFIRMED'`

It writes one document per published accommodation and builds `reservationRanges` for date-availability filtering.
