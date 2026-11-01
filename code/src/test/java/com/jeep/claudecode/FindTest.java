package com.jeep.claudecode;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /find counts the places in the texts a reply's views show, not in the Markdown it was written in:
 * Md.findable must give the same texts, in the same order, as the views Finder marks them in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FindTest {
    private static final String[] REPLIES = {
        "See [the docs](http://x.com/python) and `python` too.",
        "Run:\n```python\nprint('python')\n```\nDone **python**.",
        "| name | what |\n|---|:--:|\n| python | **bold** |\n| [link](http://p.y/python) |\n\nafter",
        "![alt python](pic.png)\n\nplain python",
        "# Title\n\n- one\n- two python\n\n1. first\n2. second",
    };

    private final Application app = RuntimeEnvironment.getApplication();
    private final Md md = new Md(app, s -> { }, s -> { }, s -> null);

    private static int count(List<String> texts, String query) {
        int n = 0;
        for (String text : texts) {
            String s = text.toLowerCase(Locale.ROOT);
            for (int i = s.indexOf(query); i >= 0; i = s.indexOf(query, i + query.length())) n++;
        }
        return n;
    }

    @Test public void findableTextsAreTheOnesTheViewsShow() {
        for (String reply : REPLIES) {
            LinearLayout box = new LinearLayout(app);
            md.render(box, reply);
            List<TextView> views = new ArrayList<>();
            Finder.findTexts(box, views);
            List<String> shown = new ArrayList<>();
            for (TextView t : views) shown.add(t.getText().toString());
            assertEquals(reply, shown, md.findable(reply));
        }
    }

    @Test public void whatIsNotOnTheScreenIsNotFound() {
        // The link's address and the language after the fence are in the Markdown only.
        assertEquals(1, count(md.findable(REPLIES[0]), "python")); // `python`, not .../python
        assertEquals(2, count(md.findable(REPLIES[1]), "python")); // inside the code, and the bold one; not ```python
        assertEquals(1, count(md.findable(REPLIES[2]), "python")); // the cell, not the link's address
        assertEquals(1, count(md.findable(REPLIES[3]), "python")); // not the picture's alt text
    }
}
