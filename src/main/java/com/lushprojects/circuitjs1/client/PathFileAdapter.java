package com.lushprojects.circuitjs1.client;

import com.google.gwt.core.client.JavaScriptObject;

/**
 * [SP_AGA_03_09] Path-based file access through the desktop runtime's Node file system
 * ({@code nw.require('fs')}), the file-bridge adapter beside {@link LoadFile} (RULE_ARCH_008: the
 * JSNI stays in this one class). It is the mechanism only — which paths and contents are allowed
 * is decided by the caller (the agent's {@code FileOps}).
 * <ul>
 * <li>Availability is detected on every call: in a plain browser build (no {@code nw.require} and
 *     no Node {@code require}/{@code process}) every operation returns {@link Status#UNAVAILABLE}.</li>
 * <li>Paths are normalised with {@code path.resolve}. An existing file is followed through
 *     symbolic links ({@code fs.realpathSync}); its resolved path is reported, so the caller can
 *     apply its rules to the real file too. A link whose target is missing is an error.</li>
 * <li>{@link #stat} and {@link #read} report a missing file (also a missing directory on the path)
 *     as {@link Status#NOT_FOUND} and anything but a regular file as {@link Status#NOT_REGULAR}
 *     (a directory, a device or a FIFO is never opened).</li>
 * <li>{@link #read} reads at most {@code maxBytes} bytes: a larger file is
 *     {@link Status#TOO_LARGE}. The content is decoded as UTF-8 and a leading byte-order mark is
 *     dropped.</li>
 * <li>{@link #write} writes to a staging file in the target's directory (exclusive create, the
 *     existing file's permission bits, flushed with {@code fsync}) and renames it over the target,
 *     so the target is replaced whole or not at all; a symbolic link keeps pointing at the
 *     replaced file. It refuses a target that resolves differently from the one the caller
 *     checked, removes only a staging file it created, and never creates parent directories.</li>
 * <li>Any other file-system failure (permissions, a full disk, a failed rename) is
 *     {@link Status#ERROR} with the system reason.</li>
 * </ul>
 * All calls are synchronous (Node's {@code *Sync} functions).
 */
public final class PathFileAdapter {

    public enum Status {
        OK, UNAVAILABLE, NOT_FOUND, NOT_REGULAR, TOO_LARGE, ERROR
    }

    /** Outcome of one call. */
    public static final class Result {
        public final Status status;
        /** File content ({@link #read}), else null. */
        public final String text;
        /** The requested path, normalised; null when unavailable. */
        public final String path;
        /** The path with symbolic links resolved (existing files), else {@link #path}. */
        public final String realPath;
        /** System reason of a failure, else null. */
        public final String reason;
        /** File size in bytes ({@link #stat}, {@link #read}); bytes written ({@link #write}). */
        public final double size;

