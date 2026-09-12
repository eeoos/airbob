#!/usr/bin/env python3
"""Local/OCI/AWS entry point; credentials are environment references in a private config."""
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'infra/aws/scripts'))
from growth_b_search import cli_producer

if __name__ == '__main__':
    try: cli_producer()
    except Exception as error:
        # JDBC, HTTP and process exceptions can contain private endpoint data.
        print('B search production failed (' + type(error).__name__ + '); no successful receipt was issued.', file=sys.stderr)
        raise SystemExit(1)
