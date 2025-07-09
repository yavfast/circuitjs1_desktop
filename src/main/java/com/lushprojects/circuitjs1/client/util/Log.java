package com.lushprojects.circuitjs1.client.util;

import com.lushprojects.circuitjs1.client.CirSim;

public class Log {

    public static void log(String... msg) {
        StringBuilder sb = new StringBuilder(1024);
        for (String s : msg) {
            sb.append(s);
        }
        CirSim.console(sb.toString());
    }
}
