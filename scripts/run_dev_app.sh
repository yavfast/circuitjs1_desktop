#!/bin/bash

# Ensure we are in the project root
cd "$(dirname "$0")/.."

# 1. Stop the application if it is running
echo "Stopping CircuitSimulator if running..."
pkill -f "CircuitSimulator"
# Give it a moment to close
sleep 1

# 2. Run build
echo "Building..."
node ./scripts/dev_n_build.js --buildall

# 3. Run if build successful
if [ $? -eq 0 ]; then
    echo "Build successful. Starting application..."
    ./scripts/run_release_debug.sh
else
    echo "Build failed."
    exit 1
fi
