package com.lushprojects.circuitjs1.client;

import com.google.gwt.storage.client.Storage;

public class OptionsManager {

    public static String getOptionFromStorage(String key, String defVal) {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return defVal;
        String s = stor.getItem(key);
        if (s == null)
            return defVal;
        return s;
    }

    public static void setOptionInStorage(String key, String val) {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        stor.setItem(key, val);
    }

    public static void setOptionInStorage(String key, boolean val) {
        setOptionInStorage(key, val ? "true" : "false");
    }

    public static boolean getBoolOptionFromStorage(String key, boolean defVal) {
        String res = getOptionFromStorage(key, null);
        if (res == null) {
            return defVal;
        }
        return res == "true";
    }

}
