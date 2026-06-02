package com.papercard.reader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PaperModels {
    static final String DEFAULT_FOLDER_ID = "default";

    private PaperModels() {
    }

    static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    static JSONArray stringsToJson(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    static List<String> stringsFromJson(JSONArray array) {
        ArrayList<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            String value = normalize(array.optString(i));
            if (!value.isEmpty() && !values.contains(value)) values.add(value);
        }
        return values;
    }

    static String readerIdFromArxivId(String arxivId) {
        return normalize(arxivId).replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

class Preferences {
    final ArrayList<String> keywords = new ArrayList<>();
    final ArrayList<String> categories = new ArrayList<>();
    int maxResults = 36;
    String language = "zh-CN";
    String agnesApiKey = BuildConfig.DEFAULT_AGNES_API_KEY;
    String agnesApiUrl = BuildConfig.DEFAULT_AGNES_API_URL;
    String agnesModel = BuildConfig.DEFAULT_AGNES_MODEL;
    String pdfReadingMode = "paged";

    static Preferences defaults() {
        Preferences value = new Preferences();
        value.keywords.add("large language model");
        value.keywords.add("retrieval augmented generation");
        value.keywords.add("agent");
        value.categories.add("cs.AI");
        value.categories.add("cs.CL");
        value.categories.add("cs.LG");
        return value;
    }

    static Preferences fromJson(JSONObject json) {
        Preferences value = defaults();
        if (json == null) return value;
        value.keywords.clear();
        value.keywords.addAll(PaperModels.stringsFromJson(json.optJSONArray("keywords")));
        if (value.keywords.isEmpty()) value.keywords.addAll(defaults().keywords);
        value.categories.clear();
        value.categories.addAll(PaperModels.stringsFromJson(json.optJSONArray("categories")));
        if (value.categories.isEmpty()) value.categories.addAll(defaults().categories);
        value.maxResults = Math.max(12, Math.min(80, json.optInt("maxResults", 36)));
        value.language = PaperModels.normalize(json.optString("language", "zh-CN"));
        value.agnesApiKey = json.optString("agnesApiKey", BuildConfig.DEFAULT_AGNES_API_KEY);
        value.agnesApiUrl = json.optString("agnesApiUrl", BuildConfig.DEFAULT_AGNES_API_URL);
        value.agnesModel = json.optString("agnesModel", BuildConfig.DEFAULT_AGNES_MODEL);
        value.pdfReadingMode = "continuous".equals(json.optString("pdfReadingMode")) ? "continuous" : "paged";
        return value;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("keywords", PaperModels.stringsToJson(keywords));
        json.put("categories", PaperModels.stringsToJson(categories));
        json.put("maxResults", maxResults);
        json.put("language", language);
        json.put("agnesApiKey", agnesApiKey);
        json.put("agnesApiUrl", agnesApiUrl);
        json.put("agnesModel", agnesModel);
        json.put("pdfReadingMode", pdfReadingMode);
        return json;
    }
}

class FavoriteFolder {
    String id;
    String name;
    String createdAt;

    FavoriteFolder(String id, String name, String createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    static FavoriteFolder defaultFolder() {
        return new FavoriteFolder(PaperModels.DEFAULT_FOLDER_ID, "默认收藏", "2026-01-01T00:00:00.000Z");
    }

    static FavoriteFolder fromJson(JSONObject json) {
        if (json == null) return defaultFolder();
        return new FavoriteFolder(
                PaperModels.normalize(json.optString("id", PaperModels.DEFAULT_FOLDER_ID)),
                PaperModels.normalize(json.optString("name", "默认收藏")),
                PaperModels.normalize(json.optString("createdAt", ""))
        );
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("createdAt", createdAt);
        return json;
    }
}

class Translation {
    String titleZh = "";
    String summaryZh = "";
    final ArrayList<String> keyPoints = new ArrayList<>();
    String readingNote = "";

    static Translation fromJson(JSONObject json) {
        Translation value = new Translation();
        if (json == null) return value;
        value.titleZh = PaperModels.normalize(json.optString("title_zh"));
        value.summaryZh = PaperModels.normalize(json.optString("summary_zh"));
        value.keyPoints.addAll(PaperModels.stringsFromJson(json.optJSONArray("key_points")));
        value.readingNote = PaperModels.normalize(json.optString("reading_note"));
        return value;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("title_zh", titleZh);
        json.put("summary_zh", summaryZh);
        json.put("key_points", PaperModels.stringsToJson(keyPoints));
        json.put("reading_note", readingNote);
        return json;
    }
}

class Paper {
    String id = "";
    String readerId = "";
    String title = "";
    String summary = "";
    final ArrayList<String> authors = new ArrayList<>();
    String published = "";
    String updated = "";
    String category = "arXiv";
    String absUrl = "";
    String pdfUrl = "";
    int relevance = 0;
    String favoriteAt = "";
    String favoriteFolderId = "";
    String skippedAt = "";
    Translation translation = null;

    Paper copy() {
        Paper value = new Paper();
        value.id = id;
        value.readerId = readerId;
        value.title = title;
        value.summary = summary;
        value.authors.addAll(authors);
        value.published = published;
        value.updated = updated;
        value.category = category;
        value.absUrl = absUrl;
        value.pdfUrl = pdfUrl;
        value.relevance = relevance;
        value.favoriteAt = favoriteAt;
        value.favoriteFolderId = favoriteFolderId;
        value.skippedAt = skippedAt;
        value.translation = translation;
        return value;
    }

    static Paper fromJson(JSONObject json) {
        Paper value = new Paper();
        if (json == null) return value;
        value.id = PaperModels.normalize(json.optString("id"));
        value.readerId = PaperModels.normalize(json.optString("readerId", PaperModels.readerIdFromArxivId(value.id)));
        value.title = PaperModels.normalize(json.optString("title"));
        value.summary = PaperModels.normalize(json.optString("summary"));
        value.authors.addAll(PaperModels.stringsFromJson(json.optJSONArray("authors")));
        value.published = PaperModels.normalize(json.optString("published"));
        value.updated = PaperModels.normalize(json.optString("updated"));
        value.category = PaperModels.normalize(json.optString("category", "arXiv"));
        value.absUrl = PaperModels.normalize(json.optString("absUrl"));
        value.pdfUrl = PaperModels.normalize(json.optString("pdfUrl"));
        value.relevance = json.optInt("relevance", 0);
        value.favoriteAt = PaperModels.normalize(json.optString("favoriteAt"));
        value.favoriteFolderId = PaperModels.normalize(json.optString("favoriteFolderId"));
        value.skippedAt = PaperModels.normalize(json.optString("skippedAt"));
        if (json.has("translation") && !json.isNull("translation")) {
            value.translation = Translation.fromJson(json.optJSONObject("translation"));
        }
        return value;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("readerId", readerId);
        json.put("title", title);
        json.put("summary", summary);
        json.put("authors", PaperModels.stringsToJson(authors));
        json.put("published", published);
        json.put("updated", updated);
        json.put("category", category);
        json.put("absUrl", absUrl);
        json.put("pdfUrl", pdfUrl);
        json.put("relevance", relevance);
        if (!favoriteAt.isEmpty()) json.put("favoriteAt", favoriteAt);
        if (!favoriteFolderId.isEmpty()) json.put("favoriteFolderId", favoriteFolderId);
        if (!skippedAt.isEmpty()) json.put("skippedAt", skippedAt);
        if (translation != null) json.put("translation", translation.toJson());
        return json;
    }
}

class PaperImage {
    String url = "";
    String alt = "";
    String caption = "";

    PaperImage(String url, String alt, String caption) {
        this.url = PaperModels.normalize(url);
        this.alt = PaperModels.normalize(alt);
        this.caption = PaperModels.normalize(caption);
    }
}

class PaperHtml {
    String paperId = "";
    String readerId = "";
    String sourceUrl = "";
    String fetchedAt = "";
    String html = "";
    int htmlLength = 0;
    boolean imageTruncated = false;
    final ArrayList<PaperImage> images = new ArrayList<>();

    String compactHtml(int limit) {
        return PaperHtmlClient.compactHtml(html, limit);
    }
}

class DeepReadMessage {
    String id = "";
    String role = "user";
    String content = "";
    String createdAt = "";
    final ArrayList<String> paperIds = new ArrayList<>();
}

class DeepReadThread {
    String id = "";
    String title = "";
    final ArrayList<String> paperIds = new ArrayList<>();
    final ArrayList<DeepReadMessage> messages = new ArrayList<>();
    String createdAt = "";
    String updatedAt = "";
}
