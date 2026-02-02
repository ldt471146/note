import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class MarkdownPreview {
    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownPreview() {
        List<Extension> exts = new ArrayList<Extension>();
        exts.add(StrikethroughExtension.create());
        exts.add(TablesExtension.create());
        parser = Parser.builder().extensions(exts).build();
        renderer = HtmlRenderer.builder().extensions(exts).build();
    }

    public void renderTo(JEditorPane pane, String markdown) {
        if (pane.getEditorKit() == null || !(pane.getEditorKit() instanceof HTMLEditorKit)) {
            pane.setEditorKit(new HTMLEditorKit());
        }
        HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
        StyleSheet css = new StyleSheet();
        css.addRule(baseCss());
        kit.setStyleSheet(css);

        String html = toHtml(markdown);
        pane.setText(html);
        pane.setCaretPosition(0);
    }

    private String toHtml(String markdown) {
        Node doc = parser.parse(markdown == null ? "" : markdown);
        String body = renderer.render(doc);
        return "<html><head><meta charset='utf-8'></head><body>" + body + "</body></html>";
    }

    private String baseCss() {
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        Color link = UIManager.getColor("Component.linkColor");
        if (link == null) link = new Color(0x1a73e8);
        return ""
                + "body{"
                + "font-family:" + cssFontFamily(UIManager.getFont("TextArea.font")) + ";"
                + "font-size:13px;"
                + "line-height:1.45;"
                + "padding:14px;"
                + "background:" + cssRgb(bg) + ";"
                + "color:" + cssRgb(fg) + ";"
                + "}"
                + "a{color:" + cssRgb(link) + ";}"
                + "pre,code{font-family:Consolas,'SFMono-Regular',Menlo,Monaco,monospace;}"
                + "pre{padding:10px;border-radius:8px;overflow:auto;}"
                + "table{border-collapse:collapse;margin:12px 0;}"
                + "th,td{border:1px solid rgba(127,127,127,0.35);padding:6px 8px;}"
                + "blockquote{margin:10px 0;padding-left:10px;border-left:3px solid rgba(127,127,127,0.4);opacity:0.95;}"
                + "h1{font-size:22px;margin:12px 0 8px 0;}"
                + "h2{font-size:18px;margin:12px 0 8px 0;}"
                + "h3{font-size:16px;margin:12px 0 8px 0;}";
    }

    private static String cssRgb(Color c) {
        if (c == null) return "rgb(255,255,255)";
        return "rgb(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")";
    }

    private static String cssFontFamily(Font f) {
        if (f == null) return "sans-serif";
        String name = f.getFamily();
        if (name == null || name.trim().length() == 0) return "sans-serif";
        return "'" + name.replace("'", "") + "',sans-serif";
    }
}
