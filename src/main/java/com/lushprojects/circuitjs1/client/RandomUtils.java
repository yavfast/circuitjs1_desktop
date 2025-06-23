package com.lushprojects.circuitjs1.client;

import java.util.Random;

public class RandomUtils {

    private final static Random random = new Random();

    public static int getRand(int x) {
        int q = random.nextInt();
        if (q < 0) {
            q = -q;
        }
        return q % x;
    }

    public static Random getRandom() {
        return random;
    }
}
