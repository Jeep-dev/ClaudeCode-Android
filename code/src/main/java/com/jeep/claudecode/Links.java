package com.jeep.claudecode;

import android.text.SpannableStringBuilder;
import android.text.style.URLSpan;
import android.text.util.Linkify;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.node.Link;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.SpanFactory;
import io.noties.markwon.SpannableBuilder;
import io.noties.markwon.core.CoreProps;
import io.noties.markwon.core.CorePlugin;

/**
 * Web addresses, e-mails and phone numbers in prose become links. Android's Linkify takes Chinese
 * as part of an address: in 「访问不了。gol.gg」 or 「（lol2llm.com）」 the link began at the Chinese,
 * and a copied one was not an address. Here a host is ASCII only, and an address stops at 。，（）.
 */
final class Links extends AbstractMarkwonPlugin {
    /** What ends an address: spaces, quotes, and CJK punctuation and full-width forms. */
    private static final String REST = "[^\\s<>\"'`\\u3000-\\u303F\\uFF00-\\uFFEF]*";
    private static final Pattern URL = Pattern.compile(
            "(?:https?://[A-Za-z0-9.-]+(?::\\d+)?"
                    // without http:// only a known ending: Md.java, build.sh are files
                    + "|(?<![A-Za-z0-9.@/_-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+"
                    + "(?:com|net|org|io|gg|cn|dev|app|ai|co|me|tv|info|xyz|edu|gov|jp|kr|uk|de|fr|ru|us|top|site|link|cc|hk|tw)"
                    + "(?![A-Za-z0-9-]))"
                    + "(?:[/?#]" + REST + ")?");

    @Override public void configure(@NonNull Registry registry) {
        registry.require(CorePlugin.class, core -> core.addOnTextAddedListener(Links::added));
    }

    private static void added(@NonNull MarkwonVisitor visitor, @NonNull String text, int start) {
        MarkwonConfiguration configuration = visitor.configuration();
        SpanFactory factory = configuration.spansFactory().get(Link.class);
        if (factory == null) return;
        SpannableStringBuilder s = new SpannableStringBuilder(text);
        Linkify.addLinks(s, Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
        List<int[]> found = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (URLSpan span : s.getSpans(0, s.length(), URLSpan.class)) {
            found.add(new int[]{s.getSpanStart(span), s.getSpanEnd(span)});
            urls.add(span.getURL());
        }
        int taken = found.size();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            int a = m.start(), b = trim(text, a, m.end());
            if (b <= a || overlaps(found, taken, a, b)) continue;
            String url = text.substring(a, b);
            found.add(new int[]{a, b});
            urls.add(url.contains("://") ? url : "http://" + url);
        }
        for (int i = 0; i < found.size(); i++) {
            CoreProps.LINK_DESTINATION.set(visitor.renderProps(), urls.get(i));
            SpannableBuilder.setSpans(visitor.builder(), factory.getSpans(configuration, visitor.renderProps()),
                    start + found.get(i)[0], start + found.get(i)[1]);
        }
    }

    /** An address does not end with the sentence's . , ! ? or with a ) it did not open. */
    private static int trim(String text, int a, int b) {
        while (b > a) {
            char last = text.charAt(b - 1);
            if (".,;:!?'\"*_~".indexOf(last) >= 0) b--;
            else if (last == ')' && count(text, a, b, '(') < count(text, a, b, ')')) b--;
            else break;
        }
        return b;
    }

    private static int count(String text, int a, int b, char ch) {
        int n = 0;
        for (int i = a; i < b; i++) if (text.charAt(i) == ch) n++;
        return n;
    }

    private static boolean overlaps(List<int[]> found, int upTo, int a, int b) {
        for (int i = 0; i < upTo; i++) if (a < found.get(i)[1] && found.get(i)[0] < b) return true;
        return false;
    }
}
