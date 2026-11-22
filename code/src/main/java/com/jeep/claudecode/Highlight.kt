package com.jeep.claudecode

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.util.Locale
import java.util.regex.Pattern

/** Colours for code blocks: comments, strings, numbers and keywords, alike for most languages. */
object Highlight {
    /** Languages whose comments start with #. */
    private val HASH = setOf("", "sh", "bash", "shell", "zsh", "fish",
        "console", "python", "py", "yaml", "yml", "toml", "ruby", "rb", "perl", "r", "dockerfile", "makefile",
        "conf", "ini", "nix", "elixir", "ex", "powershell", "ps1")
    private val DASH = setOf("sql", "lua", "haskell", "hs", "elm")
    private val KEYWORDS = setOf(
        "if", "else", "elif", "for", "while", "do", "return", "function", "def", "class", "const", "let", "var",
        "import", "from", "export", "as", "new", "try", "catch", "except", "finally", "throw", "throws", "raise",
        "in", "of", "is", "not", "and", "or", "null", "nil", "None", "true", "false", "True", "False", "this",
        "self", "super", "public", "private", "protected", "static", "final", "void", "fn", "pub", "use", "mod",
        "struct", "impl", "trait", "match", "case", "switch", "default", "break", "continue", "yield", "async",
        "await", "with", "lambda", "then", "fi", "esac", "done", "local", "package", "interface", "extends",
        "implements", "type", "enum", "go", "defer", "select", "range", "func", "pass", "global", "assert",
        "typeof", "instanceof", "delete", "val", "when", "object", "override", "mut", "ref", "where", "unsafe",
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "AND", "OR",
        "NOT", "NULL", "INTO", "VALUES", "SET", "ORDER", "BY", "GROUP", "LIMIT", "AS")
    private const val MAX = 30_000

    @JvmStatic
    fun code(c: Context, code: String, lang: String): CharSequence {
        if (code.isEmpty() || code.length > MAX) return code
        val l = lang.lowercase(Locale.ROOT)
        val re = StringBuilder("(?<comment>/\\*[\\s\\S]*?(?:\\*/|\\z)")
        if (l !in HASH || l.isEmpty()) re.append("|(?<![:\\w])//[^\\n]*")
        if (l in HASH) re.append("|(?:^|(?<=\\s))#[^\\n]*")
        if (l in DASH) re.append("|--[^\\n]*")
        re.append(")|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?|`(?:\\\\.|[^`\\\\])*`?)")
            .append("|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b0[xX][0-9a-fA-F]+\\b)")
            .append("|(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
        val m = Pattern.compile(re.toString(), Pattern.MULTILINE).matcher(code)
        val s = SpannableString(code)
        val comment = Ui.color(c, R.color.codeComment)
        val string = Ui.color(c, R.color.codeString)
        val number = Ui.color(c, R.color.codeNumber)
        val keyword = Ui.color(c, R.color.codeKeyword)
        while (m.find()) {
            val color = when {
                m.group("comment") != null -> comment
                m.group("string") != null -> string
                m.group("number") != null -> number
                m.group("word") in KEYWORDS -> keyword
                else -> continue
            }
            s.setSpan(ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color == comment) s.setSpan(StyleSpan(Typeface.ITALIC), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }
}
