# AWS traffic SQL-digest capture

`capture-statement-digests.sql` reads cumulative MySQL Performance Schema
counters for the service schema only. Run it with `--batch --raw
--skip-column-names`; each output line is one JSON object with decimal counters
encoded as strings so JavaScript does not lose 64-bit precision.

The AWS runner captures four snapshots without truncating Performance Schema:

1. idle-control before
2. idle-control after the same-duration request-free window
3. measurement baseline after inspect and warm-up
4. measurement after the k6 traffic invocation

`aggregate-traffic-results.mjs` subtracts cumulative counters. A missing
previous digest, decreasing counter, digest-text change, nonzero idle delta, or
request-to-SQL-call mismatch invalidates the run. The report retains rankings
by total time, time per call, rows examined per call, and rows sent per call.

Do not reset Performance Schema counters to manufacture a clean window. The
before/after snapshots and idle control are the attribution evidence; setup,
login, warm-up, health checks, and snapshot queries themselves remain outside
the measurement interval.
