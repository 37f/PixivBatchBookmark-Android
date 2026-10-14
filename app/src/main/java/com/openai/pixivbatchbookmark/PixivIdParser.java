package com.openai.pixivbatchbookmark;

import java.util.ArrayList;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PixivIdParser {
    // “pixiv id” itself is ambiguous: many posts use it for the artist/user ID.
    // Only the hints below are treated as strong author hints.
    private static final String[] AUTHOR_HINTS = {
            "画师", "作者", "artist", "author", "user id", "userid", "用户id", "uid", "主页", "profile"
    };
    private static final String[] EXPLICIT_WORK_HINTS = {
            "作品 id", "作品id", "artwork id", "artworkid", "illust id", "illustid", "pid"
    };

    // Schemeful URLs are parsed as whole candidates. Scheme-less links must start at a
    // real separator, never within an ASCII/Unicode hostname or another URL's path.
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"'，；,;]+|(?<![^\\s\\[('\"<：])(?:www\\.)?pixiv\\.net/[^\\s<>\"'，；,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORK_PATH = Pattern.compile("^/(?:[a-z-]+/)?artworks/(\\d{5,12})/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_PATH = Pattern.compile("^/(?:[a-z-]+/)?users/(\\d{5,12})/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIXIV_ID = Pattern.compile("(?:pixiv\\s*[-_ ]?id|pixivid)\\s*[：:]?\\s*(\\d{5,12})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PID = Pattern.compile("(?:pid|作品\\s*id|artwork\\s*id|illust\\s*id)\\s*[：:]?\\s*(\\d{5,12})(?!\\d)", Pattern.CASE_INSENSITIVE);

    private PixivIdParser() {}

    public static List<String> extract(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) return new ArrayList<>();

        boolean authorListContext = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replace("\ufeff", "").trim();
            if (line.isEmpty()) continue;

            if (line.matches("[-—_=─]{2,}")) { authorListContext = false; continue; }
            Set<String> artworkIds = collectLinks(line, WORK_PATH);
            out.addAll(artworkIds);
            Set<String> userIds = collectLinks(line, USER_PATH);

            boolean currentAuthor = hasHint(line, AUTHOR_HINTS);
            boolean explicitWorkContext = hasHint(line, EXPLICIT_WORK_HINTS);
            String lower = line.toLowerCase(Locale.ROOT).trim();
            String numericList = line.replaceFirst("^\\d{1,4}\\s*[.)、]\\s*", "");
            boolean numericRow = numericList.matches("[0-9\\s,，;；]+");
            boolean startsLikeAmbiguousId = lower.startsWith("pixiv") || lower.startsWith("uid")
                    || lower.startsWith("user") || lower.startsWith("用户");
            if (explicitWorkContext || !artworkIds.isEmpty()) authorListContext = false;
            else if (currentAuthor) authorListContext = true;
            else if (!numericRow && !startsLikeAmbiguousId) authorListContext = false;

            // Explicit PID / artwork ID is unambiguous even if the same line also names the artist.
            ArrayList<String> pidMatches = new ArrayList<>();
            collectInto(pidMatches, PID.matcher(line));
            for (String candidate : pidMatches) if (!userIds.contains(candidate)) out.add(candidate);

            // "pixiv id" is commonly an artist UID. Ignore it when the line itself is an
            // artist/author line, or when it immediately follows an author heading.
            ArrayList<String> pixivMatches = new ArrayList<>();
            collectInto(pixivMatches, PIXIV_ID.matcher(line));
            for (String candidate : pixivMatches) {
                if (userIds.contains(candidate)) continue;
                if (!explicitWorkContext && (currentAuthor || (authorListContext && startsLikeAmbiguousId))) continue;
                out.add(candidate);
            }

            // Accept only a whole numeric list, not arbitrary numbers in captions/URLs.
            if (!currentAuthor && !authorListContext && numericRow) {
                for (String candidate : numericList.split("[\\s,，;；]+"))
                    if (candidate.matches("\\d{5,12}") && !userIds.contains(candidate)) out.add(candidate);
            }
        }
        return new ArrayList<>(out);
    }

    private static Set<String> collectLinks(String line, Pattern pathPattern) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher candidates = LINK.matcher(line);
        while (candidates.find()) {
            String value = candidates.group();
            value = value.replaceFirst("[.)\\]。！]+$", "");
            try {
                URI uri = new URI(value.matches("(?i)^https?://.*") ? value : "https://" + value);
                String host = uri.getHost(), scheme = uri.getScheme();
                if (host == null || !(host.equalsIgnoreCase("pixiv.net") || host.equalsIgnoreCase("www.pixiv.net"))) continue;
                if (uri.getRawUserInfo() != null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) continue;
                if (uri.getPort() != -1 && !("https".equalsIgnoreCase(scheme) ? uri.getPort() == 443 : uri.getPort() == 80)) continue;
                Matcher path = pathPattern.matcher(uri.getPath() == null ? "" : uri.getPath());
                if (path.matches()) out.add(path.group(1));
            } catch (Exception ignored) { /* A malformed/non-Pixiv URL is not a work identifier. */ }
        }
        return out;
    }

    private static void collectInto(List<String> out, Matcher m) {
        while (m.find()) out.add(m.group(1));
    }

    private static boolean hasHint(String text, String[] hints) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String h : hints) if (lower.contains(h.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
