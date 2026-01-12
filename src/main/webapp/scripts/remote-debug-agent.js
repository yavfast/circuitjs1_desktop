/**
 * CircuitJS1 Remote Debug Agent
 * 
 * Client-side agent that connects to the remote debug server and provides
 * access to CircuitJS1 API for remote debugging, screenshots, and telemetry.
 * 
 * Usage:
 * 1. Script tag with data attribute:
 *    <script data-remote-debug-channel="<uuid>" src="remote-debug-agent.js"></script>
 * 
 * 2. URL parameter:
 *    circuitjs.html?remote-debug=<uuid>
 * 
 * 3. Programmatic:
 *    CircuitRemoteDebug.connect({ channelId: '<uuid>', serverUrl: 'http://localhost:3030' })
 */

(function () {
    'use strict';

    const DEFAULT_SERVER = 'http://localhost:3030';
    const SOCKET_PATH = '/debug';

    // State
    let socket = null;
    let isConnected = false;
    let telemetrySubscriptions = new Map(); // viewerId -> intervalId
    let documentInfoRetryIntervalId = null;
    let config = {
        channelId: null,
        serverUrl: DEFAULT_SERVER,
        autoConnect: true
    };

    /**
     * Initialize configuration from script tag or URL
     */
    function initConfig() {
        // Try script tag data attribute
        const scriptEl = document.currentScript || document.querySelector('script[data-remote-debug-channel]');
        if (scriptEl) {
            const channel = scriptEl.getAttribute('data-remote-debug-channel');
            if (channel) {
                config.channelId = channel;
            }
            const server = scriptEl.getAttribute('data-remote-debug-server');
            if (server) {
                config.serverUrl = server;
            }
        }

        // Try URL parameter (overrides script tag)
        const params = new URLSearchParams(window.location.search);
        const urlChannel = params.get('remote-debug');
        if (urlChannel) {
            config.channelId = urlChannel;
        }

        const urlServer = params.get('remote-debug-server');
        if (urlServer) {
            config.serverUrl = urlServer;
        }
    }

    function scheduleSendDocumentInfo() {
        if (documentInfoRetryIntervalId) {
            clearInterval(documentInfoRetryIntervalId);
            documentInfoRetryIntervalId = null;
        }

        let attempts = 0;
        const maxAttempts = 20;

        documentInfoRetryIntervalId = setInterval(() => {
            attempts++;
            const sent = sendDocumentInfo();
            if (sent || attempts >= maxAttempts) {
                clearInterval(documentInfoRetryIntervalId);
                documentInfoRetryIntervalId = null;
            }
        }, 500);
    }

    /**
     * Load Socket.IO client if not already loaded
     */
    function loadSocketIO() {
        return new Promise((resolve, reject) => {
            if (typeof io !== 'undefined') {
                resolve();
                return;
            }

            const script = document.createElement('script');
            script.src = config.serverUrl + '/debug/socket.io.js';
            script.onload = resolve;
            script.onerror = () => reject(new Error('Failed to load Socket.IO'));
            document.head.appendChild(script);
        });
    }

    /**
     * Connect to debug server
     */
    async function connect(options = {}) {
        if (options.channelId) config.channelId = options.channelId;
        if (options.serverUrl) config.serverUrl = options.serverUrl;

        if (!config.channelId) {
            console.warn('[RemoteDebug] No channel ID configured');
            return false;
        }

        try {
            await loadSocketIO();
        } catch (e) {
            console.error('[RemoteDebug] Failed to load Socket.IO:', e);
            return false;
        }

        if (socket) {
            socket.disconnect();
        }

        socket = io(config.serverUrl, {
            path: SOCKET_PATH,
            query: {
                channelId: config.channelId,
                role: 'agent',
                userAgent: navigator.userAgent,
                url: window.location.href
            }
        });

        setupSocketHandlers();

        console.log('[RemoteDebug] Connecting to', config.serverUrl, 'channel:', config.channelId);
        return true;
    }

    /**
     * Setup socket event handlers
     */
    function setupSocketHandlers() {
        socket.on('connect', () => {
            isConnected = true;
            console.log('[RemoteDebug] Connected');
            scheduleSendDocumentInfo();
        });

        socket.on('disconnect', () => {
            isConnected = false;
            console.log('[RemoteDebug] Disconnected');

            if (documentInfoRetryIntervalId) {
                clearInterval(documentInfoRetryIntervalId);
                documentInfoRetryIntervalId = null;
            }

            // Clear all telemetry subscriptions
            for (const intervalId of telemetrySubscriptions.values()) {
                clearInterval(intervalId);
            }
            telemetrySubscriptions.clear();
        });

        socket.on('registered', (data) => {
            console.log('[RemoteDebug] Registered as agent, session:', data.sessionId);
            scheduleSendDocumentInfo();
        });

        socket.on('error', (data) => {
            console.error('[RemoteDebug] Server error:', data.message);
        });

        // --- Command Handlers ---

        socket.on('execute', handleExecute);
        socket.on('screenshot', handleScreenshot);
        socket.on('circuit_export', handleCircuitExport);
        socket.on('sim_control', handleSimControl);
        socket.on('sim_info', handleSimInfo);
        socket.on('telemetry_subscribe', handleTelemetrySubscribe);
        socket.on('telemetry_unsubscribe', handleTelemetryUnsubscribe);
        socket.on('element_update', handleElementUpdate);

        // Setup console interception after connection
        setupConsoleInterception();

        // Send initial document info (may require retries until CircuitJS1 is ready)
        scheduleSendDocumentInfo();
    }

    // --- Console Interception ---

    const originalConsole = {
        log: console.log.bind(console),
        warn: console.warn.bind(console),
        error: console.error.bind(console),
        info: console.info.bind(console)
    };

    let consoleIntercepted = false;

    /**
     * Setup console log/warn/error interception
     */
    function setupConsoleInterception() {
        if (consoleIntercepted) return;
        consoleIntercepted = true;

        const wrapConsole = (type) => {
            return function (...args) {
                // Call original
                originalConsole[type](...args);

                // Send to debug server if connected
                if (socket && isConnected) {
                    try {
                        const message = args.map(arg => {
                            if (typeof arg === 'object') {
                                try {
                                    return JSON.stringify(arg, null, 2);
                                } catch (e) {
                                    return String(arg);
                                }
                            }
                            return String(arg);
                        }).join(' ');

                        socket.emit('console_log', {
                            type: type,
                            message: message,
                            timestamp: Date.now(),
                            url: window.location.href
                        });
                    } catch (e) {
                        // Avoid infinite loops
                    }
                }
            };
        };

        console.log = wrapConsole('log');
        console.warn = wrapConsole('warn');
        console.error = wrapConsole('error');
        console.info = wrapConsole('info');

        // Also capture uncaught errors
        window.addEventListener('error', (event) => {
            if (socket && isConnected) {
                socket.emit('console_log', {
                    type: 'error',
                    message: `Uncaught ${event.error?.name || 'Error'}: ${event.message} at ${event.filename}:${event.lineno}:${event.colno}`,
                    timestamp: Date.now(),
                    url: window.location.href,
                    stack: event.error?.stack
                });
            }
        });

        // Capture unhandled promise rejections
        window.addEventListener('unhandledrejection', (event) => {
            if (socket && isConnected) {
                socket.emit('console_log', {
                    type: 'error',
                    message: `Unhandled Promise Rejection: ${event.reason}`,
                    timestamp: Date.now(),
                    url: window.location.href
                });
            }
        });
    }

    // --- Document Events ---

    /**
     * Send current document info to server
     */
    function sendDocumentInfo() {
        if (!socket || !isConnected) return false;

        try {
            const hasCircuitApi = (typeof CircuitJS1 !== 'undefined');

            const docId = (hasCircuitApi && CircuitJS1.getActiveDocumentId)
                ? CircuitJS1.getActiveDocumentId()
                : 'default';

            const docName = (hasCircuitApi && CircuitJS1.getActiveDocumentName)
                ? (CircuitJS1.getActiveDocumentName() || 'Untitled')
                : 'CircuitJS1';

            const tabCount = (hasCircuitApi && CircuitJS1.getDocumentCount)
                ? CircuitJS1.getDocumentCount()
                : 1;

            socket.emit('document_info', {
                docId,
                docName,
                tabCount,
                timestamp: Date.now()
            });

            return true;
        } catch (e) {
            // CircuitJS1 API not ready yet
        }

        return false;
    }

    /**
     * Notify about document change (tab switch, circuit load, etc.)
     */
    function notifyDocumentChange(event, data = {}) {
        if (!socket || !isConnected) return;

        socket.emit('document_event', {
            event: event,
            ...data,
            timestamp: Date.now()
        });
    }

    /**
     * Send log message for specific document
     */
    function logToDocument(docId, type, message) {
        if (!socket || !isConnected) return;

        socket.emit('document_log', {
            docId: docId,
            docName: '', // Will be filled by server if needed
            type: type,
            message: message,
            timestamp: Date.now()
        });
    }

    /**
     * Execute JavaScript command
     */
    function handleExecute(data) {
        const { id, command, viewerId } = data;

        try {
            // Execute in global context with access to CircuitJS1
            const result = eval(command);

            // Serialize result
            let serializedResult;
            try {
                if (result instanceof HTMLElement) {
                    serializedResult = new XMLSerializer().serializeToString(result);
                } else if (result === undefined) {
                    serializedResult = 'undefined';
                } else {
                    serializedResult = JSON.stringify(result, null, 2);
                }
            } catch (e) {
                serializedResult = String(result);
            }

            socket.emit('execute_result', {
                id,
                viewerId,
                success: true,
                result: serializedResult
            });
        } catch (error) {
            socket.emit('execute_result', {
                id,
                viewerId,
                success: false,
                error: error.message || String(error)
            });
        }
    }

    /**
     * Capture circuit screenshot
     */
    function handleScreenshot(data) {
        const { id, type, viewerId } = data;

        try {
            let image;

            if (type === 'svg') {
                // Use CircuitJS1 SVG export
                if (typeof CircuitJS1 !== 'undefined' && CircuitJS1.getCircuitAsSVG) {
                    const svg = CircuitJS1.getCircuitAsSVG();
                    image = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
                } else {
                    throw new Error('CircuitJS1 API not available');
                }
            } else {
                // Capture canvas as PNG
                const canvas = document.querySelector('canvas');
                if (!canvas) {
                    throw new Error('Canvas element not found');
                }
                image = canvas.toDataURL('image/png');
            }

            socket.emit('screenshot_result', {
                id,
                viewerId,
                success: true,
                image,
                type
            });
        } catch (error) {
            socket.emit('screenshot_result', {
                id,
                viewerId,
                success: false,
                error: error.message
            });
        }
    }

    /**
     * Export circuit data
     */
    function handleCircuitExport(data) {
        const { id, withState, viewerId } = data;

        try {
            if (typeof CircuitJS1 === 'undefined') {
                throw new Error('CircuitJS1 API not available');
            }

            let circuit;
            if (withState && CircuitJS1.exportAsJsonWithState) {
                circuit = CircuitJS1.exportAsJsonWithState();
            } else {
                circuit = CircuitJS1.exportAsJson();
            }

            socket.emit('circuit_export_result', {
                id,
                viewerId,
                success: true,
                circuit: JSON.parse(circuit),
                simInfo: CircuitJS1.getSimInfo ? CircuitJS1.getSimInfo() : null
            });
        } catch (error) {
            socket.emit('circuit_export_result', {
                id,
                viewerId,
                success: false,
                error: error.message
            });
        }
    }

    /**
     * Simulation control
     */
    function handleSimControl(data) {
        const { action, viewerId } = data;

        try {
            if (typeof CircuitJS1 === 'undefined') {
                throw new Error('CircuitJS1 API not available');
            }

            switch (action) {
                case 'start':
                    CircuitJS1.setSimRunning(true);
                    break;
                case 'stop':
                    CircuitJS1.setSimRunning(false);
                    break;
                case 'step':
                    CircuitJS1.setSimRunning(false);
                    CircuitJS1.stepSimulation();
                    break;
                case 'reset':
                    CircuitJS1.resetSimulation();
                    break;
                default:
                    console.warn('[RemoteDebug] Unknown sim_control action:', action);
            }
        } catch (error) {
            console.error('[RemoteDebug] sim_control error:', error);
        }
    }

    /**
     * Get simulation info
     */
    function handleSimInfo(data) {
        const { id, viewerId } = data;

        try {
            if (typeof CircuitJS1 === 'undefined') {
                throw new Error('CircuitJS1 API not available');
            }

            const info = CircuitJS1.getSimInfo();

            socket.emit('sim_info_result', {
                id,
                viewerId,
                success: true,
                info
            });
        } catch (error) {
            socket.emit('sim_info_result', {
                id,
                viewerId,
                success: false,
                error: error.message
            });
        }
    }

    /**
     * Subscribe to telemetry streaming
     */
    function handleTelemetrySubscribe(data) {
        const { viewerId, elements, interval, fields } = data;

        // Clear existing subscription for this viewer
        if (telemetrySubscriptions.has(viewerId)) {
            clearInterval(telemetrySubscriptions.get(viewerId));
        }

        const sendTelemetry = () => {
            try {
                if (typeof CircuitJS1 === 'undefined') return;

                const telemetryData = {
                    time: CircuitJS1.getTime(),
                    elements: {}
                };

                for (const elementId of elements) {
                    const elm = CircuitJS1.getElementById(elementId);
                    if (elm) {
                        const elmData = {};
                        if (fields.includes('voltage')) {
                            elmData.voltage = elm.getVoltageDiff();
                        }
                        if (fields.includes('current')) {
                            elmData.current = elm.getCurrent();
                        }
                        if (fields.includes('power')) {
                            elmData.power = elm.getPower();
                        }
                        telemetryData.elements[elementId] = elmData;
                    }
                }

                socket.emit('telemetry', {
                    viewerId,
                    ...telemetryData
                });
            } catch (error) {
                console.error('[RemoteDebug] Telemetry error:', error);
            }
        };

        const intervalId = setInterval(sendTelemetry, interval);
        telemetrySubscriptions.set(viewerId, intervalId);

        // Send initial data immediately
        sendTelemetry();
    }

    /**
     * Unsubscribe from telemetry
     */
    function handleTelemetryUnsubscribe(data) {
        const { viewerId } = data;

        if (telemetrySubscriptions.has(viewerId)) {
            clearInterval(telemetrySubscriptions.get(viewerId));
            telemetrySubscriptions.delete(viewerId);
        }
    }

    /**
     * Update element property
     */
    function handleElementUpdate(data) {
        const { id, elementId, property, value, viewerId } = data;

        try {
            if (typeof CircuitJS1 === 'undefined') {
                throw new Error('CircuitJS1 API not available');
            }

            const success = CircuitJS1.setElementProperty(elementId, property, value);

            socket.emit('element_update_result', {
                id,
                viewerId,
                success,
                elementId,
                property,
                value
            });
        } catch (error) {
            socket.emit('element_update_result', {
                id,
                viewerId,
                success: false,
                error: error.message
            });
        }
    }

    /**
     * Disconnect from server
     */
    function disconnect() {
        if (socket) {
            socket.disconnect();
            socket = null;
        }
        isConnected = false;
    }

    // Expose public API
    window.CircuitRemoteDebug = {
        connect,
        disconnect,
        isConnected: () => isConnected,
        getConfig: () => ({ ...config }),
        getSocket: () => socket,
        notifyDocumentChange,
        logToDocument,
        sendDocumentInfo
    };

    // Auto-initialize
    initConfig();

    if (config.channelId && config.autoConnect) {
        // Wait for page load to ensure CircuitJS1 might be available
        if (document.readyState === 'complete') {
            connect();
        } else {
            window.addEventListener('load', () => connect());
        }
    }

})();
