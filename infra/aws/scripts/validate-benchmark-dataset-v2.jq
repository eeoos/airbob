def max_safe_integer: 9007199254740991;
def api_integer_max: 2147483647;
def axes: ["FAN_OUT", "VALUE_SKEW", "RECENCY", "SELECTIVITY", "CONTENTION"];
def shapes: [
  "CARDINALITY_BUCKETS",
  "UNIFORM",
  "HOTSET",
  "RECENT_HEAVY",
  "SELECTIVITY_BUCKETS",
  "CONTENTION_RATIOS"
];
def required_tables: [
  "accommodation",
  "accommodation_inventory_day",
  "member",
  "payment_transaction",
  "reservation",
  "review",
  "wishlist"
];
def required_observed: [
  "accommodation-type-skew",
  "payment-recency",
  "reservation-guest-skew",
  "review-fanout",
  "wishlist-accommodation-skew",
  "wishlist-fanout"
];
def required_scope_ranges: [
  "accommodation",
  "member",
  "payment",
  "payment-transaction",
  "reservation",
  "review",
  "wishlist",
  "wishlist-accommodation"
];
def read_model_targets: {
  "review-hot": "REVIEW_SUMMARY_V1",
  "review-median": "REVIEW_SUMMARY_V1",
  "review-cold": "REVIEW_SUMMARY_V1",
  "review-empty": "REVIEW_SUMMARY_V1",
  "wishlist-hot": "WISHLIST_PAGE_V1",
  "wishlist-median": "WISHLIST_PAGE_V1",
  "wishlist-cold": "WISHLIST_PAGE_V1",
  "wishlist-empty": "WISHLIST_PAGE_V1",
  "wishlist-hot-deep": "WISHLIST_PAGE_V1",
  "revenue-recent-1d": "REVENUE_RANGE_V1",
  "revenue-recent-7d": "REVENUE_RANGE_V1",
  "revenue-medium": "REVENUE_RANGE_V1",
  "revenue-broad": "REVENUE_RANGE_V1",
  "revenue-empty": "REVENUE_RANGE_V1",
  "revenue-refund-boundary": "REVENUE_RANGE_V1"
};

def exact_keys($expected):
  type == "object" and keys == ($expected | sort);

def canonical_id:
  type == "string" and test("^[a-z0-9][a-z0-9-]*$");

def canonical_table:
  type == "string" and test("^[a-z][a-z0-9_]*$");

def safe_integer($minimum):
  type == "number"
  and isfinite
  and floor == .
  and . >= $minimum
  and . <= max_safe_integer;

def nonnegative_integer: safe_integer(0);
def positive_integer: safe_integer(1);
def sha256: type == "string" and test("^[0-9a-f]{64}$");
def canonical_email:
  type == "string" and test("^[a-z0-9][a-z0-9._+-]*@airbob\\.cloud$");

def leap_year($year):
  ($year % 4 == 0) and (($year % 100 != 0) or ($year % 400 == 0));

def days_in_month($year; $month):
  if $month == 2 then (if leap_year($year) then 29 else 28 end)
  elif [1, 3, 5, 7, 8, 10, 12] | index($month) != null then 31
  elif [4, 6, 9, 11] | index($month) != null then 30
  else 0
  end;

def canonical_date:
  type == "string"
  and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
  and ((split("-") | map(tonumber)) as $parts
    | ($parts[1] >= 1 and $parts[1] <= 12)
    and ($parts[2] >= 1 and $parts[2] <= days_in_month($parts[0]; $parts[1])));

def canonical_datetime:
  type == "string"
  and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$")
  and (.[0:10] | canonical_date)
  and ((.[11:] | split(":") | map(tonumber)) as $parts
    | $parts[0] <= 23 and $parts[1] <= 59 and $parts[2] <= 59);

def canonical_instant:
  type == "string"
  and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
  and (.[0:-1] | canonical_datetime);

def canonical_timezone:
  type == "string"
  and (. == "UTC" or test("^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)+$"))
  and (contains("..") | not);

