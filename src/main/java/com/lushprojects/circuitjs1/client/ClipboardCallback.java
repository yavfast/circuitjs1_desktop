package com.lushprojects.circuitjs1.client;

/**
 * Callback interface for asynchronous clipboard operations
 */
public interface ClipboardCallback {
    /**
     * Called when clipboard read operation succeeds
     * @param data The clipboard data
     */
    void onSuccess(String data);

    /**
     * Called when clipboard operation fails
     * @param error Error message
     */
    void onError(String error);
}
