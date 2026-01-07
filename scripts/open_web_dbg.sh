#!/bin/bash
# Launch a Chromium-based browser with remote debugging for the dev server
# Uses environment variables:
#  BROWSER_BIN, REMOTE_DEBUG_PORT, APP_URL, SESSION_DIR, DEVTOOLS_WAIT, BROWSER_PID_FILE

set -e

BROWSER_BIN=${BROWSER_BIN:-chromium}
REMOTE_DEBUG_PORT=${REMOTE_DEBUG_PORT:-9222}
APP_URL=${APP_URL:-http://127.0.0.1:8888/circuitjs.html}
SESSION_DIR=${SESSION_DIR:-"${XDG_CACHE_HOME:-$HOME/.cache}/circuitjs-chrome-session"}
DEVTOOLS_WAIT=${DEVTOOLS_WAIT:-10}
BROWSER_PID_FILE=${BROWSER_PID_FILE:-/tmp/circuitjs-chrome-pid}

# Helper: is DevTools listening?
devtools_up() {
    curl -sSf --max-time 2 "http://127.0.0.1:${REMOTE_DEBUG_PORT}/json/version" > /dev/null 2>&1
}

# If DevTools already up, just try to confirm the app is listed and exit
if devtools_up; then
    echo "Detected existing DevTools on port ${REMOTE_DEBUG_PORT}; skipping browser launch."
    found=0
    for i in $(seq 1 $DEVTOOLS_WAIT); do
        if curl -sSf --max-time 2 "http://127.0.0.1:${REMOTE_DEBUG_PORT}/json/list" > /tmp/_devtools_list.json 2>/dev/null; then
            if grep -q "${APP_URL}" /tmp/_devtools_list.json || grep -q "circuitjs.html" /tmp/_devtools_list.json; then
                echo "DevTools is already listing the app page."
                found=1
                break
            fi
        fi
        sleep 1
    done
    if [ "$found" -eq 0 ]; then
        echo "Warning: DevTools is available but the app page was not found on /json/list."
    else
        # Try to reload the page via Chrome DevTools Protocol (CDP) WebSocket
        wsurl=$(sed -n 's/.*"webSocketDebuggerUrl"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /tmp/_devtools_list.json | head -n1 || true)
        if [ -n "$wsurl" ]; then
            echo "Found WebSocket debug URL: $wsurl"
            payload='{"id":1,"method":"Page.reload","params":{}}'

            # Try CDP via available WebSocket tools — tolerate failures (clearer errors)
            set +e
            sent=0

            # Prefer websocat
            if command -v websocat > /dev/null 2>&1; then
                echo "$payload" | websocat -1 "$wsurl" >/dev/null 2>&1 && sent=1
                if [ "$sent" -eq 1 ]; then
                    echo "Sent Page.reload via websocat."; set -e; exit 0; fi
            fi

            set -e
            if [ "$sent" -eq 0 ]; then
                echo "Warning: could not send CDP reload (missing websocat/wscat/python websocket-client)."
            fi
        fi
    fi
    exit 0
fi

if ! command -v "$BROWSER_BIN" > /dev/null 2>&1; then
    echo "Warning: $BROWSER_BIN not found in PATH; cannot launch browser."
    exit 1
fi

mkdir -p "$SESSION_DIR"
# Start browser
"$BROWSER_BIN" \
    --remote-debugging-port="$REMOTE_DEBUG_PORT" \
    --user-data-dir="$SESSION_DIR" \
    --no-first-run --no-default-browser-check --disable-extensions \
    --start-maximized --app="$APP_URL" &

browser_pid=$!
# Persist PID so caller can read it
echo "$browser_pid" > "$BROWSER_PID_FILE" || true

echo "Browser started (PID $browser_pid). To connect DevTools: http://127.0.0.1:${REMOTE_DEBUG_PORT}"

# Wait until DevTools lists the app page (best-effort)
found=0
for i in $(seq 1 $DEVTOOLS_WAIT); do
    if curl -sSf --max-time 2 "http://127.0.0.1:${REMOTE_DEBUG_PORT}/json/list" > /tmp/_devtools_list.json 2>/dev/null; then
        if grep -q "${APP_URL}" /tmp/_devtools_list.json || grep -q "circuitjs.html" /tmp/_devtools_list.json; then
            echo "DevTools is listing the app page (found in /json/list)."
            found=1
            break
        fi
    fi
    sleep 1
done
if [ "$found" -eq 0 ]; then
    echo "Warning: DevTools is available but the app page was not yet listed on /json/list."
fi

exit 0