def unique_array:
  type == "array" and length == (unique | length);

def canonical_integer_map($allow_empty):
  type == "object"
  and ($allow_empty or length > 0)
  and all(to_entries[]; (.key | canonical_id) and (.value | nonnegative_integer));

def valid_observed_distribution:
  . as $distribution
  |
  exact_keys(["id", "axis", "totalRows", "distinctKeys", "maxRowsPerKey", "bucketUnit", "buckets"])
  and (.id | canonical_id)
  and (.axis as $axis | axes | index($axis) != null)
  and (.totalRows | nonnegative_integer)
  and (.distinctKeys | nonnegative_integer)
  and (.maxRowsPerKey | nonnegative_integer)
  and (((.totalRows == 0) and (.distinctKeys == 0) and (.maxRowsPerKey == 0))
    or ((.totalRows > 0) and (.distinctKeys > 0) and (.maxRowsPerKey > 0)))
  and (.bucketUnit == "ROWS" or .bucketUnit == "KEYS")
  and (.buckets | canonical_integer_map(false))
  and (if .bucketUnit == "ROWS"
    then ([.buckets[]] | add) == $distribution.totalRows
    else true
    end);

def valid_provenance($world):
  exact_keys([
    "profileVersion", "generatorVersion", "prngAlgorithm", "seedDerivation", "globalSeed",
    "anchor", "timezone", "sourceInventorySha256", "calibrationVersion",
    "calibrationSha256", "specSha256", "verificationPassed", "assertionSha256"
  ])
  and (.profileVersion | canonical_id)
  and (.generatorVersion | canonical_id)
  and (.prngAlgorithm | canonical_id)
  and (.calibrationVersion | canonical_id)
  and (.seedDerivation | type == "string" and length > 0
    and (explode | all(.[]; . >= 32 and . != 127)))
  and (.globalSeed | safe_integer(-max_safe_integer))
  and (.globalSeed == $world.seed)
  and (.anchor | canonical_instant)
  and (.timezone | canonical_timezone)
  and (.timezone == $world.timezone)
  and (.verificationPassed == true)
  and (.sourceInventorySha256 | sha256)
  and (.calibrationSha256 | sha256)
  and (.specSha256 | sha256)
  and (.assertionSha256 | sha256);

def valid_scoped_observation($map_key):
  exact_keys([
    "id", "totalRows", "keyCount", "zeroKeys", "p50", "p95", "p99", "maximum",
    "bucketUnit", "buckets", "shares", "rankRows"
  ])
  and (.id == $map_key)
  and (.totalRows | nonnegative_integer)
  and (.keyCount | nonnegative_integer)
  and (.zeroKeys | nonnegative_integer)
  and (.p50 | nonnegative_integer)
  and (.p95 | nonnegative_integer)
  and (.p99 | nonnegative_integer)
  and (.maximum | nonnegative_integer)
  and (.zeroKeys <= .keyCount)
  and (.p50 <= .p95 and .p95 <= .p99 and .p99 <= .maximum)
  and (.bucketUnit == "ROWS" or .bucketUnit == "KEYS")
  and (.buckets | canonical_integer_map(true))
  and (.rankRows | canonical_integer_map(true))
  and (.shares | type == "object")
  and all(.shares | to_entries[];
    (.key | canonical_id)
    and (.value | type == "number" and isfinite and . >= 0 and . <= 1));

def valid_scope_range($map_key):
  exact_keys(["id", "minimumId", "maximumId", "rowCount"])
  and (.id == $map_key)
  and (.rowCount | positive_integer)
  and (.minimumId | positive_integer)
  and (.maximumId | positive_integer)
  and .maximumId >= .minimumId
  and .rowCount == (.maximumId - .minimumId + 1);

