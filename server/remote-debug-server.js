/**
 * CircuitJS1 Remote Debug Server
 * 
 * WebSocket-based debug server for remote control and diagnostics of CircuitJS1.
 * Provides channel-based sessions where multiple viewers can connect to a single agent.
 */

const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');
const path = require('path');
const { v4: uuidv4 } = require('uuid');

const PORT = process.env.PORT || 3030;
const CORS_ORIGIN = process.env.CORS_ORIGIN || '*';

const app = express();
const httpServer = createServer(app);

const io = new Server(httpServer, {
    cors: {
        origin: CORS_ORIGIN,
        methods: ['GET', 'POST']
    },
    path: '/debug'
});

// Serve static files for web viewer
app.use(express.static(path.join(__dirname, 'public')));

// Channel sessions storage
// channelId -> { agent: socket, viewers: Set<socket>, created: Date }
const channels = new Map();

/**
 * Get or create a channel
 */
function getChannel(channelId) {
    if (!channels.has(channelId)) {
        channels.set(channelId, {
            agent: null,
            viewers: new Set(),
            created: new Date(),
            lastActivity: new Date(),
            documentInfo: null
        });
    }
    return channels.get(channelId);
}

/**
 * Clean up empty channels periodically
 */
function cleanupChannels() {
    const now = Date.now();
    const MAX_IDLE = 30 * 60 * 1000; // 30 minutes

    for (const [channelId, channel] of channels.entries()) {
        if (!channel.agent && channel.viewers.size === 0) {
            if (now - channel.lastActivity.getTime() > MAX_IDLE) {
                channels.delete(channelId);
                console.log(`[cleanup] Removed idle channel: ${channelId}`);
            }
        }
    }
}

setInterval(cleanupChannels, 5 * 60 * 1000); // Every 5 minutes

