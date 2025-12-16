// Debug logging module for CircuitJS1
(function() {
    'use strict';

    // Create debug console
    const debugConsole = {
        originalLog: console.log,
        originalError: console.error,
        originalWarn: console.warn,

        init: function() {
            // Override console methods to also write to file/stdout
            console.log = function(...args) {
                debugConsole.originalLog.apply(console, args);
                debugConsole.writeToOutput('LOG', args);
            };

            console.error = function(...args) {
                debugConsole.originalError.apply(console, args);
                debugConsole.writeToOutput('ERROR', args);
            };

            console.warn = function(...args) {
                debugConsole.originalWarn.apply(console, args);
                debugConsole.writeToOutput('WARN', args);
            };

            // Catch unhandled errors
            window.addEventListener('error', function(e) {
                const details = `${e.message} at ${e.filename}:${e.lineno}:${e.colno}` + (e.error && e.error.stack ? '\n' + e.error.stack : '');
                debugConsole.writeToOutput('UNHANDLED_ERROR', [details]);
                // Show modal dialog to user
                debugConsole.showErrorDialog('Unhandled error', details);
                // Also log to CircuitJS API if available
                debugConsole.logToCircuitApi('UNHANDLED_ERROR: ' + details);
            });

            // Catch unhandled promise rejections
            window.addEventListener('unhandledrejection', function(e) {
                const details = (e.reason && e.reason.stack) ? (String(e.reason) + '\n' + e.reason.stack) : String(e.reason);
                debugConsole.writeToOutput('UNHANDLED_REJECTION', [details]);
                // Show modal dialog to user
                debugConsole.showErrorDialog('Unhandled promise rejection', details);
                // Also log to CircuitJS API if available
                debugConsole.logToCircuitApi('UNHANDLED_REJECTION: ' + details);
            });

            console.log('Debug logging initialized for CircuitJS1');

            // Try flushing any pending API logs periodically and now
            try { debugConsole._flushPendingApiLogs(); } catch (e) {}
            setInterval(function() { try { debugConsole._flushPendingApiLogs(); } catch (e) {} }, 5000);
        },

        writeToOutput: function(level, args) {
            const timestamp = new Date().toISOString();
            const message = args.map(arg =>
                typeof arg === 'object' ? JSON.stringify(arg) : String(arg)
            ).join(' ');

            const logLine = `[${timestamp}] [${level}] ${message}`;

            // Try to write to Node.js process if available
            if (typeof process !== 'undefined' && process.stdout) {
                process.stdout.write(logLine + '\n');
            }

            // Also try to write to a debug element in DOM
            debugConsole.writeToDOM(logLine);
        },

        writeToDOM: function(message) {
            let debugDiv = document.getElementById('debug-log');
            if (!debugDiv) {
                debugDiv = document.createElement('div');
                debugDiv.id = 'debug-log';
                debugDiv.style.cssText = `
                    position: fixed;
                    top: 10px;
                    right: 10px;
                    width: 400px;
                    height: 300px;
                    background: rgba(0,0,0,0.8);
                    color: white;
                    font-family: monospace;
                    font-size: 12px;
                    padding: 10px;
                    overflow-y: auto;
                    z-index: 10000;
                    border: 1px solid #ccc;
                    display: none;
                `;
                document.body.appendChild(debugDiv);

                // Add toggle button
                const toggleBtn = document.createElement('button');
                toggleBtn.textContent = 'Debug';
                toggleBtn.style.cssText = `
                    position: fixed;
                    top: 10px;
                    right: 420px;
                    z-index: 10001;
                    background: #333;
                    color: white;
                    border: none;
                    padding: 5px 10px;
                    cursor: pointer;
                `;
                toggleBtn.onclick = function() {
                    debugDiv.style.display = debugDiv.style.display === 'none' ? 'block' : 'none';
                };
                document.body.appendChild(toggleBtn);
            }

            debugDiv.innerHTML += message + '\n';
            debugDiv.scrollTop = debugDiv.scrollHeight;
        },

        // Show a modal dialog to the user with error details
        showErrorDialog: function(title, details) {
            try {
                // Avoid creating multiple dialogs
                if (document.getElementById('debug-error-modal')) return;
                const overlay = document.createElement('div');
                overlay.id = 'debug-error-modal';
                overlay.style.cssText = `
                    position: fixed; left: 0; top: 0; right: 0; bottom: 0;
                    background: rgba(0,0,0,0.6); z-index: 20000; display: flex; align-items: center; justify-content: center;
                `;

                const box = document.createElement('div');
                box.style.cssText = `
                    width: 640px; max-width: 95%; background: #fff; color: #000; padding: 16px; border-radius: 6px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); font-family: sans-serif; font-size: 13px;
                `;

                const h = document.createElement('div');
                h.style.cssText = 'font-weight: bold; margin-bottom: 8px;';
                h.textContent = title;

                const pre = document.createElement('pre');
                pre.style.cssText = 'white-space: pre-wrap; max-height: 320px; overflow: auto; background: #f6f6f6; padding: 8px; border-radius: 4px;';
                pre.textContent = details;

                const actions = document.createElement('div');
                actions.style.cssText = 'margin-top: 8px; display:flex; gap:8px; justify-content: flex-end;';

                const copyBtn = document.createElement('button');
                copyBtn.textContent = 'Copy details';
                copyBtn.onclick = function() {
                    try { navigator.clipboard.writeText(details); } catch (err) { console.warn('Clipboard copy failed', err); }
                };

                const closeBtn = document.createElement('button');
                closeBtn.textContent = 'Close';
                closeBtn.onclick = function() { document.body.removeChild(overlay); };

                actions.appendChild(copyBtn);
                actions.appendChild(closeBtn);

                box.appendChild(h);
                box.appendChild(pre);
                box.appendChild(actions);
                overlay.appendChild(box);
                document.body.appendChild(overlay);
            } catch (e) {
                console.error('showErrorDialog failed', e);
            }
        },

        // Internal queue for logs that should go to CircuitJS API
        _pendingApiLogs: [],

        // Flush pending logs if CircuitJS API becomes available
        _flushPendingApiLogs: function() {
            try {
                if (typeof window.CircuitJS1 !== 'undefined' && typeof window.CircuitJS1.addLog === 'function') {
                    while (debugConsole._pendingApiLogs.length) {
                        const m = debugConsole._pendingApiLogs.shift();
                        try { window.CircuitJS1.addLog(m); } catch (e) { console.error('flush addLog failed', e); break; }
                    }
                }
            } catch (e) {
                console.error('_flushPendingApiLogs failed', e);
            }
        },

        // Try to log message into the application's JS API (CircuitJS1.addLog)
        logToCircuitApi: function(message) {
            try {
                const entry = `[API_LOG] ${new Date().toISOString()} ${message}`;
                if (typeof window.CircuitJS1 !== 'undefined' && typeof window.CircuitJS1.addLog === 'function') {
                    window.CircuitJS1.addLog(entry);
                } else {
                    // Queue for later flush
                    debugConsole._pendingApiLogs.push(entry);
                    // Also write to console so it appears in console logs
                    console.error('[API_LOG queued] ' + message);
                }
            } catch (e) {
                console.error('logToCircuitApi failed', e);
            }
        }
    };

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', debugConsole.init);
    } else {
        debugConsole.init();
    }

    // Make it globally available
    window.debugConsole = debugConsole;
})();

