# Local cache experiment targets

The local runner's optional --grafana mode atomically creates its own
cache-experiment-<run>-{apps,redis,runner}.json files here and removes them on exit.
Only loopback ports, run IDs and public measurement labels are written; no tokens.
The directory is mounted read-only into the local Prometheus container.
After a hard kill, remove only the target files for that interrupted run.
