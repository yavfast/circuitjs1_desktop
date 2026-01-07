#!/bin/bash

# Run CircuitJS in browser using GWT DevMode (Super Dev Mode)
# This allows live reloading and debugging in browser

# Ensure we are in the project root
cd "$(dirname "$0")/.."

ensure_java17() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        if "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q "^javac 17"; then
            export PATH="$JAVA_HOME/bin:$PATH"
            return 0
        fi
    fi

    for candidate in \
        "$CIRCUITJS1_JAVA_HOME" \
        "/usr/lib/jvm/java-17-openjdk" \
        "/usr/lib/jvm/java-17-openjdk-amd64" \
        "/usr/lib/jvm/java-17"; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
            if "$candidate/bin/javac" -version 2>&1 | grep -q "^javac 17"; then
                export JAVA_HOME="$candidate"
                export PATH="$JAVA_HOME/bin:$PATH"
                return 0
            fi
        fi
    done

    return 1
}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Starting CircuitJS in browser dev mode...${NC}"

if ensure_java17; then
    echo "Using JDK 17: JAVA_HOME=$JAVA_HOME"
else
    echo -e "${YELLOW}Warning: JDK 17 not found; Maven may fail (target=17).${NC}"
    echo -e "${YELLOW}Tip: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk${NC}"
fi

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed or not in PATH${NC}"
    exit 1
fi

# Kill any existing GWT code server
echo -e "${YELLOW}Stopping any existing GWT processes...${NC}"
pkill -f "codeserver" 2>/dev/null
pkill -f "gwt:devmode" 2>/dev/null
sleep 1

# Option 1: Use GWT DevMode (recommended for debugging)
# This starts a code server and opens the app in browser
echo -e "${GREEN}Starting GWT DevMode...${NC}"
echo -e "${YELLOW}The application will be available at: http://127.0.0.1:8888/circuitjs.html${NC}"
echo -e "${YELLOW}Code Server will be at: http://127.0.0.1:9876/${NC}"
echo ""

# Run GWT DevMode
# -Dgwt.codeserver.port=9876 - Code server port for Super Dev Mode
# The startupUrl is configured in pom.xml
mvn gwt:devmode -Dgwt.codeserver.port=9876 &
mvn_pid=$!

# Browser/agent settings (can be overriden via env)
BROWSER_BIN=${BROWSER_BIN:-chromium}
REMOTE_DEBUG_PORT=${REMOTE_DEBUG_PORT:-9222}
APP_URL=${APP_URL:-http://127.0.0.1:8888/circuitjs.html}
WAIT_TIMEOUT=${WAIT_TIMEOUT:-60}

wait_for_url() {
    local url="$1"
    local timeout="$2"
    local waited=0
    until curl -sSf --max-time 2 "$url" > /dev/null 2>&1; do
        sleep 1
        waited=$((waited+1))
        if [ "$waited" -ge "$timeout" ]; then
            echo -e "${RED}Error: Timed out waiting for $url${NC}"
            return 1
        fi
    done
    return 0
}

echo -e "${GREEN}Waiting for application at ${APP_URL}...${NC}"
if wait_for_url "$APP_URL" "$WAIT_TIMEOUT"; then
    echo -e "${GREEN}Application is up. Preparing to ensure a Chromium agent is available on port ${REMOTE_DEBUG_PORT}...${NC}"

    # Launch browser helper (delegated)
    echo -e "${GREEN}Launching browser helper: scripts/open_web_dbg.sh${NC}"
    "$PWD/scripts/open_web_dbg.sh" &

else
    echo -e "${YELLOW}Warning: Application did not become available within ${WAIT_TIMEOUT}s. Not launching browser.${NC}"
fi
