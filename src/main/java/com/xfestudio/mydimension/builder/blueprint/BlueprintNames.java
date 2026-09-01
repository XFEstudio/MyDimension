package com.xfestudio.mydimension.builder.blueprint;

import java.text.Normalizer;
import java.util.Locale;

public final class BlueprintNames {
    private static final String FORBIDDEN = "\\/:*?\"<>|";

    private BlueprintNames() {
    }

    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Blueprint name is missing");
        }
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > BlueprintLimits.MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Blueprint name must contain 1-"
                    + BlueprintLimits.MAX_NAME_CODE_POINTS + " characters");
        }
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Blueprint name is reserved");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) || FORBIDDEN.indexOf(codePoint) >= 0) {
                throw new IllegalArgumentException("Blueprint name contains a forbidden character");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    public static String collisionKey(String input) {
        return normalize(input).toLowerCase(Locale.ROOT);
    }
}
