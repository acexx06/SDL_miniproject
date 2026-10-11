package com.hms.components.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The parts of a robots.txt file that apply to one crawler: allow/disallow path prefixes and crawl delay. */
public final class RobotsRules {

    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), 0);

    private record Rule(String prefix, boolean allow) {
    }

    private final List<Rule> rules;
    private final long crawlDelayMs;

    private RobotsRules(List<Rule> rules, long crawlDelayMs) {
        this.rules = rules;
        this.crawlDelayMs = crawlDelayMs;
    }

    /** Uses the group naming this crawler if there is one, otherwise the {@code *} group. */
    public static RobotsRules parse(String text, String userAgent) {
        String agent = userAgent.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        long[] delays = {-1, -1}; // specific, wildcard
        boolean matchesSpecific = false;
        boolean matchesWildcard = false;
        boolean readingAgents = false;
        boolean sawSpecific = false;

        for (String raw : text.split("\\R")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!readingAgents) { // a new group starts
                    matchesSpecific = false;
                    matchesWildcard = false;
                    readingAgents = true;
                }
                String token = value.toLowerCase(Locale.ROOT);
                if (token.equals("*")) {
                    matchesWildcard = true;
                } else if (!token.isEmpty() && agent.contains(token)) {
                    matchesSpecific = true;
                    sawSpecific = true;
                }
                continue;
            }
            readingAgents = false;
            boolean isAllow = field.equals("allow");
            if ((isAllow || field.equals("disallow")) && !value.isEmpty()) {
                if (matchesSpecific) {
                    specific.add(new Rule(value, isAllow));
                }
                if (matchesWildcard) {
                    wildcard.add(new Rule(value, isAllow));
                }
            } else if (field.equals("crawl-delay")) {
                try {
                    long ms = (long) (Double.parseDouble(value) * 1000);
                    if (matchesSpecific) {
                        delays[0] = ms;
                    }
                    if (matchesWildcard) {
                        delays[1] = ms;
                    }
                } catch (NumberFormatException ignored) {
                    // malformed delay: ignore the line
                }
            }
        }
        return sawSpecific ? new RobotsRules(specific, Math.max(0, delays[0]))
                : new RobotsRules(wildcard, Math.max(0, delays[1]));
    }

    /** The longest matching prefix decides; with no match the path is allowed. */
    public boolean isAllowed(String path) {
        Rule best = null;
        for (Rule rule : rules) {
            if (path.startsWith(rule.prefix) && (best == null || rule.prefix.length() > best.prefix.length()
                    || (rule.prefix.length() == best.prefix.length() && rule.allow))) {
                best = rule;
            }
        }
        return best == null || best.allow;
    }

    public long crawlDelayMs() {
        return crawlDelayMs;
    }
}