def valid_world:
  . as $world
  | exact_keys([
      "version", "profile", "seed", "anchorTime", "validUntil", "timezone", "flywayVersion",
      "claimScope", "tableRows", "observedDistributions", "provenance",
      "scopedObservedDistributions", "scopeRanges", "fingerprints"
    ])
  and (.version == "world-v2")
  and (.profile == "DEMO" or .profile == "PERF" or .profile == "LARGE")
  and (.seed | safe_integer(-max_safe_integer))
  and (.anchorTime | canonical_datetime)
  and (.validUntil | canonical_datetime)
  and (.validUntil > .anchorTime)
  and (.timezone | canonical_timezone)
  and (.flywayVersion == 27)
  and (.claimScope == "controlled-synthetic-workload")
  and (.tableRows | type == "object")
  and all(.tableRows | to_entries[]; (.key | canonical_table) and (.value | nonnegative_integer))
  and (required_tables as $tables | all($tables[]; . as $table | $world.tableRows | has($table)))
  and (.tableRows.accommodation_inventory_day == 0)
  and (.observedDistributions | type == "array" and length > 0)
  and all(.observedDistributions[]; valid_observed_distribution)
  and ([.observedDistributions[].id] | unique_array)
  and ([.observedDistributions[].id] as $ids
    | required_observed as $required
    | all($required[]; . as $id | $ids | index($id) != null))
  and (.provenance | valid_provenance($world))
  and (.scopedObservedDistributions | type == "object" and length > 0)
  and all(.scopedObservedDistributions | to_entries[]; . as $entry
    | ($entry.key | canonical_id)
    and ($entry.value | valid_scoped_observation($entry.key)))
  and (.scopeRanges | exact_keys(required_scope_ranges))
  and all(.scopeRanges | to_entries[]; . as $entry
    | ($entry.key | canonical_id)
    and ($entry.value | valid_scope_range($entry.key)))
  and (.fingerprints | type == "object")
  and (.fingerprints["final-world"] | sha256)
  and (.fingerprints["final-inventory"] | sha256)
  and (.fingerprints["base-world"] | sha256)
  and all(.fingerprints | to_entries[]; (.key | canonical_id) and (.value | sha256));

def valid_buckets($allow_zero):
  type == "array"
  and length > 0
  and all(.[]; if $allow_zero then nonnegative_integer else positive_integer end)
  and (sort == .)
  and unique_array;

def valid_distribution:
  exact_keys(["id", "axis", "shape", "buckets", "parameters"])
  and (.id | canonical_id)
  and (.axis as $axis | axes | index($axis) != null)
  and (.shape as $shape | shapes | index($shape) != null)
  and (.buckets | type == "array")
  and (.parameters | type == "object")
  and (if .shape == "CARDINALITY_BUCKETS"
    then .axis == "FAN_OUT" and (.buckets | valid_buckets(true)) and (.parameters | exact_keys([]))
    elif .shape == "SELECTIVITY_BUCKETS"
    then .axis == "SELECTIVITY" and (.buckets | valid_buckets(true)) and (.parameters | exact_keys([]))
    elif .shape == "CONTENTION_RATIOS"
    then .axis == "CONTENTION" and (.buckets | valid_buckets(false)) and (.parameters | exact_keys([]))
    elif .shape == "UNIFORM"
    then .axis == "VALUE_SKEW" and (.buckets | length == 0)
      and (.parameters | exact_keys(["totalKeys"]) and (.totalKeys | positive_integer))
    elif .shape == "HOTSET"
    then .axis == "VALUE_SKEW" and (.buckets | length == 0)
      and (.parameters | exact_keys(["totalKeys", "hotKeys", "hotTrafficPercent"])
        and (.totalKeys | positive_integer)
        and (.hotKeys | positive_integer)
        and (.hotTrafficPercent | positive_integer)
        and .hotKeys < .totalKeys
        and .hotTrafficPercent <= 100)
    else .axis == "RECENCY" and (.buckets | length == 0)
      and (.parameters | exact_keys(["totalDays", "recentDays", "recentTrafficPercent"])
        and (.totalDays | positive_integer)
        and (.recentDays | positive_integer)
        and (.recentTrafficPercent | positive_integer)
        and .recentDays < .totalDays
        and .recentTrafficPercent <= 100)
    end);

