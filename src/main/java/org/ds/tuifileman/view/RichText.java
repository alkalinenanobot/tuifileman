package org.ds.tuifileman.view;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterNode;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

/**
 * HTML and Markdown files as styled plain text for the F3 viewer: headings (underlined or marked with #),
 * paragraphs, lists, tables, block quotes, code blocks and links as {@code text (url)}, with bold, italic,
 * underline, strike-through and the heading, link and code colours as {@link TextStyle}. Both parsers are pure Java.
 */
final class RichText {
    private static final String RULE = "\u2500".repeat(40);
    private static final Parser MARKDOWN = Parser.builder().extensions(List.<Extension>of(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(),
            YamlFrontMatterExtension.create())).build();

    enum Kind {
        HTML("HTML text"),
        MARKDOWN("Markdown");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private RichText() { }

    /** The kind of document a file name says it is, or null for anything else. */
    static Kind kindOf(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return null;
        }
        String file = name.toString().toLowerCase(Locale.ROOT);
        int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (file.substring(dot + 1)) {
            case "html", "htm", "xhtml" -> Kind.HTML;
            case "md", "markdown", "mdown", "mkd" -> Kind.MARKDOWN;
            default -> null;
        };
    }

    /** Renders the bytes of a file of the given kind; the result is empty when the document has no text. */
    static TextOut.Result render(Kind kind, byte[] bytes, Charset fallback) throws IOException {
        return kind == Kind.HTML ? html(bytes) : markdown(decode(bytes, fallback));
    }

