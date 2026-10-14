#!/bin/sh
# Builds the server and runs all regression scenarios. See tools/stress/regress.sh.
# usage: ./test.sh [seconds-of-load] [scenario...]
exec "$(dirname "$0")/tools/stress/regress.sh" "$@"