def valid_account_pool:
  . as $pool
  |
  exact_keys(["capacity", "emails"])
  and (.capacity | nonnegative_integer)
  and (.emails | type == "array")
  and ((.emails | length) == $pool.capacity)
  and all(.emails[]; canonical_email)
  and (.emails | unique_array);

def valid_target_account:
  exact_keys(["memberId", "email", "role", "status"])
  and (.memberId | positive_integer)
  and (.email | canonical_email)
  and (.role == "MEMBER" or .role == "ADMIN")
  and (.status == "ACTIVE");

def valid_review_query($target):
  exact_keys(["kind", "accommodationId"])
  and (.kind == "REVIEW_SUMMARY_V1")
  and (.accommodationId | positive_integer)
  and ($target.resourceIds == [.accommodationId])
  and ($target | has("account") | not);

def valid_wishlist_query($target):
  exact_keys(["kind", "memberId", "size", "lastId", "lastCreatedAt", "accommodationId", "totalActiveRows"])
  and (.kind == "WISHLIST_PAGE_V1")
  and (.memberId | positive_integer)
  and (.size | positive_integer)
  and (.size <= 50)
  and ((.lastId == null) == (.lastCreatedAt == null))
  and (if .lastId == null then true
    else (.lastId | positive_integer) and (.lastCreatedAt | canonical_datetime)
    end)
  and (if .accommodationId == null then true else (.accommodationId | positive_integer) end)
  and (.totalActiveRows | nonnegative_integer)
  and ($target.expectedRows <= .size)
  and ($target.resourceIds == [.memberId])
  and ($target.account | valid_target_account)
  and ($target.account.memberId == .memberId)
  and ($target.account.role == "MEMBER")
  and ($target.account.status == "ACTIVE")
  and (if $target.id == "wishlist-hot-deep"
    then .lastId != null and .lastCreatedAt != null
    else true
    end);

def valid_revenue_query($target):
  exact_keys(["kind", "from", "to", "dayBoundary"])
  and (.kind == "REVENUE_RANGE_V1")
  and (.from | canonical_date)
  and (.to | canonical_date)
  and (.to >= .from)
  and (.dayBoundary == "UTC")
  and ($target.resourceIds | length == 0)
  and ($target.account | valid_target_account)
  and ($target.account.role == "ADMIN")
  and ($target.account.status == "ACTIVE");

def valid_search_query($target):
  exact_keys([
    "kind", "destination", "minPrice", "maxPrice", "adultOccupancy", "childOccupancy",
    "infantOccupancy", "petOccupancy", "topLeftLat", "topLeftLng", "bottomRightLat",
    "bottomRightLng", "page"
  ])
  and (.kind == "ACCOMMODATION_SEARCH_V1")
  and (.destination == "")
  and (.minPrice | nonnegative_integer and . <= api_integer_max)
  and (.maxPrice | nonnegative_integer and . <= api_integer_max)
  and (.adultOccupancy | positive_integer and . <= api_integer_max)
  and (.childOccupancy | nonnegative_integer and . <= api_integer_max)
  and (.infantOccupancy | nonnegative_integer and . <= api_integer_max)
  and (.petOccupancy | nonnegative_integer and . <= api_integer_max)
  and (.page | nonnegative_integer and . <= 14)
  and (.adultOccupancy + .childOccupancy <= api_integer_max)
  and (.minPrice <= .maxPrice)
  and (.topLeftLat | type == "number" and isfinite and . >= -90 and . <= 90)
  and (.bottomRightLat | type == "number" and isfinite and . >= -90 and . <= 90)
  and (.topLeftLng | type == "number" and isfinite and . >= -180 and . <= 180)
  and (.bottomRightLng | type == "number" and isfinite and . >= -180 and . <= 180)
  and (.topLeftLat > .bottomRightLat)
  and (.topLeftLng < .bottomRightLng)
  and ($target | has("account") | not);