    /** UTF-8 (a BOM is dropped) when the bytes are valid UTF-8, else the fallback charset. */
    private static String decode(byte[] bytes, Charset fallback) {
        int start = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF
                ? 3 : 0;
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, fallback);
        }
    }

    // ---------------------------------------------------------------- HTML

    static TextOut.Result html(byte[] bytes) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
        String title = doc.title().strip();
        doc.select("script, style, noscript, template, svg, iframe, head").remove();
        TextOut out = new TextOut();
        Element firstHeading = doc.selectFirst("h1");
        if (!title.isEmpty() && (firstHeading == null || !firstHeading.text().strip().equals(title))) {
            out.pushStyle(TextStyle.HEADING | TextStyle.BOLD);
            out.text(title);
            out.breaks(1);
            out.text("=".repeat(Math.max(1, out.lineWidth())));
            out.popStyle();
            out.breaks(2);
        }
        NodeTraversor.filter(new HtmlVisitor(out), doc.body());
        return out.result();
    }

    private static final Set<String> BLOCKS = Set.of("address", "article", "aside", "body", "details", "div",
            "fieldset", "figcaption", "figure", "footer", "form", "header", "main", "nav", "section", "summary",
            "center", "dl", "html");

    /** The style an HTML element adds to its content, 0 for none. */
    private static int styleOf(Element el) {
        return switch (el.normalName()) {
            case "b", "strong", "dt" -> TextStyle.BOLD;
            case "i", "em", "cite", "dfn", "var" -> TextStyle.ITALIC;
            case "u", "ins" -> TextStyle.UNDERLINE;
            case "s", "del", "strike" -> TextStyle.STRIKE;
            case "code", "kbd", "samp", "tt", "pre" -> TextStyle.CODE;
            case "a" -> el.hasAttr("href") ? TextStyle.LINK | TextStyle.UNDERLINE : 0;
            case "h1", "h2", "h3", "h4", "h5", "h6" -> TextStyle.HEADING | TextStyle.BOLD;
            case "blockquote" -> TextStyle.MUTED;
            default -> 0;
        };
    }

    private static final class HtmlVisitor implements NodeFilter {
        private final TextOut out;
        private final Deque<int[]> lists = new ArrayDeque<>(); // {next number}, 0 = bullets
        private int pre;

        HtmlVisitor(TextOut out) {
            this.out = out;
        }

        @Override
        public FilterResult head(org.jsoup.nodes.Node node, int depth) {
            if (node instanceof TextNode text) {
                if (pre > 0) {
                    out.literal(text.getWholeText());
                } else {
                    out.text(text.getWholeText());
                }
                return FilterResult.CONTINUE;
            }
            if (!(node instanceof Element el)) {
                return FilterResult.CONTINUE;
            }
            String tag = el.normalName();
            if (tag.equals("table")) {
                table(el);
                return FilterResult.SKIP_ENTIRELY;
            }
            switch (tag) {
                case "hr" -> {
                    out.breaks(2);
                    out.pushStyle(TextStyle.MUTED);
                    out.text(RULE);
                    out.popStyle();
                    out.breaks(2);
                }
                case "img" -> {
                    String alt = el.attr("alt").strip();
                    out.pushStyle(TextStyle.MUTED | TextStyle.ITALIC);
                    out.text(alt.isEmpty() ? "[image]" : "[image: " + alt + "]");
                    out.popStyle();
                }
                case "pre" -> {
                    out.breaks(2);
                    pre++;
                    out.pushIndent("    ");
                }
                case "ul", "ol" -> {
                    out.breaks(lists.isEmpty() ? 2 : 1);
                    lists.push(new int[] {"ol".equals(tag) ? startNumber(el) : 0});
                }
                case "li" -> {
                    out.breaks(1);
                    int[] list = lists.peek();
                    String marker = list == null || list[0] == 0 ? "\u2022 " : (list[0]++) + ". ";
                    out.bullet(marker);
                    out.pushIndent(" ".repeat(marker.length()));
                }
                case "blockquote" -> {
                    out.breaks(2);
                    out.pushIndent("> ");
                }
                case "br" -> out.br();
                case "h1", "h2", "h3", "h4", "h5", "h6", "p" -> out.breaks(2);
                case "tr", "dt" -> out.breaks(1);
                case "dd" -> {
                    out.breaks(1);
                    out.pushIndent("    ");
                }
                default -> {
                    if (BLOCKS.contains(tag)) {
                        out.breaks(1);
                    }
                }
            }
            int style = styleOf(el);
            if (style != 0) {
                out.pushStyle(style);
            }
            if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '3' && tag.charAt(1) <= '6') {
                out.text("#".repeat(tag.charAt(1) - '0') + " ");
            }
            return FilterResult.CONTINUE;
        }

        @Override
        public FilterResult tail(org.jsoup.nodes.Node node, int depth) {
            if (!(node instanceof Element el)) {
                return FilterResult.CONTINUE;
            }
            String tag = el.normalName();
            int style = styleOf(el);
            if (tag.equals("a")) {
                if (style != 0) {
                    out.popStyle();
                }
                link(el);
                return FilterResult.CONTINUE;
            }
            switch (tag) {
                case "pre" -> {
                    pre--;
                    out.popIndent();
                    out.breaks(2);
                }
                case "h1", "h2" -> {
                    int width = out.lineWidth();
                    out.breaks(1);
                    out.text((tag.equals("h1") ? "=" : "-").repeat(Math.max(1, width)));
                    out.breaks(2);
                }
                case "h3", "h4", "h5", "h6", "p" -> out.breaks(2);
                case "ul", "ol" -> {
                    lists.pop();
                    out.breaks(lists.isEmpty() ? 2 : 1);
                }
                case "li" -> {
                    out.flushBullet();
                    out.popIndent();
                    out.breaks(1);
                }
                case "blockquote" -> {
                    out.popIndent();
                    out.breaks(2);
                }
                case "dd" -> {
                    out.popIndent();
                    out.breaks(1);
                }
                case "tr", "dt" -> out.breaks(1);
                default -> {
                    if (BLOCKS.contains(tag)) {
                        out.breaks(1);
                    }
                }
            }
            if (style != 0) {
                out.popStyle();
            }
            return FilterResult.CONTINUE;
        }

        private void link(Element el) {
            String href = el.attr("href").strip();
            if (href.isEmpty() || href.startsWith("#") || href.regionMatches(true, 0, "javascript:", 0, 11)) {
                return;
            }
            String label = el.text().strip();
            if (label.equals(href) || label.equals(href.replaceFirst("(?i)^mailto:", ""))) {
                return;
            }
            out.space();
            out.pushStyle(TextStyle.MUTED);
            out.text("(" + href + ")");
            out.popStyle();
        }

        private static int startNumber(Element ol) {
            try {
                return Math.max(1, Integer.parseInt(ol.attr("start").strip()));
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        /** A table as aligned columns; {@code th} cells and rows of a {@code thead} are the header. */
        private void table(Element table) {
            List<Element> trs = new ArrayList<>();
            Element caption = null;
            for (Element child : table.children()) {
                switch (child.normalName()) {
                    case "tr" -> trs.add(child);
                    case "thead", "tbody", "tfoot" -> trs.addAll(child.children().stream()
                            .filter(e -> e.normalName().equals("tr")).toList());
                    case "caption" -> caption = child;
                    default -> { }
                }
            }
            if (caption != null && !caption.text().isBlank()) {
                out.breaks(2);
                out.pushStyle(TextStyle.BOLD);
                out.text(caption.text());
                out.popStyle();
            }
            List<List<TextOut.Result>> rows = new ArrayList<>();
            List<TableLayout.Align> aligns = new ArrayList<>();
            int headerRows = 0;
            boolean headerOpen = true;
            for (Element tr : trs) {
                List<TextOut.Result> row = new ArrayList<>();
                boolean allHeaders = true;
                int column = 0;
                for (Element cell : tr.children()) {
                    if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) {
                        continue;
                    }
                    allHeaders &= cell.normalName().equals("th");
                    row.add(cellText(cell));
                    while (aligns.size() <= column) {
                        aligns.add(null);
                    }
                    if (aligns.get(column) == null) {
                        aligns.set(column, alignOf(cell.attr("align")));
                    }
                    int span = spanOf(cell.attr("colspan"));
                    for (int i = 1; i < span; i++) {
                        row.add(TextOut.Result.EMPTY);
                    }
                    column += span;
                }
                if (row.isEmpty()) {
                    continue;
                }
                boolean inHead = tr.parent() != null && tr.parent().normalName().equals("thead");
                if (headerOpen && (inHead || allHeaders)) {
                    headerRows++;
                } else {
                    headerOpen = false;
                }
                rows.add(row);
            }
            TableLayout.write(out, rows, headerRows, aligns);
        }

        private static TextOut.Result cellText(Element cell) {
            TextOut sub = new TextOut();
            HtmlVisitor visitor = new HtmlVisitor(sub);
            for (org.jsoup.nodes.Node child : cell.childNodes()) {
                NodeTraversor.filter(visitor, child);
            }
            return sub.result().oneLine();
        }

        private static TableLayout.Align alignOf(String value) {
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "right" -> TableLayout.Align.RIGHT;
                case "center" -> TableLayout.Align.CENTER;
                default -> null;
            };
        }

        private static int spanOf(String value) {
            try {
                return Math.max(1, Math.min(50, Integer.parseInt(value.strip())));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    // ------------------------------------------------------------ Markdown

    static TextOut.Result markdown(String source) {
        Node doc = MARKDOWN.parse(source);
        TextOut out = new TextOut();
        doc.accept(new MarkdownVisitor(out));
        return out.result();
    }

    private static final class MarkdownVisitor extends AbstractVisitor {
        private final TextOut out;
        private int listDepth;

        MarkdownVisitor(TextOut out) {
            this.out = out;
        }

        @Override
        public void visit(Heading heading) {
            out.breaks(2);
            out.pushStyle(TextStyle.HEADING | TextStyle.BOLD);
            int level = heading.getLevel();
            if (level >= 3) {
                out.text("#".repeat(level) + " ");
            }
            visitChildren(heading);
            if (level <= 2) {
                int width = out.lineWidth();
                out.breaks(1);
                out.text((level == 1 ? "=" : "-").repeat(Math.max(1, width)));
            }
            out.popStyle();
            out.breaks(2);
        }

        @Override
        public void visit(Paragraph paragraph) {
            int gap = tightItem(paragraph) ? 1 : 2;
            if (!(paragraph.getPrevious() instanceof TaskListItemMarker)) {
                out.breaks(gap);
            }
            visitChildren(paragraph);
            out.breaks(gap);
        }

        private static boolean tightItem(Paragraph paragraph) {
            Node item = paragraph.getParent();
            return item instanceof ListItem && item.getParent() instanceof ListBlock list && list.isTight();
        }

        @Override
        public void visit(BulletList list) {
            items(list, null);
        }

        @Override
        public void visit(OrderedList list) {
            items(list, list.getMarkerStartNumber() == null ? 1 : list.getMarkerStartNumber());
        }

        /** {@code number} is the number of the first item of an ordered list, null for bullets. */
        private void items(ListBlock list, Integer number) {
            out.breaks(listDepth == 0 ? 2 : 1);
            listDepth++;
            int n = number == null ? 0 : number;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                String marker = number == null ? "\u2022 " : (n++) + ". ";
                out.breaks(1);
                out.bullet(marker);
                out.pushIndent(" ".repeat(marker.length()));
                visitChildren(item);
                out.flushBullet();
                out.popIndent();
                out.breaks(1);
            }
            listDepth--;
            out.breaks(listDepth == 0 ? 2 : 1);
        }

        @Override
        public void visit(BlockQuote quote) {
            out.breaks(2);
            out.pushIndent("> ");
            out.pushStyle(TextStyle.MUTED);
            visitChildren(quote);
            out.popStyle();
            out.popIndent();
            out.breaks(2);
        }

        @Override
        public void visit(FencedCodeBlock block) {
            code(block.getLiteral(), TextStyle.CODE);
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            code(block.getLiteral(), TextStyle.CODE);
        }

        @Override
        public void visit(HtmlBlock block) {
            code(block.getLiteral(), TextStyle.MUTED);
        }

        private void code(String literal, int style) {
            out.breaks(2);
            out.pushIndent("    ");
            out.pushStyle(style);
            out.literal(literal);
            out.popStyle();
            out.popIndent();
            out.breaks(2);
        }

        @Override
        public void visit(HtmlInline html) {
            if (html.getLiteral().regionMatches(true, 0, "<br", 0, 3)) {
                out.br();
            }
        }

        @Override
        public void visit(ThematicBreak rule) {
            out.breaks(2);
            out.pushStyle(TextStyle.MUTED);
            out.text(RULE);
            out.popStyle();
            out.breaks(2);
        }

        @Override
        public void visit(Text text) {
            out.text(text.getLiteral());
        }

        @Override
        public void visit(Code code) {
            out.pushStyle(TextStyle.CODE);
            out.text(code.getLiteral());
            out.popStyle();
        }

        @Override
        public void visit(Emphasis emphasis) {
            out.pushStyle(TextStyle.ITALIC);
            visitChildren(emphasis);
            out.popStyle();
        }

        @Override
        public void visit(StrongEmphasis strong) {
            out.pushStyle(TextStyle.BOLD);
            visitChildren(strong);
            out.popStyle();
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            out.space();
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            out.br();
        }

        @Override
        public void visit(Link link) {
            out.pushStyle(TextStyle.LINK | TextStyle.UNDERLINE);
            visitChildren(link);
            out.popStyle();
            String destination = link.getDestination();
            if (destination == null || destination.isEmpty() || destination.startsWith("#")) {
                return;
            }
            String label = plain(link);
            if (label.equals(destination) || label.equals(destination.replaceFirst("(?i)^mailto:", ""))) {
                return;
            }
            out.space();
            out.pushStyle(TextStyle.MUTED);
            out.text("(" + destination + ")");
            out.popStyle();
        }

        @Override
        public void visit(Image image) {
            String alt = plain(image);
            out.pushStyle(TextStyle.MUTED | TextStyle.ITALIC);
            out.text(alt.isEmpty() ? "[image]" : "[image: " + alt + "]");
            out.popStyle();
        }

        @Override
        public void visit(CustomNode node) {
            if (node instanceof Strikethrough) {
                out.pushStyle(TextStyle.STRIKE);
                visitChildren(node);
                out.popStyle();
            } else if (node instanceof TaskListItemMarker marker) {
                out.pushStyle(marker.isChecked() ? TextStyle.SUCCESS : TextStyle.MUTED);
                out.text(marker.isChecked() ? "[x]" : "[ ]");
                out.popStyle();
                out.space();
            } else {
                visitChildren(node);
            }
        }

        @Override
        public void visit(CustomBlock block) {
            if (block instanceof TableBlock) {
                table(block);
            } else if (block instanceof YamlFrontMatterBlock) {
                out.breaks(2);
                out.pushStyle(TextStyle.MUTED);
                for (Node child = block.getFirstChild(); child != null; child = child.getNext()) {
                    if (child instanceof YamlFrontMatterNode entry) {
                        out.breaks(1);
                        out.text(entry.getKey() + ": " + String.join(", ", entry.getValues()));
                    }
                }
                out.popStyle();
                out.breaks(2);
            } else {
                visitChildren(block);
            }
        }

        private void table(Node block) {
            List<List<TextOut.Result>> rows = new ArrayList<>();
            List<TableLayout.Align> aligns = new ArrayList<>();
            int headerRows = 0;
            for (Node part = block.getFirstChild(); part != null; part = part.getNext()) {
                for (Node row = part.getFirstChild(); row != null; row = row.getNext()) {
                    List<TextOut.Result> cells = new ArrayList<>();
                    int column = 0;
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        TextOut sub = new TextOut();
                        MarkdownVisitor visitor = new MarkdownVisitor(sub);
                        for (Node content = cell.getFirstChild(); content != null; content = content.getNext()) {
                            content.accept(visitor);
                        }
                        cells.add(sub.result().oneLine());
                        if (aligns.size() <= column) {
                            aligns.add(alignOf(cell instanceof TableCell tc ? tc.getAlignment() : null));
                        }
                        column++;
                    }
                    rows.add(cells);
                    if (part instanceof TableHead) {
                        headerRows++;
                    }
                }
            }
            TableLayout.write(out, rows, headerRows, aligns);
        }

        private static TableLayout.Align alignOf(TableCell.Alignment alignment) {
            if (alignment == null) {
                return TableLayout.Align.LEFT;
            }
            return switch (alignment) {
                case LEFT -> TableLayout.Align.LEFT;
                case CENTER -> TableLayout.Align.CENTER;
                case RIGHT -> TableLayout.Align.RIGHT;
            };
        }

        /** The text of a node without markup. */
        private static String plain(Node node) {
            StringBuilder sb = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text text) {
                    sb.append(text.getLiteral());
                }

                @Override
                public void visit(Code code) {
                    sb.append(code.getLiteral());
                }
            });
            return sb.toString().strip();
        }
    }
}
