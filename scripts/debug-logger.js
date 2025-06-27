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
                debugConsole.writeToOutput('UNHANDLED_ERROR', [e.message, e.filename, e.lineno, e.colno]);
            });

            // Catch unhandled promise rejections
            window.addEventListener('unhandledrejection', function(e) {
                debugConsole.writeToOutput('UNHANDLED_REJECTION', [e.reason]);
            });

            console.log('Debug logging initialized for CircuitJS1');
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

