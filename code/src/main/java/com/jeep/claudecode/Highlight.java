package com.jeep.claudecode;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colours for code blocks: comments, strings, numbers and keywords, alike for most languages. */
final class Highlight {
    /** Languages whose comments start with #. */
    private static final Set<String> HASH = new HashSet<>(Arrays.asList("", "sh", "bash", "shell", "zsh", "fish",
            "console", "python", "py", "yaml", "yml", "toml", "ruby", "rb", "perl", "r", "dockerfile", "makefile",
            "conf", "ini", "nix", "elixir", "ex", "powershell", "ps1"));
    private static final Set<String> DASH = new HashSet<>(Arrays.asList("sql", "lua", "haskell", "hs", "elm"));
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "if", "else", "elif", "for", "while", "do", "return", "function", "def", "class", "const", "let", "var",
            "import", "from", "export", "as", "new", "try", "catch", "except", "finally", "throw", "throws", "raise",
            "in", "of", "is", "not", "and", "or", "null", "nil", "None", "true", "false", "True", "False", "this",
            "self", "super", "public", "private", "protected", "static", "final", "void", "fn", "pub", "use", "mod",
            "struct", "impl", "trait", "match", "case", "switch", "default", "break", "continue", "yield", "async",
            "await", "with", "lambda", "then", "fi", "esac", "done", "local", "package", "interface", "extends",
            "implements", "type", "enum", "go", "defer", "select", "range", "func", "pass", "global", "assert",
            "typeof", "instanceof", "delete", "val", "when", "object", "override", "mut", "ref", "where", "unsafe",
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "AND", "OR",
            "NOT", "NULL", "INTO", "VALUES", "SET", "ORDER", "BY", "GROUP", "LIMIT", "AS"));
    private static final int MAX = 30_000;

    private Highlight() {}

    static CharSequence code(Context c, String code, String lang) {
        if (code.isEmpty() || code.length() > MAX) return code;
        String l = lang.toLowerCase(Locale.ROOT);
        StringBuilder re = new StringBuilder("(?<comment>/\\*[\\s\\S]*?(?:\\*/|$)");
        if (!HASH.contains(l) || l.isEmpty()) re.append("|(?<![:\\w])//[^\\n]*");
        if (HASH.contains(l)) re.append("|(?:^|(?<=\\s))#[^\\n]*");
        if (DASH.contains(l)) re.append("|--[^\\n]*");
        re.append(")|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?|`(?:\\\\.|[^`\\\\])*`?)")
                .append("|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b0[xX][0-9a-fA-F]+\\b)")
                .append("|(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)");
        Matcher m = Pattern.compile(re.toString(), Pattern.MULTILINE).matcher(code);
        SpannableString s = new SpannableString(code);
        int comment = Ui.color(c, R.color.codeComment), string = Ui.color(c, R.color.codeString),
                number = Ui.color(c, R.color.codeNumber), keyword = Ui.color(c, R.color.codeKeyword);
        while (m.find()) {
            int color;
            if (m.group("comment") != null) color = comment;
            else if (m.group("string") != null) color = string;
            else if (m.group("number") != null) color = number;
            else if (KEYWORDS.contains(m.group("word"))) color = keyword;
            else continue;
            s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (color == comment) s.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
}
