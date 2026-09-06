require "shellwords"

class Graphite < Formula
  desc "Build, query, and serve Graphite static analysis graphs"
  homepage "https://github.com/johnsonlee/graphite"
  url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-packaging-audit/graphite.jar"
  version "3.0.0-packaging-audit"
  sha256 "dfaea0fd1e11bbc2ec15b28fe3fdc76ea38306f3eea9c2dda47da7b8ab775d43"
  license "Apache-2.0"

  depends_on "openjdk@17"

  on_macos do
    on_intel do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-packaging-audit/graphite-server-3.0.0-packaging-audit-darwin-amd64.tar.gz"
        sha256 "7b8ebb36df5a7b79acfca5dce7ee84509e0fc047d42b77a70bd3eeed47674c77"
      end
    end
    on_arm do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-packaging-audit/graphite-server-3.0.0-packaging-audit-darwin-arm64.tar.gz"
        sha256 "842b2cdf69a1f005b1e11ec1b57917ce09441de4706ea5942eb546a6d32c14e3"
      end
    end
  end

  on_linux do
    on_intel do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-packaging-audit/graphite-server-3.0.0-packaging-audit-linux-amd64.tar.gz"
        sha256 "2a63e6e8a4b70cac21e70284edab8821d0ab303000e62f9d8419cc638fa2de81"
      end
    end
    on_arm do
      resource "native-server" do
        url "https://github.com/johnsonlee/graphite/releases/download/v3.0.0-packaging-audit/graphite-server-3.0.0-packaging-audit-linux-arm64.tar.gz"
        sha256 "0f914cf69a71978a4430e3eab1caa064dd581128526fe9130971a3a3dae3a351"
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
