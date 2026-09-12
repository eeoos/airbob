#!/usr/bin/env python3
"""Restore an exact B native companion; alias activation is explicit."""
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'infra/aws/scripts'))
from growth_b_search import cli_restore

if __name__ == '__main__':
    try: cli_restore()
    except Exception as error:
        print('B search restore failed (' + type(error).__name__ + '); no successful receipt was issued.', file=sys.stderr)
        raise SystemExit(1)