        Result(Status status, String text, String path, String realPath, String reason, double size) {
            this.status = status;
            this.text = text;
            this.path = path;
            this.realPath = realPath;
            this.reason = reason;
            this.size = size;
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    private PathFileAdapter() {
    }

    // Called from JSNI: status as its ordinal
    private static Result result(int status, String text, String path, String realPath, String reason, double size) {
        return new Result(Status.values()[status], text, path, realPath, reason, size);
    }

    /** @return true when the desktop runtime's Node file system is reachable */
    public static native boolean isAvailable() /*-{
        return @com.lushprojects.circuitjs1.client.PathFileAdapter::node()() != null;
    }-*/;

    /** @return true when {@code path} is absolute by the platform's rules; false when unavailable */
    public static native boolean isAbsolute(String path) /*-{
        var n = @com.lushprojects.circuitjs1.client.PathFileAdapter::node()();
        return n != null && typeof path === 'string' && n.path.isAbsolute(path);
    }-*/;

    /** @return the last component of {@code path} (platform separators); the path itself when unavailable */
    public static native String baseName(String path) /*-{
        var n = @com.lushprojects.circuitjs1.client.PathFileAdapter::node()();
        return n == null ? path : n.path.basename(path);
    }-*/;

    /**
     * Inspects {@code path}: {@link Status#OK} for an existing regular file (size, real path),
     * {@link Status#NOT_FOUND}, {@link Status#NOT_REGULAR}, or {@link Status#ERROR}.
     */
    public static native Result stat(String path) /*-{
        var n = @com.lushprojects.circuitjs1.client.PathFileAdapter::node()();
        var R = function(status, text, p, real, reason, size) {
            return @com.lushprojects.circuitjs1.client.PathFileAdapter::result(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;D)(status, text, p, real, reason, size);
        };
        if (n == null) return R(1, null, null, null, null, 0);
        var s = @com.lushprojects.circuitjs1.client.PathFileAdapter::statNative(*)(n, path);
        return R(s.status, null, s.path, s.real, s.reason, s.size);
    }-*/;

    /**
     * Reads {@code path} as UTF-8 text, at most {@code maxBytes} bytes; a leading byte-order mark
     * is dropped.
     */
    public static native Result read(String path, double maxBytes) /*-{
        var n = @com.lushprojects.circuitjs1.client.PathFileAdapter::node()();
        var R = function(status, text, p, real, reason, size) {
            return @com.lushprojects.circuitjs1.client.PathFileAdapter::result(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;D)(status, text, p, real, reason, size);
        };
        if (n == null) return R(1, null, null, null, null, 0);
        var s = @com.lushprojects.circuitjs1.client.PathFileAdapter::statNative(*)(n, path);
        if (s.status != 0) return R(s.status, null, s.path, s.real, s.reason, s.size);
        if (s.size > maxBytes) return R(4, null, s.path, s.real, 'the file is larger than ' + maxBytes + ' bytes', s.size);
        var buf;
        try {
            buf = n.fs.readFileSync(s.real);
        } catch (e) {
            return R(5, null, s.path, s.real, @com.lushprojects.circuitjs1.client.PathFileAdapter::reasonOf(*)(e), s.size);
        }
        // the file may have grown since the stat
        if (buf.length > maxBytes) return R(4, null, s.path, s.real, 'the file is larger than ' + maxBytes + ' bytes', buf.length);
        var text = buf.toString('utf8');
        if (text.charCodeAt(0) === 0xFEFF) text = text.substring(1);
        return R(0, text, s.path, s.real, null, buf.length);
    }-*/;

    /**
     * Writes {@code content} as UTF-8 to {@code path} through a staging file in the same directory:
     * created exclusively ({@code wx}), written, flushed ({@code fsync}) and closed, then renamed
     * over the target. An existing target is followed through symbolic links and keeps its
     * permission bits. On any failure only a staging file this call created is removed.
     * <p>
     * {@code expectedRealPath} is the target the caller checked ({@link #stat}): the resolved path
     * of the existing file, or null when no file existed. When the target now resolves
     * differently (created, removed or re-linked since the check), nothing is written and the
     * result is {@link Status#ERROR}.
     * The size of the result is the number of bytes written.
     */
    public static native Result write(String path, String content, String expectedRealPath) /*-{
        var n = @com.lushprojects.circuitjs1.client.PathFileAdapter::node()();
        var R = function(status, text, p, real, reason, size) {
            return @com.lushprojects.circuitjs1.client.PathFileAdapter::result(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;D)(status, text, p, real, reason, size);
        };
        var reasonOf = function(e) {
            return @com.lushprojects.circuitjs1.client.PathFileAdapter::reasonOf(*)(e);
        };
        if (n == null) return R(1, null, null, null, null, 0);
        var fs = n.fs, np = n.path;
        var p0 = np.resolve(path);
        var target = p0, mode;
        var s = @com.lushprojects.circuitjs1.client.PathFileAdapter::statNative(*)(n, path);
        if (s.status == 0) {
            target = s.real;
            try { mode = fs.statSync(target).mode & 4095; } catch (e) { mode = undefined; }
        } else if (s.status == 3) {
            return R(3, null, s.path, s.real, s.reason, 0);
        } else if (s.status != 2) {
            return R(5, null, s.path, s.real, s.reason, 0);
        } else {
            // a dangling symbolic link: lstat finds it although the target is missing
            var link = false;
            try { link = fs.lstatSync(p0).isSymbolicLink(); } catch (e) { link = false; }
            if (link) return R(5, null, p0, p0, 'the symbolic link points to a missing file', 0);
        }
        // the target must still be the one the caller checked
        var nowReal = s.status == 0 ? target : null;
        if (nowReal !== (expectedRealPath == null ? null : expectedRealPath)) {
            return R(5, null, p0, target, 'the target changed after it was checked ('
                + (expectedRealPath == null ? 'no file' : expectedRealPath) + ' -> ' + (nowReal == null ? 'no file' : nowReal) + ')', 0);
        }
        var dir = np.dirname(target);
        var staging = np.join(dir, '.' + np.basename(target) + '.' + Date.now().toString(36)
            + Math.floor(Math.random() * 1e9).toString(36) + '.tmp');
        var buf = n.Buffer.from(content, 'utf8');
        var fd = null, created = false;
        try {
            fd = fs.openSync(staging, 'wx', mode === undefined ? 438 : mode);
            created = true;
            if (mode !== undefined) {
                try { fs.fchmodSync(fd, mode); } catch (e) { }
            }
            var off = 0;
            while (off < buf.length) {
                off += fs.writeSync(fd, buf, off, buf.length - off);
            }
            fs.fsyncSync(fd);
            fs.closeSync(fd);
            fd = null;
            fs.renameSync(staging, target);
        } catch (e) {
            if (fd != null) {
                try { fs.closeSync(fd); } catch (e2) { }
            }
            // remove only a staging file this call created (an EEXIST from 'wx' is not ours)
            if (created) {
                try { fs.unlinkSync(staging); } catch (e2) { }
            }
            if (!created && e && (e.code === 'ENOENT' || e.code === 'ENOTDIR')) {
                return R(5, null, p0, target, 'the directory ' + dir + ' does not exist (' + reasonOf(e) + ')', 0);
            }
            return R(5, null, p0, target, reasonOf(e), 0);
        }
        return R(0, null, p0, target, null, buf.length);
    }-*/;

    /**
     * @return {fs, path, Buffer} of the desktop runtime, or null in a plain browser (no NW.js
     *         {@code nw.require}, no Node {@code require} with {@code process})
     */
    private static native JavaScriptObject node() /*-{
        try {
            var req = null;
            if ($wnd.nw && typeof $wnd.nw.require === 'function') {
                req = function(m) { return $wnd.nw.require(m); };
            } else if (typeof $wnd.require === 'function' && typeof $wnd.process === 'object') {
                req = function(m) { return $wnd.require(m); };
            }
            if (req == null) return null;
            var fs = req('fs'), path = req('path'), buffer = req('buffer');
            if (!fs || !path || !buffer || typeof fs.readFileSync !== 'function' || typeof path.resolve !== 'function') return null;
            return { fs: fs, path: path, Buffer: buffer.Buffer };
        } catch (e) {
            return null;
        }
    }-*/;

    /**
     * Stat of a path as a JS object {status, path, real, reason, size}; status as
     * {@link Status} ordinal (0 OK, 2 NOT_FOUND, 3 NOT_REGULAR, 5 ERROR).
     */
    private static native JavaScriptObject statNative(JavaScriptObject n, String path) /*-{
        var fs = n.fs, np = n.path;
        var p0 = np.resolve(path);
        var reasonOf = function(e) {
            return @com.lushprojects.circuitjs1.client.PathFileAdapter::reasonOf(*)(e);
        };
        var real, st;
        try {
            real = fs.realpathSync(p0);
            st = fs.statSync(real);
        } catch (e) {
            if (e && (e.code === 'ENOENT' || e.code === 'ENOTDIR')) {
                return { status: 2, path: p0, real: p0, reason: reasonOf(e), size: 0 };
            }
            return { status: 5, path: p0, real: p0, reason: reasonOf(e), size: 0 };
        }
        if (!st.isFile()) {
            return { status: 3, path: p0, real: real, reason: 'not a regular file', size: 0 };
        }
        return { status: 0, path: p0, real: real, reason: null, size: st.size };
    }-*/;

    /** @return the system reason of a Node error: its message (which names the code), else its string form */
    private static native String reasonOf(JavaScriptObject e) /*-{
        if (e == null) return 'unknown error';
        if (e.message) return String(e.message);
        return String(e);
    }-*/;
}
