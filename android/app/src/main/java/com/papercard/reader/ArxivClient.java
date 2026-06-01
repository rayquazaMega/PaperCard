package com.papercard.reader;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

class ArxivClient {
    static class Result {
        final ArrayList<Paper> papers;
        final String query;

        Result(ArrayList<Paper> papers, String query) {
            this.papers = papers;
            this.query = query;
        }
    }

    Result fetch(Preferences preferences) throws Exception {
        String query = buildQuery(preferences);
        String url = "https://export.arxiv.org/api/query"
                + "?search_query=" + URLEncoder.encode(query, "UTF-8")
                + "&start=0"
                + "&max_results=" + preferences.maxResults
                + "&sortBy=submittedDate"
                + "&sortOrder=descending";

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "PaperCard/1.0 native-android");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("arXiv 返回 " + status);

        String xml = readString(connection);
        ArrayList<Paper> papers = parse(xml, preferences);
        return new Result(papers, query);
    }

    private static String buildQuery(Preferences preferences) {
        ArrayList<String> keywordParts = new ArrayList<>();
        for (String keyword : preferences.keywords) {
            String term = quoteTerm(keyword);
            if (!term.isEmpty()) {
                keywordParts.add("ti:" + term);
                keywordParts.add("abs:" + term);
            }
        }
        ArrayList<String> categoryParts = new ArrayList<>();
        for (String category : preferences.categories) categoryParts.add("cat:" + category);
        if (!keywordParts.isEmpty() && !categoryParts.isEmpty()) {
            return "(" + join(keywordParts, " OR ") + ") AND (" + join(categoryParts, " OR ") + ")";
        }
        if (!keywordParts.isEmpty()) return join(keywordParts, " OR ");
        return join(categoryParts, " OR ");
    }

    private static String quoteTerm(String value) {
        String clean = value == null ? "" : value.replaceAll("[\"()]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "";
        return clean.contains(" ") ? "\"" + clean + "\"" : clean;
    }

    private static ArrayList<Paper> parse(String xml, Preferences preferences) throws Exception {
        ArrayList<Paper> papers = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new java.io.StringReader(xml));

        Paper current = null;
        String textTag = "";
        StringBuilder text = new StringBuilder();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("entry".equals(name)) {
                    current = new Paper();
                } else if (current != null) {
                    if ("link".equals(name)) {
                        handleLink(parser, current);
                    } else if (name != null && name.contains("primary_category")) {
                        String term = parser.getAttributeValue(null, "term");
                        if (term != null && !term.isEmpty()) current.category = term;
                    } else if ("category".equals(name) && "arXiv".equals(current.category)) {
                        String term = parser.getAttributeValue(null, "term");
                        if (term != null && !term.isEmpty()) current.category = term;
                    } else if (isTextTag(name)) {
                        textTag = name;
                        text.setLength(0);
                    }
                }
            } else if (event == XmlPullParser.TEXT && current != null && !textTag.isEmpty()) {
                text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if (current != null && !textTag.isEmpty() && textTag.equals(name)) {
                    applyText(current, textTag, text.toString());
                    textTag = "";
                    text.setLength(0);
                } else if ("entry".equals(name) && current != null) {
                    finishPaper(current, preferences);
                    if (!current.id.isEmpty() && !current.title.isEmpty()) papers.add(current);
                    current = null;
                }
            }
            event = parser.next();
        }
        return papers;
    }

    private static boolean isTextTag(String name) {
        return "id".equals(name) || "title".equals(name) || "summary".equals(name)
                || "published".equals(name) || "updated".equals(name) || "name".equals(name);
    }

    private static void applyText(Paper paper, String tag, String raw) {
        String value = PaperModels.normalize(raw);
        if ("id".equals(tag)) {
            paper.absUrl = value;
        } else if ("title".equals(tag)) {
            paper.title = value;
        } else if ("summary".equals(tag)) {
            paper.summary = value;
        } else if ("published".equals(tag)) {
            paper.published = value;
        } else if ("updated".equals(tag)) {
            paper.updated = value;
        } else if ("name".equals(tag) && paper.authors.size() < 10) {
            paper.authors.add(value);
        }
    }

    private static void handleLink(XmlPullParser parser, Paper paper) {
        String title = parser.getAttributeValue(null, "title");
        String type = parser.getAttributeValue(null, "type");
        String href = parser.getAttributeValue(null, "href");
        if (href == null) return;
        if ("pdf".equals(title) || "application/pdf".equals(type)) paper.pdfUrl = href;
    }

    private static void finishPaper(Paper paper, Preferences preferences) {
        String arxivId = paper.absUrl;
        int absIndex = arxivId.lastIndexOf("/abs/");
        if (absIndex >= 0) arxivId = arxivId.substring(absIndex + 5);
        else {
            int slash = arxivId.lastIndexOf('/');
            if (slash >= 0) arxivId = arxivId.substring(slash + 1);
        }
        paper.id = PaperModels.normalize(arxivId);
        paper.readerId = PaperModels.readerIdFromArxivId(paper.id);
        if (paper.pdfUrl.isEmpty() && !paper.id.isEmpty()) paper.pdfUrl = "https://arxiv.org/pdf/" + paper.id;
        paper.relevance = calculateRelevance(paper, preferences);
    }

    private static int calculateRelevance(Paper paper, Preferences preferences) {
        String haystack = (paper.title + " " + paper.summary + " " + paper.category).toLowerCase(Locale.US);
        int matched = 0;
        for (String keyword : preferences.keywords) {
            if (haystack.contains(keyword.toLowerCase(Locale.US))) matched++;
        }
        int categoryBoost = preferences.categories.contains(paper.category) ? 14 : 0;
        int keywordBoost = Math.min(matched * 16, 32);
        long ageDays = 30;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(paper.published);
            if (date != null) ageDays = Math.max(0, (System.currentTimeMillis() - date.getTime()) / 86400000L);
        } catch (Exception ignored) {
        }
        int recencyBoost = Math.max(0, 18 - (int) ageDays);
        return Math.max(38, Math.min(99, 45 + categoryBoost + keywordBoost + recencyBoost));
    }

    private static String readString(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String join(ArrayList<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
