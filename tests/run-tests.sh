#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JDK_ROOT:-}" ]]; then
    python3 tests/run-tests.py --java-root "$JDK_ROOT"
else
    python3 tests/run-tests.py
fi
