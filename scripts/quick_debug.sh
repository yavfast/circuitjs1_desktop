#!/bin/bash

# Quick debug launcher with real-time log viewing

EXECUTABLE="./out/linux-x64/CircuitJS1 Desktop Mod/CircuitSimulator"
LOG_FILE="./debug.log"

echo "CircuitJS1 Desktop Mod - Quick Debug Launcher"
echo "============================================="

# Check if executable exists
if [ ! -f "$EXECUTABLE" ]; then
    echo "Error: Release executable not found at: $EXECUTABLE"
    echo "Please build the release version first."
    exit 1
fi

# Clear previous log
> "$LOG_FILE"

echo "Starting CircuitSimulator with debug logging..."
echo "Debug log: $LOG_FILE"
echo "Press Ctrl+C to stop"
echo "===================="

# Start CircuitSimulator with logging in background
"$EXECUTABLE" > "$LOG_FILE" 2>&1 &
APP_PID=$!

# Give it a moment to start
sleep 1

# Check if it started successfully
if ! ps -p $APP_PID > /dev/null; then
    echo "Failed to start CircuitSimulator"
    exit 1
fi

echo "CircuitSimulator started (PID: $APP_PID)"
echo "Monitoring debug output..."
echo ""

# Follow the log file in real-time
tail -f "$LOG_FILE" &
TAIL_PID=$!

# Set up cleanup function
cleanup() {
    echo ""
    echo "Stopping CircuitSimulator..."
    kill $APP_PID 2>/dev/null
    kill $TAIL_PID 2>/dev/null
    echo "Debug log saved to: $LOG_FILE"
    exit 0
}

# Handle Ctrl+C
trap cleanup INT

# Wait for CircuitSimulator to finish
wait $APP_PID
kill $TAIL_PID 2>/dev/null
echo ""
echo "CircuitSimulator closed"
echo "Debug log saved to: $LOG_FILE"
