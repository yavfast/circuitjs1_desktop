#!/bin/bash

# Enhanced debug launcher for CircuitJS1 Desktop release version

# Ensure we are in the project root
cd "$(dirname "$0")/.."

DETACH=0
START_REMOTE_DEBUG_SERVER=0
for arg in "$@"; do
    case "$arg" in
        --bg|--background|--detach)
            DETACH=1
            ;;
        --remote-debug|--remote-debug-server|--start-remote-debug-server)
            START_REMOTE_DEBUG_SERVER=1
            ;;
    esac
done

EXECUTABLE="./out/linux-x64/CircuitJS1 Desktop Mod/CircuitSimulator"
LOG_FILE="./debug.log"

REMOTE_DEBUG_HOST=${REMOTE_DEBUG_HOST:-127.0.0.1}
REMOTE_DEBUG_PORT=${REMOTE_DEBUG_PORT:-3030}
REMOTE_DEBUG_SERVER_DIR=${REMOTE_DEBUG_SERVER_DIR:-./server}
REMOTE_DEBUG_SERVER_LOG=${REMOTE_DEBUG_SERVER_LOG:-./remote-debug-server.log}

start_remote_debug_server_if_needed() {
    if [ "$START_REMOTE_DEBUG_SERVER" -ne 1 ]; then
        return 0
    fi

    if ! command -v curl >/dev/null 2>&1; then
        echo "Error: curl is required to auto-start/health-check the Remote Debug server"
        return 1
    fi

    if curl -fsS --max-time 1 "http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}/api/channels" >/dev/null 2>&1; then
        echo "Remote Debug Server already running at http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}"
        return 0
    fi

    echo "Remote Debug Server not detected; starting..."

    if [ ! -d "$REMOTE_DEBUG_SERVER_DIR" ]; then
        echo "Error: server directory not found: $REMOTE_DEBUG_SERVER_DIR"
        return 1
    fi

    if [ ! -d "$REMOTE_DEBUG_SERVER_DIR/node_modules" ]; then
        echo "Error: server dependencies not installed. Run: (cd $REMOTE_DEBUG_SERVER_DIR && npm install)"
        return 1
    fi

    (
        cd "$REMOTE_DEBUG_SERVER_DIR" || exit 1
        PORT="$REMOTE_DEBUG_PORT" nohup node remote-debug-server.js > "../$(basename "$REMOTE_DEBUG_SERVER_LOG")" 2>&1 &
        echo $! > "../.remote-debug-server.pid"
    )

    sleep 0.5
    if curl -fsS --max-time 2 "http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}/api/channels" >/dev/null 2>&1; then
        echo "Remote Debug Server started: http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}"
        echo "Server log: $REMOTE_DEBUG_SERVER_LOG"
        echo "Server pid: $(cat ./.remote-debug-server.pid 2>/dev/null || true)"
        return 0
    fi

    echo "Warning: Remote Debug Server failed to respond on http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}"
    echo "Check server log: $REMOTE_DEBUG_SERVER_LOG"
    return 1
}

echo "CircuitJS1 Desktop Mod - Enhanced Debug Launcher"
echo "================================================="

# Check if executable exists
if [ ! -f "$EXECUTABLE" ]; then
    echo "Error: Release executable not found at: $EXECUTABLE"
    echo "Please build the release version first using:"
    echo "  node scripts/dev_n_build.js --buildall"
    echo "  or: node scripts/dev_n_build.js (then select option 6 or 8)"
    exit 1
fi

# Clear previous log
> "$LOG_FILE"

start_remote_debug_server_if_needed || exit 1

echo "Starting CircuitSimulator with enhanced debug logging..."
echo "Log file: $LOG_FILE"
echo "---"

# Try different approaches to get debug output
# Method 1: Try with stdout/stderr redirection
echo "Method 1: Redirecting stdout/stderr to log file"
if [ "$DETACH" -eq 1 ]; then
    "$EXECUTABLE" >> "$LOG_FILE" 2>&1 &
    PID=$!
else
    "$EXECUTABLE" 2>&1 | tee "$LOG_FILE" &
    PID=$!
fi

# Wait a bit and check if process is running
sleep 2
if ps -p $PID > /dev/null; then
    echo "CircuitSimulator started successfully (PID: $PID)"
    if [ "$DETACH" -eq 1 ]; then
        echo "Detached mode enabled; not tailing logs."
        echo "To follow logs: tail -f $LOG_FILE"
        if [ "$START_REMOTE_DEBUG_SERVER" -eq 1 ]; then
            echo "Remote Debug Viewer: http://${REMOTE_DEBUG_HOST}:${REMOTE_DEBUG_PORT}/"
        fi
        exit 0
    fi

    echo "Press Ctrl+C to stop monitoring logs"
    echo "=================================="

    # Monitor the log file
    tail -f "$LOG_FILE" &
    TAIL_PID=$!

    # Wait for the main process to finish
    wait $PID

    # Kill the tail process
    kill $TAIL_PID 2>/dev/null
else
    echo "Failed to start CircuitSimulator"
    exit 1
fi

echo ""
echo "--- CircuitSimulator closed ---"
echo "Debug log saved to: $LOG_FILE"
