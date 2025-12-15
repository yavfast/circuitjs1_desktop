#!/bin/bash

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

if ensure_java17; then
    echo "Using JDK 17: JAVA_HOME=$JAVA_HOME"
else
    echo "Warning: JDK 17 not found; using default java: $(command -v java)"
    java -version 2>/dev/null | head -n 1 || true
fi

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
