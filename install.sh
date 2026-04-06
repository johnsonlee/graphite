#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="$HOME/.graphite"
BIN_DIR="$INSTALL_DIR/bin"
LIB_DIR="$INSTALL_DIR/lib"
REPO="johnsonlee/graphite"

CLI_TOOLS="graphite-query graphite-explore"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${GREEN}[graphite]${NC} $1" >&2; }
warn() { echo -e "${YELLOW}[graphite]${NC} $1" >&2; }
error() { echo -e "${RED}[graphite]${NC} $1" >&2; exit 1; }

check_java() {
    if ! command -v java &>/dev/null; then
        error "Java not found. Install Java 17+ first: https://adoptium.net"
    fi
    local version
    version=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' | cut -d. -f1)
    if [ "$version" -lt 17 ] 2>/dev/null; then
        error "Java 17+ required, found Java $version"
    fi
    info "Found Java $version"
}

get_version() {
    local version="${1:-}"
    if [ -z "$version" ]; then
        info "Fetching latest version..."
        version=$(curl -sL "https://api.github.com/repos/$REPO/tags?per_page=1" \
            | grep '"name"' | head -1 | sed 's/.*"name": *"\(.*\)".*/\1/')
    fi
    if [ -z "$version" ]; then
        error "Could not determine latest version"
    fi
    echo "$version"
}

download_tool() {
    local tool="$1"
    local version="$2"
    local url="https://github.com/$REPO/releases/download/$version/$tool.jar"

    local http_code
    http_code=$(curl -sL -w '%{http_code}' -o "$LIB_DIR/$tool.jar" "$url")
    if [ "$http_code" != "200" ]; then
        rm -f "$LIB_DIR/$tool.jar"
        return 1
    fi
    return 0
}

create_wrapper() {
    local tool="$1"
    cat > "$BIN_DIR/$tool" << WRAPPER
#!/usr/bin/env bash
exec java \${GRAPHITE_OPTS:--Xmx4g} -jar "$LIB_DIR/$tool.jar" "\$@"
WRAPPER
    chmod +x "$BIN_DIR/$tool"
}

setup_path() {
    local shell_rc=""
    if [ -n "${ZSH_VERSION:-}" ] || [ -f "$HOME/.zshrc" ]; then
        shell_rc="$HOME/.zshrc"
    elif [ -f "$HOME/.bashrc" ]; then
        shell_rc="$HOME/.bashrc"
    elif [ -f "$HOME/.bash_profile" ]; then
        shell_rc="$HOME/.bash_profile"
    fi

    if [ -n "$shell_rc" ]; then
        if ! grep -q '.graphite/bin' "$shell_rc" 2>/dev/null; then
            echo "" >> "$shell_rc"
            echo "# Graphite CLI" >> "$shell_rc"
            echo 'export PATH="$HOME/.graphite/bin:$PATH"' >> "$shell_rc"
            info "Added $BIN_DIR to PATH in $shell_rc"
            warn "Run 'source $shell_rc' or open a new terminal"
        fi
    fi
}

main() {
    info "Installing Graphite CLI tools..."
    echo ""

    check_java

    local version
    version=$(get_version "${1:-}")
    info "Version: $version"

    mkdir -p "$LIB_DIR" "$BIN_DIR"

    local installed=0
    for tool in $CLI_TOOLS; do
        info "  Downloading $tool..."
        if download_tool "$tool" "$version"; then
            create_wrapper "$tool"
            installed=$((installed + 1))
        else
            warn "  $tool not available, skipping"
        fi
    done

    if [ "$installed" -eq 0 ]; then
        error "No tools installed. Check version: $version"
    fi

    # 'graphite' alias → graphite-query
    if [ -f "$BIN_DIR/graphite-query" ]; then
        ln -sf graphite-query "$BIN_DIR/graphite"
    fi

    setup_path
    echo "$version" > "$INSTALL_DIR/version"

    echo ""
    info "$installed tool(s) installed ($version)"
    echo ""
    echo "  Commands:"
    for cmd in $(ls "$BIN_DIR" 2>/dev/null | sort); do
        echo "    $cmd"
    done
    echo ""
    echo "  Quick start:"
    echo "    graphite build app.jar -o /data/graph --include com.example"
    echo "    graphite /data/graph cypher \"MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 10\""
    echo "    graphite-explore /data/graph --port 8080"
    echo ""
}

main "$@"
