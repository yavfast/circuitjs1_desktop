// Errors of target resolution and launch (SP_MCB_02_02, SP_MCB_03). The message is the
// user-facing text of the spec; `code` lets the stdio server and the CLI map the error to an
// `isError` tool result or an exit code without parsing the text.

/**
 * @typedef {'unreachable'|'unknown_instance'|'no_app'|'app_not_found'|'app_spawn_failed'|'launch_timeout'} BridgeErrorCode
 */

export class BridgeError extends Error {
  /**
   * @param {BridgeErrorCode} code
   * @param {string} message  spec text, shown to the agent or on stderr as is
   */
  constructor(code, message) {
    super(message);
    this.name = 'BridgeError';
    this.code = code;
  }
}