def valid_query($target):
  if .kind == "REVIEW_SUMMARY_V1" then valid_review_query($target)
  elif .kind == "WISHLIST_PAGE_V1" then valid_wishlist_query($target)
  elif .kind == "REVENUE_RANGE_V1" then valid_revenue_query($target)
  elif .kind == "ACCOMMODATION_SEARCH_V1" then valid_search_query($target)
  else false
  end;

def valid_target:
  . as $target
  | (type == "object")
  and (if has("query")
    then if (.query.kind == "WISHLIST_PAGE_V1" or .query.kind == "REVENUE_RANGE_V1")
      then exact_keys(["id", "expectedRows", "resourceIds", "query", "expectedResultHash", "account"])
      else exact_keys(["id", "expectedRows", "resourceIds", "query", "expectedResultHash"])
      end
    else exact_keys(["id", "expectedRows", "resourceIds"])
    end)
  and (.id | canonical_id)
  and (.expectedRows | nonnegative_integer)
  and (.resourceIds | type == "array")
  and all(.resourceIds[]; positive_integer)
  and (.resourceIds | unique_array)
  and (if has("query")
    then (.query | type == "object")
      and (.expectedResultHash | sha256)
      and (.query | valid_query($target))
    else true
    end);

def valid_read_model_capsule:
  . as $capsule
  | (.accountPool.capacity == 5)
  and ([.targets[].id] | sort == (read_model_targets | keys))
  and all(.targets[]; .query.kind == read_model_targets[.id])
  and all(["wishlist-hot", "wishlist-median", "wishlist-cold", "wishlist-empty"][];
    . as $id
    | ($capsule.targets[] | select(.id == $id) | .query)
    | .lastId == null and .lastCreatedAt == null)
  and ([.targets[] | select(has("account")) | .account.email] | unique | sort
    == ($capsule.accountPool.emails | sort));

def valid_capsule:
  exact_keys([
    "capsuleId", "schemaVersion", "mutability", "touchedTables", "distributions",
    "accountPool", "targets", "runtime"
  ])
  and (.capsuleId | canonical_id)
  and (.schemaVersion == 1)
  and (.mutability == "READ_ONLY" or .mutability == "RUN_LOCAL_WRITE")
  and (.touchedTables | type == "array" and length > 0)
  and all(.touchedTables[]; canonical_table)
  and (.touchedTables | unique_array)
  and (.distributions | type == "array" and length > 0)
  and all(.distributions[]; valid_distribution)
  and ([.distributions[].id] | unique_array)
  and (.accountPool | valid_account_pool)
  and (.targets | type == "array")
  and all(.targets[]; valid_target)
  and ([.targets[].id] | unique_array)
  and ([.targets[] | if has("query") then {query, resourceIds} else {id, resourceIds} end] | unique_array)
  and ((.targets | length > 0) or (.accountPool.capacity > 0))
  and (.runtime | exact_keys(["owner", "setup", "resetPolicy"]))
  and (.runtime.owner == "AIRBOB_APPLICATION" or .runtime.owner == "K6_HARNESS" or .runtime.owner == "AWS_LAB")
  and (.runtime.setup | canonical_id)
  and (.runtime.resetPolicy | canonical_id)
  and (if .capsuleId == "read-model-v2" then valid_read_model_capsule else true end);

def valid_manifest:
  exact_keys(["schemaVersion", "datasetVersion", "world", "capsules", "targetFingerprint"])
  and (.schemaVersion == 2)
  and (.datasetVersion == "benchmark-dataset-v2")
  and (.world | valid_world)
  and (.capsules | type == "array" and length > 0)
  and all(.capsules[]; valid_capsule)
  and ([.capsules[].capsuleId] | unique_array)
  and ([.capsules[].capsuleId] | index("read-model-v2") != null)
  and (.targetFingerprint | sha256);

if valid_manifest then . else error("invalid benchmark-dataset-v2 manifest") end
