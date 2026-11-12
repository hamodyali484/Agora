package com.newoether.agora.bidi;

/**
 * Fixes the bidi level of a symbol glued to a Latin word inside an RTL paragraph — without
 * inserting a single character into the text.
 *
 * <p>The Unicode bidi algorithm resolves a neutral such as {@code @}, {@code /}, {@code .},
 * {@code --}, {@code #} or {@code $} to the paragraph direction when it sits between Arabic
 * text and a Latin word. In an Arabic paragraph {@code @ag-ui/claude-agent-sdk.} is therefore
 * laid out as {@code .ag-ui/claude-agent-sdk@}: every character is still at its logical
 * position, only the resolved levels of the edge symbols are wrong for reading.
 *
 * <p>Android keeps the per-character levels in the {@code byte[] chInfo} array that
 * {@code android.text.AndroidBidi.bidi(int, char[], byte[])} fills. Raising the leading
 * symbols to the level of the first Latin letter (an even/LTR level) makes the whole token one
 * LTR run, so the symbol stays at the start of the word. No character is added or removed, so
 * span offsets, link taps and text selection are unaffected.
 *
 * <p>The prefix of a token may mix symbols and ASCII digits ({@code /1/api/x},
 * {@code ./2/build.sh}): digits are already resolved to an even (European number) level and
 * only the symbols are raised.
 *
 * <p>Tokens that carry an explicit directional mark before their first Latin letter (LRM, RLM,
 * ALM, LRE/RLE/PDF/LRO/RLO, LRI/RLI/FSI/PDI) are left untouched: the mark is a deliberate
 * direction choice by the author, and overriding it would fight the sender's intent.
 *
 * <p>Pure Java on purpose: it is compiled and tested on the JVM by
 * {@code xposed-bidi/test-harness/BidiLevelFixerTest.java}.
 *
 * <p>Scope limits (documented, not fixed here): brackets and quotes around a token
 * ({@code (/etc/hosts)}, {@code "[/start]"}), an Arabic fragment inside a Latin-dominant line,
 * and RTL list numbering such as {@code 3)} keep their algorithm-computed order.
 */
public final class BidiLevelFixer {

    /** Characters that may prefix a Latin token and must take the token's level. */
    private static final String LEADING = "/\\.~-_#@+=:$";

    private BidiLevelFixer() {
    }

    /**
     * Rewrites the leading symbols of Latin tokens in {@code levels} in place.
     *
     * @param chars  characters of the paragraph (as passed to {@code AndroidBidi.bidi})
     * @param levels per-character bidi levels; may be longer than {@code chars}
     * @return number of levels changed (0 when nothing needed fixing)
     */
    public static int fix(char[] chars, byte[] levels) {
        if (chars == null || levels == null || chars.length == 0 || levels.length == 0) {
            return 0;
        }
        final int length = Math.min(chars.length, levels.length);
        int changed = 0;
        int i = 0;
        while (i < length) {
            while (i < length && isSeparator(chars[i])) {
                i++;
            }
            final int tokenStart = i;
            while (i < length && !isSeparator(chars[i])) {
                i++;
            }
            if (tokenStart < i) {
                changed += fixToken(chars, levels, tokenStart, i);
            }
        }
        return changed;
    }

    /**
     * Whitespace plus every Unicode space separator. {@link Character#isWhitespace} is false for
     * the no-break spaces (U+00A0, U+2007, U+202F), which would otherwise glue the preceding
     * Arabic word and the following token into one.
     */
    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * Latin letters plus the other left-to-right scripts Android lays out as strong LTR runs
     * (Greek, Cyrillic, Armenian, Georgian). Only such a letter anchors a token: a leading
     * symbol in front of an Arabic word is ordinary punctuation, not a glued token.
     */
    static boolean isLtrLetter(int cp) {
        return (cp >= 0x0041 && cp <= 0x005A)   // A-Z
                || (cp >= 0x0061 && cp <= 0x007A) // a-z
                || (cp >= 0x00C0 && cp <= 0x024F) // Latin-1 Supplement + Extended-A/B
                || (cp >= 0x0370 && cp <= 0x03FF) // Greek
                || (cp >= 0x0400 && cp <= 0x052F) // Cyrillic + Supplement
                || (cp >= 0x0530 && cp <= 0x058F) // Armenian
                || (cp >= 0x10A0 && cp <= 0x10FF) // Georgian
                || (cp >= 0x1E00 && cp <= 0x1EFF) // Latin Extended Additional
                || (cp >= 0x2C60 && cp <= 0x2C7F) // Latin Extended-C
                || (cp >= 0xA720 && cp <= 0xA7FF) // Latin Extended-D
                || (cp >= 0xAB30 && cp <= 0xAB6F); // Latin Extended-E
    }

