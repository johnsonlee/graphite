#!/bin/bash
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-${JAVA_OPTS:--Xmx8g}}"
AGENT_ARGS=""
PASSTHROUGH_ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--profile" ]; then
    PROFILE_OUT="${GRAPHITE_PROFILE:-profile.html}"
    AP_LIB=""
    ASPROF="$(which asprof 2>/dev/null)"
    if [ -n "$ASPROF" ]; then
      AP_DIR="$(dirname "$ASPROF")/../lib"
      for ext in dylib so; do
        [ -f "$AP_DIR/libasyncProfiler.$ext" ] && AP_LIB="$AP_DIR/libasyncProfiler.$ext" && break
      done
    fi
    if [ -n "$AP_LIB" ]; then
      AGENT_ARGS="-agentpath:$AP_LIB=start,event=cpu,file=$PROFILE_OUT"
    else
      echo "async-profiler not found. Install: brew install async-profiler" >&2
      exit 1
    fi
  else
    PASSTHROUGH_ARGS+=("$arg")
  fi
done
exec "/usr/bin/java" $AGENT_ARGS -jar "/tmp/graphite-native-signal-main-build-4e328b0/graphite-query/build/libs/graphite.jar" "${PASSTHROUGH_ARGS[@]}"
