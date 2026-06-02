package com.papercard.reader;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PaperHtmlClient {
    static final int MAX_UI_HTML_CHARS = 30000;
    static final int MAX_PROMPT_HTML_CHARS = 22000;
    private static final long MAX_HTML_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_PAPER_IMAGES = 80;

    PaperHtml load(Context context, Paper paper, boolean force) throws Exception {
        File dir = new File(context.getCacheDir(), "htmls");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 HTML 缓存目录");

        String safeReaderId = PaperModels.readerIdFromArxivId(paper.readerId.isEmpty() ? paper.id : paper.readerId);
        if (safeReaderId.isEmpty()) throw new IllegalArgumentException("HTML 标识为空");
        File htmlFile = new File(dir, safeReaderId + ".html");
        File metaFile = new File(dir, safeReaderId + ".json");

        if (!force && htmlFile.exists() && htmlFile.length() > 0) {
            String html = readFile(htmlFile);
            String sourceUrl = "";
            String fetchedAt = "";
            try {
                JSONObject meta = new JSONObject(readFile(metaFile));
                sourceUrl = PaperModels.normalize(meta.optString("sourceUrl"));
                fetchedAt = PaperModels.normalize(meta.optString("fetchedAt"));
            } catch (Exception ignored) {
            }
            if (sourceUrl.isEmpty()) sourceUrl = firstCandidate(paper);
            return payload(paper, html, sourceUrl, fetchedAt);
        }

        FetchResult fetched = fetchHtml(paper);
        String fetchedAt = PaperStore.nowIso();
        writeAtomic(htmlFile, fetched.html);
        JSONObject meta = new JSONObject()
                .put("sourceUrl", fetched.sourceUrl)
                .put("fetchedAt", fetchedAt);
        writeAtomic(metaFile, meta.toString());
        return payload(paper, fetched.html, fetched.sourceUrl, fetchedAt);
    }

    static String compactHtml(String html, int limit) {
        if (limit <= 0) return "";
        String clean = html == null ? "" : html;
        clean = clean.replaceAll("(?is)<(script|style|noscript|template|svg|canvas|form|nav|header|footer|aside)\\b[\\s\\S]*?</\\1>", " ");
        clean = clean.replaceAll("(?is)<(meta|link|button|input|select|option|textarea)\\b[^>]*>", " ");
        clean = clean.replaceAll("(?s)<!--[\\s\\S]*?-->", " ");
        clean = clean.replaceAll("(?is)<\\s*(br|hr)\\s*/?\\s*>", "\n");
        clean = clean.replaceAll("(?is)</?(h[1-6]|p|div|section|article|li|ul|ol|figcaption|figure|table|tr|td|th|blockquote|pre|code)\\b[^>]*>", "\n");
        clean = clean.replaceAll("(?is)<[^>]+>", " ");
        clean = decodeHtmlEntities(clean).replace('\u00a0', ' ');

        StringBuilder builder = new StringBuilder();
        HashSet<String> seen = new HashSet<>();
        String[] lines = clean.split("\\R+");
        for (String rawLine : lines) {
            String line = PaperModels.normalize(rawLine);
            if (isNoisyHtmlLine(line)) continue;
            if (line.length() > 1800) line = line.substring(0, 1800);
            String key = line.toLowerCase(Locale.US);
            if (seen.contains(key)) continue;
            seen.add(key);

            int remaining = limit - builder.length();
            if (remaining <= 0) break;
            if (builder.length() > 0) {
                builder.append("\n");
                remaining--;
            }
            if (line.length() > remaining) {
                builder.append(line, 0, Math.max(0, remaining));
                break;
            }
            builder.append(line);
        }
        return builder.toString().trim();
    }

    private static boolean isNoisyHtmlLine(String line) {
        if (line == null || line.length() < 2) return true;
        String lower = line.toLowerCase(Locale.US);
        if (lower.matches("^[\\W_]+$")) return true;
        if (lower.matches("^(html|body|article|section|figure|figcaption|navigation|menu)$")) return true;
        if (lower.contains("skip to main content")) return true;
        if (lower.contains("download pdf") || lower.equals("pdf") || lower.equals("view pdf")) return true;
        if (lower.contains("download source") || lower.contains("source files")) return true;
        if (lower.contains("arxiv labs") || lower.contains("about arxiv")) return true;
        if (lower.contains("browse by topic") || lower.contains("subscribe to")) return true;
        if (lower.contains("privacy policy") || lower.contains("terms of use")) return true;
        if (lower.contains("share this paper") || lower.contains("copy link")) return true;
        return false;
    }

    static boolean isAllowedArxivHost(String hostname) {
        String host = PaperModels.normalize(hostname).toLowerCase(Locale.US);
        return "arxiv.org".equals(host) || host.endsWith(".arxiv.org");
    }

    private static PaperHtml payload(Paper paper, String html, String sourceUrl, String fetchedAt) {
        PaperHtml value = new PaperHtml();
        value.paperId = paper.id;
        value.readerId = paper.readerId;
        value.sourceUrl = PaperModels.normalize(sourceUrl);
        value.fetchedAt = PaperModels.normalize(fetchedAt);
        value.html = html == null ? "" : html;
        value.htmlLength = value.html.length();
        value.images.addAll(extractPaperImages(value.html, value.sourceUrl));
        value.imageTruncated = value.images.size() >= MAX_PAPER_IMAGES;
        return value;
    }

    private static FetchResult fetchHtml(Paper paper) throws Exception {
        ArrayList<String> errors = new ArrayList<>();
        for (String candidate : htmlCandidates(paper)) {
            HttpURLConnection connection = null;
            try {
                URL url = validatedHtmlUrl(candidate);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(18000);
                connection.setReadTimeout(45000);
                connection.setRequestProperty("User-Agent", "PaperCard/1.0 native-android");

                int status = connection.getResponseCode();
                String sourceUrl = validatedHtmlUrl(connection.getURL().toString()).toString();
                if (status < 200 || status >= 300) {
                    errors.add(sourceUrl + ": " + status);
                    continue;
                }

                String contentType = PaperModels.normalize(connection.getContentType()).toLowerCase(Locale.US);
                if (!contentType.isEmpty() && !contentType.contains("text/html")) {
                    errors.add(sourceUrl + ": unsupported content type " + contentType);
                    continue;
                }

                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_HTML_BYTES) throw new IllegalStateException("HTML 文件过大");
                return new FetchResult(readLimited(connection), sourceUrl);
            } catch (Exception exception) {
                errors.add(exception.getMessage() == null ? exception.toString() : exception.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IllegalStateException(errors.isEmpty() ? "未找到可用 HTML 来源" : "HTML 获取失败：" + errors.get(0));
    }

    private static URL validatedHtmlUrl(String rawUrl) throws Exception {
        URL url = new URL(PaperModels.normalize(rawUrl));
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !isAllowedArxivHost(url.getHost())) {
            throw new IllegalArgumentException("仅允许 HTTPS arXiv HTML 来源");
        }
        return url;
    }

    private static ArrayList<String> htmlCandidates(Paper paper) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        String idPath = arxivIdPath(paper.id.isEmpty() ? paper.readerId : paper.id);
        try {
            URL absUrl = new URL(PaperModels.normalize(paper.absUrl.isEmpty() ? "https://arxiv.org/abs/" + idPath : paper.absUrl));
            if ("https".equalsIgnoreCase(absUrl.getProtocol()) && isAllowedArxivHost(absUrl.getHost())) {
                String path = absUrl.getPath() == null ? "" : absUrl.getPath();
                String htmlPath = path.contains("/abs/") ? path.replace("/abs/", "/html/") : "/html/" + idPath;
                values.add(new URL("https://arxiv.org" + htmlPath).toString());
            }
        } catch (Exception ignored) {
        }
        if (!idPath.isEmpty()) {
            values.add("https://arxiv.org/html/" + idPath);
            values.add("https://ar5iv.labs.arxiv.org/html/" + idPath);
        }

        ArrayList<String> unique = new ArrayList<>();
        for (String value : values) {
            if (!unique.contains(value)) unique.add(value);
        }
        return unique;
    }

    private static String firstCandidate(Paper paper) {
        try {
            ArrayList<String> candidates = htmlCandidates(paper);
            return candidates.isEmpty() ? paper.absUrl : candidates.get(0);
        } catch (Exception ignored) {
            return paper.absUrl;
        }
    }

    private static String arxivIdPath(String arxivId) throws Exception {
        String[] parts = PaperModels.normalize(arxivId).split("/");
        ArrayList<String> encoded = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) encoded.add(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        return join(encoded, "/");
    }

    private static ArrayList<PaperImage> extractPaperImages(String html, String sourceUrl) {
        ArrayList<PaperImage> images = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        Pattern figurePattern = Pattern.compile("<figure\\b[\\s\\S]*?</figure>", Pattern.CASE_INSENSITIVE);
        Pattern captionPattern = Pattern.compile("<figcaption\\b[\\s\\S]*?</figcaption>", Pattern.CASE_INSENSITIVE);
        Pattern imagePattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);

        Matcher figures = figurePattern.matcher(html == null ? "" : html);
        while (figures.find()) {
            String figure = figures.group();
            Matcher captionMatcher = captionPattern.matcher(figure);
            String caption = captionMatcher.find() ? captionMatcher.group() : "";
            Matcher imageMatcher = imagePattern.matcher(figure);
            while (imageMatcher.find()) {
                addImage(images, seen, imageMatcher.group(), caption, sourceUrl);
                if (images.size() >= MAX_PAPER_IMAGES) return images;
            }
        }

        Matcher imageMatcher = imagePattern.matcher(html == null ? "" : html);
        while (imageMatcher.find()) {
            addImage(images, seen, imageMatcher.group(), "", sourceUrl);
            if (images.size() >= MAX_PAPER_IMAGES) return images;
        }
        return images;
    }

    private static void addImage(ArrayList<PaperImage> images, HashSet<String> seen, String tag, String caption, String sourceUrl) {
        String url = normalizeImageUrl(firstAttr(tag, "src", "data-src"), sourceUrl);
        if (url.isEmpty() || seen.contains(url)) return;
        seen.add(url);
        images.add(new PaperImage(
                url,
                stripTags(attr(tag, "alt"), 180),
                stripTags(caption, 260)
        ));
    }

    private static String firstAttr(String tag, String first, String second) {
        String value = attr(tag, first);
        return value.isEmpty() ? attr(tag, second) : value;
    }

    private static String attr(String tag, String name) {
        Pattern pattern = Pattern.compile(name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(tag == null ? "" : tag);
        if (!matcher.find()) return "";
        for (int i = 1; i <= 3; i++) {
            String value = matcher.group(i);
            if (value != null) return decodeHtmlEntities(value);
        }
        return "";
    }

    private static String normalizeImageUrl(String rawUrl, String sourceUrl) {
        String raw = PaperModels.normalize(decodeHtmlEntities(rawUrl));
        if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:")) return "";
        try {
            URL url = new URL(new URL(sourceUrl), raw);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !isAllowedArxivHost(url.getHost())) return "";
            String path = url.getPath() == null ? "" : url.getPath();
            if (path.contains("/static/browse/") || path.toLowerCase(Locale.US).contains("arxiv-logo")) return "";
            return url.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String stripTags(String value, int limit) {
        String clean = decodeHtmlEntities((value == null ? "" : value).replaceAll("<[^>]+>", " "));
        clean = PaperModels.normalize(clean);
        return clean.length() > limit ? clean.substring(0, limit) : clean;
    }

    private static String decodeHtmlEntities(String value) {
        String clean = value == null ? "" : value;
        clean = clean.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");

        Matcher matcher = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(clean);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            try {
                String code = matcher.group(1);
                int point = code.startsWith("x") || code.startsWith("X")
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(new String(Character.toChars(point))));
            } catch (Exception ignored) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String readLimited(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_HTML_BYTES) throw new IllegalStateException("HTML 文件过大");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeAtomic(File file, String value) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            tmp.delete();
            throw exception;
        }
        if (file.exists() && !file.delete()) throw new IllegalStateException("无法更新 HTML 缓存");
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IllegalStateException("HTML 缓存写入失败");
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

    private static class FetchResult {
        final String html;
        final String sourceUrl;

        FetchResult(String html, String sourceUrl) {
            this.html = html;
            this.sourceUrl = sourceUrl;
        }
    }
}