    /**
     * Explicit directional marks and embeddings/isolates. A token that carries one of these
     * before its first Latin letter keeps the levels the algorithm gave it.
     */
    private static boolean isExplicitMark(char c) {
        return c == '\u200E' // LRM
                || c == '\u200F' // RLM
                || c == '\u061C' // ALM
                || (c >= '\u202A' && c <= '\u202E') // LRE, RLE, PDF, LRO, RLO
                || (c >= '\u2066' && c <= '\u2069'); // LRI, RLI, FSI, PDI
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int fixToken(char[] chars, byte[] levels, int start, int end) {
        int latin = -1;
        for (int k = start; k < end; k++) {
            if (isLtrLetter(chars[k])) {
                latin = k;
                break;
            }
        }
        if (latin < 0) {
            return 0; // pure symbol/digit token ("2/3", "50%", "--", "/"): never changed
        }
        boolean explicitMark = false;
        for (int k = start; k < latin; k++) {
            if (isExplicitMark(chars[k])) {
                explicitMark = true; // the author chose the direction explicitly: respect it
                break;
            }
        }
        final byte target = levels[latin];
        if (explicitMark || (target & 1) != 0) {
            // A directional mark, or the Latin letter itself is not LTR-resolved: the algorithm's
            // answer stands, on both edges.
            return 0;
        }
        int changed = fixPrefix(chars, levels, start, latin, target);
        changed += fixTrailing(chars, levels, start, end, target);
        return changed;
    }

    /**
     * The run of leading symbols and ASCII digits glued to the first Latin letter
     * ({@code @ag-ui/…}, {@code ./2/build.sh}) takes the token's level. Tokens where anything
     * else (an Arabic letter, a bracket, a quote) sits before the Latin are left alone.
     */
    private static int fixPrefix(char[] chars, byte[] levels, int start, int latin, byte target) {
        int p = start;
        boolean symbol = false;
        while (p < latin) {
            final char c = chars[p];
            if (isAsciiDigit(c)) {
                p++;
            } else if (LEADING.indexOf(c) >= 0) {
                symbol = true;
                p++;
            } else {
                break;
            }
        }
        if (p != latin || !symbol) {
            return 0;
        }
        int changed = 0;
        for (int k = start; k < p; k++) {
            if (isAsciiDigit(chars[k])) {
                continue; // digits are already at an even (EN) level; only symbols are raised
            }
            if (levels[k] != target) {
                levels[k] = target;
                changed++;
            }
        }
        return changed;
    }

    /**
     * Same rule on the other edge: a symbol glued to the <em>end</em> of a Latin token
     * ({@code @ag-ui/claude-agent-sdk.}, {@code Python,}) resolves to the paragraph direction and
     * jumps to the far side of the word. Raising it to the token's level keeps it attached.
     *
     * <p>Only symbols directly after the last Latin letter are touched, and only while none of
     * them is a directional mark; everything after the first unrelated character is left to the
     * algorithm.
     */
    private static int fixTrailing(char[] chars, byte[] levels, int start, int end, byte target) {
        int lastLatin = -1;
        for (int k = start; k < end; k++) {
            if (isLtrLetter(chars[k])) {
                lastLatin = k;
            }
        }
        if (lastLatin < 0) {
            return 0;
        }
        int changed = 0;
        for (int k = lastLatin + 1; k < end; k++) {
            final char c = chars[k];
            if (isExplicitMark(c)) {
                break; // the author chose the direction explicitly: respect it
            }
            if (LEADING.indexOf(c) < 0) {
                break; // bracket, quote, Arabic letter or anything else: not our business
            }
            if (levels[k] != target) {
                levels[k] = target;
                changed++;
            }
        }
        return changed;
    }
}
