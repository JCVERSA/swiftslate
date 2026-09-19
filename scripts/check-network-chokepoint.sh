#!/usr/bin/env bash
set -euo pipefail
violations=$(grep -RInE '\bopenConnection\(\)' app/src/main --include='*.kt' \
  | grep -v 'api/ApiConnections.kt' || true)
if [ -n "$violations" ]; then
  echo "::error::Direct openConnection() calls outside ApiConnections.kt:"
  echo "$violations"
  exit 1
fi
echo "F-1 guard passed: all connections route through ApiConnections."
