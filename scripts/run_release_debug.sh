#!/bin/bash

# Enhanced debug launcher for CircuitJS1 Desktop release version

EXECUTABLE="./out/linux-x64/CircuitJS1 Desktop Mod/CircuitSimulator"
LOG_FILE="./debug.log"

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

echo "Starting CircuitSimulator with enhanced debug logging..."
echo "Log file: $LOG_FILE"
echo "---"

# Try different approaches to get debug output
# Method 1: Try with stdout/stderr redirection
echo "Method 1: Redirecting stdout/stderr to log file"
"$EXECUTABLE" 2>&1 | tee "$LOG_FILE" &
PID=$!

# Wait a bit and check if process is running
sleep 2
if ps -p $PID > /dev/null; then
    echo "CircuitSimulator started successfully (PID: $PID)"
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