// Socket.IO connection handling
io.on('connection', (socket) => {
    const { channelId, role } = socket.handshake.query;

    if (!channelId) {
        socket.emit('error', { message: 'Missing channelId' });
        socket.disconnect();
        return;
    }

    const channel = getChannel(channelId);
    socket.channelId = channelId;
    socket.role = role || 'viewer';

    console.log(`[connect] ${socket.role} joined channel ${channelId} (${socket.id})`);

    // Join channel room
    socket.join(channelId);

    if (socket.role === 'agent') {
        // Agent connection
        if (channel.agent) {
            // Disconnect old agent
            channel.agent.emit('error', { message: 'Another agent connected' });
            channel.agent.disconnect();
        }
        channel.agent = socket;
        socket.emit('registered', { sessionId: socket.id, channelId });

        // Notify viewers that agent is connected
        socket.to(channelId).emit('agent_status', { connected: true });

    } else {
        // Viewer connection
        channel.viewers.add(socket);
        socket.emit('registered', {
            sessionId: socket.id,
            channelId,
            agentConnected: !!channel.agent
        });

        // If the agent already reported document metadata, replay it to late-joining viewers.
        if (channel.documentInfo) {
            socket.emit('document_info', channel.documentInfo);
        }
    }

    channel.lastActivity = new Date();

    // --- Message Handlers ---

    /**
     * Execute JavaScript command on agent
     * Viewer -> Agent
     */
    socket.on('execute', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) {
            socket.emit('execute_result', {
                id: data.id,
                success: false,
                error: 'No agent connected'
            });
            return;
        }

        ch.agent.emit('execute', {
            id: data.id || uuidv4(),
            command: data.command,
            viewerId: socket.id
        });
        ch.lastActivity = new Date();
    });

    /**
     * Execute result from agent
     * Agent -> Viewer
     */
    socket.on('execute_result', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            // Send to specific viewer
            io.to(data.viewerId).emit('execute_result', data);
        } else {
            // Broadcast to all viewers in channel
            socket.to(socket.channelId).emit('execute_result', data);
        }
    });

    /**
     * Request screenshot
     * Viewer -> Agent
     */
    socket.on('screenshot', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) {
            socket.emit('screenshot_result', {
                id: data?.id,
                success: false,
                error: 'No agent connected'
            });
            return;
        }

        ch.agent.emit('screenshot', {
            id: data?.id || uuidv4(),
            type: data?.type || 'png', // png or svg
            viewerId: socket.id
        });
        ch.lastActivity = new Date();
    });

    /**
     * Screenshot result from agent
     * Agent -> Viewer
     */
    socket.on('screenshot_result', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            io.to(data.viewerId).emit('screenshot_result', data);
        } else {
            socket.to(socket.channelId).emit('screenshot_result', data);
        }
    });

    /**
     * Request circuit export
     * Viewer -> Agent
     */
    socket.on('circuit_export', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) {
            socket.emit('circuit_export_result', {
                id: data?.id,
                success: false,
                error: 'No agent connected'
            });
            return;
        }

        ch.agent.emit('circuit_export', {
            id: data?.id || uuidv4(),
            withState: data?.withState ?? true,
            viewerId: socket.id
        });
    });

    /**
     * Circuit export result from agent
     * Agent -> Viewer
     */
    socket.on('circuit_export_result', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            io.to(data.viewerId).emit('circuit_export_result', data);
        } else {
            socket.to(socket.channelId).emit('circuit_export_result', data);
        }
    });

    /**
     * Simulation control
     * Viewer -> Agent
     */
    socket.on('sim_control', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) return;

        ch.agent.emit('sim_control', {
            action: data.action, // start, stop, step, reset
            viewerId: socket.id
        });
    });

    /**
     * Telemetry subscription
     * Viewer -> Agent
     */
    socket.on('telemetry_subscribe', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) return;

        ch.agent.emit('telemetry_subscribe', {
            viewerId: socket.id,
            elements: data.elements || [],
            interval: data.interval || 100,
            fields: data.fields || ['voltage', 'current']
        });
    });

    socket.on('telemetry_unsubscribe', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) return;

        ch.agent.emit('telemetry_unsubscribe', { viewerId: socket.id });
    });

    /**
     * Telemetry data broadcast
     * Agent -> Viewers
     */
    socket.on('telemetry', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            io.to(data.viewerId).emit('telemetry', data);
        } else {
            socket.to(socket.channelId).emit('telemetry', data);
        }
    });

    /**
     * Element update
     * Viewer -> Agent
     */
    socket.on('element_update', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) return;

        ch.agent.emit('element_update', {
            id: data.id || uuidv4(),
            elementId: data.elementId,
            property: data.property,
            value: data.value,
            viewerId: socket.id
        });
    });

    socket.on('element_update_result', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            io.to(data.viewerId).emit('element_update_result', data);
        } else {
            socket.to(socket.channelId).emit('element_update_result', data);
        }
    });

    /**
     * Get simulation info
     * Viewer -> Agent
     */
    socket.on('sim_info', (data) => {
        if (socket.role !== 'viewer') return;

        const ch = channels.get(socket.channelId);
        if (!ch?.agent) {
            socket.emit('sim_info_result', { success: false, error: 'No agent connected' });
            return;
        }

        ch.agent.emit('sim_info', { id: data?.id, viewerId: socket.id });
    });

    socket.on('sim_info_result', (data) => {
        if (socket.role !== 'agent') return;

        if (data.viewerId) {
            io.to(data.viewerId).emit('sim_info_result', data);
        } else {
            socket.to(socket.channelId).emit('sim_info_result', data);
        }
    });

    // --- Console & Document Events from Agent ---

    /**
     * Console log from agent
     * Agent -> Viewers
     */
    socket.on('console_log', (data) => {
        if (socket.role !== 'agent') return;

        // Broadcast to all viewers in channel
        socket.to(socket.channelId).emit('console_log', {
            ...data,
            agentId: socket.id
        });
    });

    /**
     * Document info from agent
     * Agent -> Viewers
     */
    socket.on('document_info', (data) => {
        if (socket.role !== 'agent') return;

        const ch = channels.get(socket.channelId);
        if (ch) {
            ch.documentInfo = data;
            ch.lastActivity = new Date();
        }

        // Broadcast to all viewers
        socket.to(socket.channelId).emit('document_info', data);
    });

    /**
     * Document event from agent (tab switch, circuit load, etc.)
     * Agent -> Viewers
     */
    socket.on('document_event', (data) => {
        if (socket.role !== 'agent') return;

        socket.to(socket.channelId).emit('document_event', data);
    });

    /**
     * Document-specific log from agent
     * Agent -> Viewers
     */
    socket.on('document_log', (data) => {
        if (socket.role !== 'agent') return;

        socket.to(socket.channelId).emit('document_log', data);
    });

    // --- Disconnect Handling ---

    socket.on('disconnect', () => {
        console.log(`[disconnect] ${socket.role} left channel ${socket.channelId} (${socket.id})`);

        const ch = channels.get(socket.channelId);
        if (!ch) return;

        if (socket.role === 'agent') {
            if (ch.agent === socket) {
                ch.agent = null;
                // Notify viewers
                socket.to(socket.channelId).emit('agent_status', { connected: false });
            }
        } else {
            ch.viewers.delete(socket);
        }

        ch.lastActivity = new Date();
    });
});

// API endpoint to create new channel
app.get('/api/channel/new', (req, res) => {
    const channelId = uuidv4();
    getChannel(channelId);
    res.json({ channelId, viewerUrl: `/?channel=${channelId}` });
});

// API endpoint to list active channels (for debugging)
app.get('/api/channels', (req, res) => {
    const list = [];
    for (const [channelId, channel] of channels.entries()) {
        list.push({
            channelId,
            hasAgent: !!channel.agent,
            viewerCount: channel.viewers.size,
            created: channel.created,
            lastActivity: channel.lastActivity
        });
    }
    res.json(list);
});

// Start server
httpServer.listen(PORT, () => {
    console.log(`CircuitJS1 Remote Debug Server running on http://localhost:${PORT}`);
    console.log(`Web viewer: http://localhost:${PORT}/?channel=<your-channel-id>`);
});
