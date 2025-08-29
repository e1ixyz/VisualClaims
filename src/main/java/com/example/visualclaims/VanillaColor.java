package com.example.visualclaims;

import java.util.Locale;

public enum VanillaColor {
    BLACK(0x000000),
    DARK_BLUE(0x0000AA),
    DARK_GREEN(0x00AA00),
    DARK_AQUA(0x00AAAA),
    DARK_RED(0xAA0000),
    DARK_PURPLE(0xAA00AA),
    GOLD(0xFFAA00),
    GRAY(0xAAAAAA),
    DARK_GRAY(0x555555),
    BLUE(0x5555FF),
    GREEN(0x55FF55),
    AQUA(0x55FFFF),
    RED(0xFF5555),
    LIGHT_PURPLE(0xFF55FF),
    YELLOW(0xFFFF55),
    WHITE(0xFFFFFF);

    public final int rgb;
    VanillaColor(int rgb) { this.rgb = rgb; }

    public static VanillaColor fromString(String s) {
        if (s == null) return null;
        try {
            return VanillaColor.valueOf(s.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (Exception ex) {
            return null;
        }
    }
}
