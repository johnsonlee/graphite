require "shellwords"

class Graphite < Formula
  desc "Build, query, and serve Graphite static analysis graphs"
  homepage "https://github.com/johnsonlee/graphite"
  url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-root-release-audit/graphite.jar"
  version "3.0.0-root-release-audit"
  sha256 "93b0c9d9fe65661932c25cd13deca8da0fec12af705106009a5df2eef6e75273"
  license "Apache-2.0"

  depends_on "openjdk@17"

  on_macos do
    on_intel do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-root-release-audit/graphite-server-3.0.0-root-release-audit-darwin-amd64.tar.gz"
        sha256 "533283bacaa3be8a1d49791008ba71a2f999a9a2babc6f42552c018a6c5ea6fe"
      end
    end
    on_arm do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-root-release-audit/graphite-server-3.0.0-root-release-audit-darwin-arm64.tar.gz"
        sha256 "0488cc43ce9a2a4aa8c9b321143685b4c58568da5ce244fa8927fd70227f4a19"
      end
    end
  end

  on_linux do
    on_intel do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-root-release-audit/graphite-server-3.0.0-root-release-audit-linux-amd64.tar.gz"
        sha256 "95878fdd4b064d0368ed2f4d8d6a0baa2f2139f7f9031dd3e155aadf8774d373"
      end
    end
    on_arm do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-root-release-audit/graphite-server-3.0.0-root-release-audit-linux-arm64.tar.gz"
        sha256 "bb7284d56b10897be081c40de72b14cbc1fcd7471632b72a2ed59c9071ab0c66"
      end
    end
  end

  def install
    libexec.install "graphite.jar"
    resource("native-server").stage do
      libexec.install "graphite-server"
      (libexec/"native-licenses").install "LICENSE", "licenses", "VERSION"
    end
    launcher = <<~'SH'
      #!/bin/bash
      # Server requests run the native executable; graph building and offline query
      # retain the JVM CLI. Arguments are always passed as an array, never evaluated.
      PROFILE=0
      PASSTHROUGH_ARGS=()
      for arg in "$@"; do
        if [ "$arg" = "--profile" ]; then
          PROFILE=1
        else
          PASSTHROUGH_ARGS+=("$arg")
        fi
      done
      
      if [ "${PASSTHROUGH_ARGS[0]:-}" = "serve" ]; then
        if [ "$PROFILE" = 1 ]; then
          export GRAPHITE_NATIVE_CPU_PROFILE=1
          export GRAPHITE_PROFILE="${GRAPHITE_PROFILE:-profile.html}"
        fi
        exec @NATIVE@ "${PASSTHROUGH_ARGS[@]}"
      fi
      
      export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-${JAVA_OPTS:--Xmx8g}}"
      # @files and global flags are parsed by the same Picocli command model as the
      # real invocation. The probe never executes user commands. A failed probe goes
      # through the normal JVM path so it cannot silently select a different command.
      if [ "$PROFILE" = 1 ]; then
        PROBE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/graphite-route.XXXXXX")" || exit 1
        PROBE_PID=""
        stop_probe() {
          trap - TERM INT
          if [ -n "$PROBE_PID" ]; then
            kill -TERM "$PROBE_PID" 2>/dev/null
            wait "$PROBE_PID" 2>/dev/null
          fi
          rm -rf -- "$PROBE_DIR"
          exit "$1"
        }
        trap 'stop_probe 143' TERM
        trap 'stop_probe 130' INT
        env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS @JAVA@ -Xmx128m -cp @JAR@ io.johnsonlee.graphite.cli.CommandRouteMainKt "${PASSTHROUGH_ARGS[@]}" > "$PROBE_DIR/route" &
        PROBE_PID=$!
        ROUTE=""
        if wait "$PROBE_PID"; then ROUTE="$(cat "$PROBE_DIR/route")"; fi
        PROBE_PID=""
        rm -rf -- "$PROBE_DIR"
        trap - TERM INT
        if [ "$ROUTE" = "serve" ]; then
          export GRAPHITE_NATIVE_CPU_PROFILE=1
          export GRAPHITE_PROFILE="${GRAPHITE_PROFILE:-profile.html}"
          exec @JAVA@ -jar @JAR@ "${PASSTHROUGH_ARGS[@]}"
        fi
      fi
      AGENT_ARGS=()
      if [ "$PROFILE" = 1 ]; then
        PROFILE_OUT="${GRAPHITE_PROFILE:-profile.html}"
        AP_LIB=""
        ASPROF="$(command -v asprof 2>/dev/null)"
        if [ -n "$ASPROF" ]; then
          AP_DIR="$(dirname "$ASPROF")/../lib"
          for ext in dylib so; do
            if [ -f "$AP_DIR/libasyncProfiler.$ext" ]; then
              AP_LIB="$AP_DIR/libasyncProfiler.$ext"
              break
            fi
          done
        fi
        if [ -z "$AP_LIB" ]; then
          echo "async-profiler not found. Install: brew install async-profiler" >&2
          exit 1
        fi
        AGENT_ARGS=("-agentpath:$AP_LIB=start,event=cpu,file=$PROFILE_OUT")
      fi
      exec @JAVA@ "${AGENT_ARGS[@]}" -jar @JAR@ "${PASSTHROUGH_ARGS[@]}"
    SH
    launcher = launcher.gsub("@JAVA@", "#{formula_opt_bin("openjdk@17")}/java".shellescape)
                       .gsub("@JAR@", (libexec/"graphite.jar").to_s.shellescape)
                       .gsub("@NATIVE@", (libexec/"graphite-server").to_s.shellescape)
    (bin/"graphite").write launcher
  end

  test do
    assert_match "Usage", shell_output("#{bin}/graphite --help")
    assert_match "Usage", shell_output("#{bin}/graphite serve --help")
  end
end
