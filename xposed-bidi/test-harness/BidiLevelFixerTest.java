import com.newoether.agora.bidi.BidiLevelFixer;

import java.text.Bidi;

/**
 * JVM tests for {@link BidiLevelFixer}: the symbol glued to a Latin token must take the token's
 * (LTR) level so the token keeps its logical order inside an RTL paragraph, with no character
 * inserted. Levels are computed with {@code java.text.Bidi} and the visual result is produced
 * with {@code Bidi.reorderVisually}.
 *
 * <p>Bracket pairs are not part of the visual cases: {@code java.text.Bidi} implements no
 * bracket-pair rule and no mirroring, so such tokens are asserted as unchanged on purpose,
 * together with tokens carrying an explicit directional mark, which the fixer must never
 * override.
 */
public final class BidiLevelFixerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // symbols glued to a Latin token must stay on its side
        expectFixed("قبل التفريغ @ag-ui/claude-agent-sdk.", "@ag-ui/claude-agent-sdk.");
        expectFixed("مسار و /system/bin/su", "/system/bin/su");
        expectFixed("شغّل ./run.sh الآن", "./run.sh");
        expectFixed("استخدم --help للمساعدة", "--help");
        expectFixed("وسم #tag هنا", "#tag");
        expectFixed("اكتب @user ثم أرسل", "@user");
        expectFixed("انسخ إلى ~/dir/file", "~/dir/file");

        // '$' is a leading symbol too (env vars and shell prompts glued to a Latin word)
        expectFixed("شغل $HOME/bin الان", "$HOME/bin");
        expectFixed("استخدم $PATH/bin هنا", "$PATH/bin");
        expectFixed("انسخ $HOME/.ssh/id هنا", "$HOME/.ssh/id");

        // ASCII digits are allowed inside the prefix; only the symbols are raised
        expectFixed("المسار /1/api/x هنا", "/1/api/x");
        expectFixed("المسار 1/x/y هنا", "1/x/y");
        expectFixed("الملف ./2/build.sh هنا", "./2/build.sh");

        // no-break spaces separate tokens just like a normal space
        expectFixed("افتح\u00A0/etc/hosts الان", "/etc/hosts");
        expectFixed("افتح\u202F/usr/local/bin الان", "/usr/local/bin");
        expectFixed("افتح\u2007/tmp/x الان", "/tmp/x");

        // trailing symbols glued to the end of a Latin token follow the same rule
        expectFixed("اقرأ Python، ثم Node.", "Node.");
        expectFixed("زيارة https://example.com/a/ هنا", "https://example.com/a/");
        expectFixed("شغّلهم: ./run.sh, then stop", "./run.sh,");

        // nothing to fix
        expectUnchanged("نص عربي صرف");
        expectUnchanged("Hello world");
        expectUnchanged("(Hello)");
        expectUnchanged("\"quoted\"");
        expectUnchanged("2/3");
        expectUnchanged("50%");
        expectUnchanged("--");
        expectUnchanged("/");

        // digit-only tokens stay as the algorithm resolved them (no Latin letter to anchor on)
        expectUnchanged("المسار /1/2 هنا");
        expectUnchanged("شغل $5 الان");

        // brackets and quotes are deliberately NOT fixed (see the class javadoc)
        expectUnchanged("(/etc/hosts)");
        expectUnchanged("\"/etc/hosts\"");
        expectUnchanged("[/start]");
        // an Arabic word glued directly to a symbol is not a leading symbol of a Latin token
        expectUnchanged("ملف/etc/hosts");

        // explicit directional marks before the token are the author's own decision
        expectUnchanged("افتح \u200F/etc/hosts الان"); // RLM
        expectUnchanged("افتح \u200E/etc/hosts الان"); // LRM
        expectUnchanged("افتح \u061C/etc/hosts الان"); // ALM
        expectUnchanged("افتح \u202B/etc/hosts الان"); // RLE
        expectUnchanged("افتح \u2066/etc/hosts\u2069 الان"); // LRI ... PDI
        expectUnchanged("المسار /\u200F/etc/hosts هنا"); // mark after the leading symbol

        // idempotent
        idempotent("شغّل ./run.sh الآن");
        idempotent("شغل $HOME/bin الان");
        idempotent("المسار /1/api/x هنا");
        idempotent("افتح\u00A0/etc/hosts الان");
        idempotent("قبل التفريغ @ag-ui/claude-agent-sdk.");
        idempotent("زيارة https://example.com/a/ هنا");

        // defensive: mismatched array lengths must not throw
        mismatchedLengths();

        System.out.println();
        System.out.println("PASS " + passed + ", FAIL " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void expectFixed(String text, String token) {
        final char[] chars = text.toCharArray();
        final byte[] levels = levels(text);
        final int changed = BidiLevelFixer.fix(chars, levels);
        final String visual = reorder(chars, levels);
        if (changed > 0 && visual.contains(token)) {
            passed++;
            System.out.println("ok   fixed " + text + " -> " + visual);
        } else {
            failed++;
            System.out.println("FAIL fixed " + text + " (changed=" + changed + ", visual=" + visual + ")");
        }
    }

    private static void expectUnchanged(String text) {
        final char[] chars = text.toCharArray();
        final byte[] levels = levels(text);
        final int changed = BidiLevelFixer.fix(chars, levels);
        if (changed == 0) {
            passed++;
            System.out.println("ok   untouched " + text);
        } else {
            failed++;
            System.out.println("FAIL untouched " + text + " (changed=" + changed + ")");
        }
    }

    private static void idempotent(String text) {
        final char[] chars = text.toCharArray();
        final byte[] levels = levels(text);
        final int first = BidiLevelFixer.fix(chars, levels);
        final int second = BidiLevelFixer.fix(chars, levels);
        if (first > 0 && second == 0) {
            passed++;
            System.out.println("ok   idempotent " + text);
        } else {
            failed++;
            System.out.println("FAIL idempotent " + text + " (first=" + first + ", second=" + second + ")");
        }
    }

    private static void mismatchedLengths() {
        try {
            final char[] chars = "شغّل ./run.sh الآن".toCharArray();
            final byte[] shorter = new byte[4];
            final byte[] longer = new byte[chars.length + 10];
            BidiLevelFixer.fix(chars, shorter);
            BidiLevelFixer.fix(chars, longer);
            BidiLevelFixer.fix(null, longer);
            BidiLevelFixer.fix(chars, null);
            passed++;
            System.out.println("ok   mismatched lengths do not throw");
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL mismatched lengths threw " + t);
        }
    }

    private static byte[] levels(String text) {
        final Bidi bidi = new Bidi(text, Bidi.DIRECTION_RIGHT_TO_LEFT);
        final byte[] levels = new byte[text.length()];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = (byte) bidi.getLevelAt(i);
        }
        return levels;
    }

    private static String reorder(char[] chars, byte[] levels) {
        final Object[] objects = new Object[chars.length];
        for (int i = 0; i < chars.length; i++) {
            objects[i] = chars[i];
        }
        Bidi.reorderVisually(levels, 0, objects, 0, chars.length);
        final StringBuilder sb = new StringBuilder(chars.length);
        for (Object o : objects) {
            sb.append((Character) o);
        }
        return sb.toString();
    }
}
