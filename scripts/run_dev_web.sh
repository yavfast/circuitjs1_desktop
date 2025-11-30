#!/bin/bash

# Run CircuitJS in browser using GWT DevMode (Super Dev Mode)
# This allows live reloading and debugging in browser

# Ensure we are in the project root
cd "$(dirname "$0")/.."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Starting CircuitJS in browser dev mode...${NC}"

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
echo -e "${GREEN}Press Ctrl+C to stop${NC}"
echo ""

# Run GWT DevMode
# -Dgwt.codeserver.port=9876 - Code server port for Super Dev Mode
# The startupUrl is configured in pom.xml
mvn gwt:devmode -Dgwt.codeserver.port=9876 &

# Alternative: Just compile and serve static files (uncomment if needed)
# echo "Building..."
# mvn gwt:compile
# echo "Starting simple HTTP server on port 8000..."
# cd war
# python3 -m http.server 8000
