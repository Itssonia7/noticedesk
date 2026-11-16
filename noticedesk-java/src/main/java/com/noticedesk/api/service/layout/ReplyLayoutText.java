package com.noticedesk.api.service.layout;

import com.noticedesk.api.service.layout.ReplyLayout.Block;
import com.noticedesk.api.service.layout.ReplyLayout.PageBreak;
import com.noticedesk.api.service.layout.ReplyLayout.Para;
import com.noticedesk.api.service.layout.ReplyLayout.Style;
import com.noticedesk.api.service.layout.ReplyLayout.Table;

import java.util.ArrayList;
import java.util.List;

/** The filing layout as plain text, in the same order as the Word document (the .txt output of the runners). */
public final class ReplyLayoutText {

    private ReplyLayoutText() {}

    public static final String PAGE_BREAK = "\f";

    public static String render(ReplyLayout layout) {
        StringBuilder sb = new StringBuilder();
        render(sb, layout.reply());
        render(sb, layout.review());
        return ReplyLayout.plain(sb.toString());
    }

    /** The reply part only (for filing). */
    public static String renderReply(ReplyLayout layout) {
        StringBuilder sb = new StringBuilder();
        render(sb, layout.reply());
        return ReplyLayout.plain(sb.toString());
    }

    private static void render(StringBuilder sb, List<Block> blocks) {
        Style prev = null;
        for (Block b : blocks) {
            if (b instanceof Para p && prev == Style.ITEM && p.style() != Style.ITEM) sb.append('\n');
            prev = b instanceof Para p ? p.style() : null;
            if (b instanceof PageBreak) {
                sb.append('\n').append(PAGE_BREAK).append("\n");
            } else if (b instanceof Para p) {
                String text = p.text() != null ? p.text() : "";
                switch (p.style()) {
                    case PART -> sb.append('\n').append(text).append('\n').append("-".repeat(Math.min(80, Math.max(8, ReplyLayout.plain(text).length())))).append("\n\n");
                    case TITLE -> sb.append(text).append('\n').append("=".repeat(ReplyLayout.plain(text).length())).append("\n\n");
                    case HEADING -> sb.append(text).append("\n\n");
                    case SUBHEADING -> sb.append(text).append("\n\n");
                    case SUBJECT -> sb.append('\n').append(text).append("\n\n");
                    case LETTER, SIGNATURE -> sb.append(text).append('\n');
                    default -> sb.append(p.number() != null ? p.number() + "  " : "").append(text).append(p.style() == Style.ITEM ? "\n" : "\n\n");
                }
                if (p.style() == Style.ITEM && p.number() == null) sb.append('\n');
            } else if (b instanceof Table t) {
                table(sb, t);
            }
        }
    }

    private static void table(StringBuilder sb, Table t) {
        List<List<String>> all = new ArrayList<>();
        all.add(t.header());
        all.addAll(t.rows());
        sb.append(String.join(" | ", t.header())).append('\n');
        sb.append("-".repeat(Math.min(80, String.join(" | ", t.header()).length() + 10))).append('\n');
        for (List<String> r : t.rows()) sb.append(String.join(" | ", r.stream().map(c -> c != null ? c : "").toList())).append('\n');
        sb.append('\n');
    }
}
