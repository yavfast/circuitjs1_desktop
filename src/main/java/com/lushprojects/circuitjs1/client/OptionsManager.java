package com.lushprojects.circuitjs1.client;

import com.google.gwt.storage.client.Storage;

/**
 * Centralized manager for localStorage operations.
 * Provides safe access to browser's localStorage with proper fallbacks.
 */
public class OptionsManager {

    /**
     * Get localStorage instance if supported by browser
     * @return Storage instance or null if not supported
     */
    public static Storage getLocalStorage() {
        return Storage.getLocalStorageIfSupported();
    }

    /**
     * Check if localStorage is supported by the browser
     * @return true if localStorage is available
     */
    public static boolean hasLocalStorage() {
        return Storage.isLocalStorageSupported();
    }

    /**
     * Get string value from localStorage
     * @param key Storage key
     * @param defVal Default value if key not found or localStorage unavailable
     * @return Stored value or default value
     */
    public static String getOptionFromStorage(String key, String defVal) {
        Storage storage = getLocalStorage();
        if (storage != null) {
            String s = storage.getItem(key);
            if (s != null) {
                return s;
            }
        }
        return defVal;
    }

    /**
     * Set string value in localStorage
     * @param key Storage key
     * @param val Value to store
     */
    public static void setOptionInStorage(String key, String val) {
        Storage storage = getLocalStorage();
        if (storage != null) {
            storage.setItem(key, val);
        }
    }

    /**
     * Set boolean value in localStorage
     * @param key Storage key
     * @param val Boolean value to store
     */
    public static void setOptionInStorage(String key, boolean val) {
        setOptionInStorage(key, val ? "true" : "false");
    }

    /**
     * Set integer value in localStorage
     * @param key Storage key
     * @param val Integer value to store
     */
    public static void setOptionInStorage(String key, int val) {
        setOptionInStorage(key, String.valueOf(val));
    }

    /**
     * Set double value in localStorage
     * @param key Storage key
     * @param val Double value to store
     */
    public static void setOptionInStorage(String key, double val) {
        setOptionInStorage(key, String.valueOf(val));
    }

    /**
     * Get boolean value from localStorage
     * @param key Storage key
     * @param defVal Default value if key not found
     * @return Stored boolean value or default value
     */
    public static boolean getBoolOptionFromStorage(String key, boolean defVal) {
        String res = getOptionFromStorage(key, null);
        if (res != null) {
            return "true".equals(res);  // Fixed: Use .equals() instead of ==
        }
        return defVal;
    }

    /**
     * Get integer value from localStorage
     * @param key Storage key
     * @param defVal Default value if key not found or parsing fails
     * @return Stored integer value or default value
     */
    public static int getIntOptionFromStorage(String key, int defVal) {
        String res = getOptionFromStorage(key, null);
        if (res != null) {
            try {
                return Integer.parseInt(res);
            } catch (NumberFormatException e) {
                // Return default if parsing fails
            }
        }
        return defVal;
    }

    /**
     * Get double value from localStorage
     * @param key Storage key
     * @param defVal Default value if key not found or parsing fails
     * @return Stored double value or default value
     */
    public static double getDoubleOptionFromStorage(String key, double defVal) {
        String res = getOptionFromStorage(key, null);
        if (res != null) {
            try {
                return Double.parseDouble(res);
            } catch (NumberFormatException e) {
                // Return default if parsing fails
            }
        }
        return defVal;
    }

    /**
     * Remove key from localStorage
     * @param key Storage key to remove
     */
    public static void removeOptionFromStorage(String key) {
        Storage storage = getLocalStorage();
        if (storage != null) {
            storage.removeItem(key);
        }
    }

    /**
     * Clear all items from localStorage
     */
    public static void clearAllOptions() {
        Storage storage = getLocalStorage();
        if (storage != null) {
            storage.clear();
        }
    }

    /**
     * Get number of items in localStorage
     * @return Number of stored items, 0 if localStorage unavailable
     */
    public static int getStorageLength() {
        Storage storage = getLocalStorage();
        if (storage != null) {
            return storage.getLength();
        }
        return 0;
    }

    /**
     * Get key by index from localStorage
     * @param index Index of the key
     * @return Key at specified index or null
     */
    public static String getStorageKey(int index) {
        Storage storage = getLocalStorage();
        if (storage != null) {
            return storage.key(index);
        }
        return null;
    }

    /**
     * Check if key exists in localStorage
     * @param key Storage key to check
     * @return true if key exists
     */
    public static boolean hasOption(String key) {
        return getOptionFromStorage(key, null) != null;
    }
}
