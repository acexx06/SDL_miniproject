package com.hms.components.idgen;

import java.util.Random;

/** Base-62 text encoding. The alphabet is in ASCII order, so fixed-width encodings sort like the numbers do. */
public final class Base62 {

    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Base62() {
    }

    public static String encode(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        StringBuilder sb = new StringBuilder(Math.max(width, 11));
        do {
            sb.append(ALPHABET.charAt((int) (value % 62)));
            value /= 62;
        } while (value > 0);
        while (sb.length() < width) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }

    public static long decode(String text) {
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a base-62 string: " + text);
            }
            value = value * 62 + digit;
        }
        return value;
    }

    public static String random(Random rng, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET.charAt(rng.nextInt(62));
        }
        return new String(chars);
    }

    public static boolean isValid(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (ALPHABET.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
