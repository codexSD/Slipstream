package com.slipstream.meridian

import androidx.compose.ui.graphics.Color

/**
 * The single source of colour truth. This is the ONLY file in the module permitted
 * to contain a `Color(0x…)` literal — `scripts/check-meridian-tokens.sh` fails the
 * build otherwise, so a colour cannot enter the app without entering this table first.
 *
 * Roles, not names: consumers reference `MeridianTheme.colors.critical`, never a hex.
 */
internal object MeridianTokens {

    /** The calm cool-gray field with a single blue. */
    object Light {
        val canvas = Color(0xFFF4F5F7)
        val surface = Color(0xFFFFFFFF)
        val stroke = Color(0xFFECEDF1)
        val tint = Color(0xFFEEF0FB)
        val ink = Color(0xFF1B1D28)
        val inkMuted = Color(0xFF8A8D9B)

        val brand = Color(0xFF1B62C9)
        val brandStrong = Color(0xFF154FA6)
        val onBrand = Color(0xFFFFFFFF)
        val onBrandMuted = Color(0xFFDCE8FF)

        /** Reuses ink — there is deliberately no separate navy. */
        val strong = ink

        val positive = Color(0xFF2E9E5B)
        val warning = Color(0xFFE08A1E)
        val critical = Color(0xFFD64545)

        /** Equals brand on purpose: an in-flight item is not an alarm. */
        val info = brand
    }

    /**
     * Same roles, re-derived for a dark ground. Hues are held; lightness is inverted
     * for the neutrals and raised for the accents so each still clears 4.5:1 on the
     * dark surface — a straight reuse of the light accents fails that badly.
     */
    object Dark {
        val canvas = Color(0xFF0F1014)
        val surface = Color(0xFF17181D)
        val stroke = Color(0xFF2A2C35)
        val tint = Color(0xFF1D2739)
        val ink = Color(0xFFEDEEF2)
        val inkMuted = Color(0xFF9B9EAC)

        val brand = Color(0xFF6BA5F0)
        val brandStrong = Color(0xFF8FBDF5)
        val onBrand = Color(0xFF0F1014)
        val onBrandMuted = Color(0xFF16324F)

        val strong = ink

        val positive = Color(0xFF5FC98D)
        val warning = Color(0xFFF0AD52)
        val critical = Color(0xFFEE7C7C)

        val info = brand
    }
}
