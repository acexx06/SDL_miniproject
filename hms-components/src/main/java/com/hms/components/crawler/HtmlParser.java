package com.hms.components.crawler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls out of an HTML page the three things the crawler needs: its links, its title, and any
 * structured values marked up as {@code <span data-field="name">value</span>}. The enclosing
 * {@code data-type="..."} attribute says what kind of thing the page describes.
 */
public final class HtmlParser {

    /**
     * @param type   value of the first {@code data-type} attribute, or {@code null}
     * @param fields {@code data-field} name to text, in document order
     * @param links  raw {@code href} values, unresolved
     */
    public record Parsed(String title, String type, Map<String, String> fields, List<String> links) {
    }

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE = Pattern.compile("data-type\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD = Pattern.compile("data-field\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([^<]*)<", Pattern.CASE_INSENSITIVE);

    private HtmlParser() {
    }

    public static Parsed parse(String html) {
        Matcher title = TITLE.matcher(html);
        Matcher type = TYPE.matcher(html);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Matcher m = FIELD.matcher(html); m.find(); ) {
            fields.putIfAbsent(m.group(1), unescape(m.group(2)).trim());
        }
        List<String> links = new ArrayList<>();
        for (Matcher m = LINK.matcher(html); m.find(); ) {
            links.add(unescape(m.group(1)));
        }
        return new Parsed(title.find() ? unescape(title.group(1)).trim() : "",
                type.find() ? type.group(1) : null, fields, links);
    }

    static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
