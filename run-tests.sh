#!/bin/bash
# Run core tests and capture output
cd "$(dirname "$0")"
sbt "core/test" 2>&1 | tee /tmp/core-test-output.txt
echo "Exit code: $?"
