package com.papercard.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

class PaperStore {
    private static final String PREFS_NAME = "papercard.native.store";
    private static final String STORE_KEY = "store";

    Preferences preferences = Preferences.defaults();
    final ArrayList<Paper> papers = new ArrayList<>();
    final ArrayList<FavoriteFolder> favoriteFolders = new ArrayList<>();
    final JSONObject favorites = new JSONObject();
    final JSONObject skipped = new JSONObject();
    final JSONObject translations = new JSONObject();
    String lastFetchAt = "";
    String lastQuery = "";
    String lastError = "";

    private final SharedPreferences sharedPreferences;

    PaperStore(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    static String nowIso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    void load() {
        favoriteFolders.clear();
        favoriteFolders.add(FavoriteFolder.defaultFolder());
        String raw = sharedPreferences.getString(STORE_KEY, "");
        if (raw == null || raw.isEmpty()) return;

        try {
            JSONObject store = new JSONObject(raw);
            preferences = Preferences.fromJson(store.optJSONObject("preferences"));
            loadPapers(store.optJSONArray("papers"));
            loadFolders(store.optJSONArray("favoriteFolders"));
            copyObject(store.optJSONObject("favorites"), favorites);
            copyObject(store.optJSONObject("skipped"), skipped);
            copyObject(store.optJSONObject("translations"), translations);
            lastFetchAt = PaperModels.normalize(store.optString("lastFetchAt"));
            lastQuery = PaperModels.normalize(store.optString("lastQuery"));
            lastError = PaperModels.normalize(store.optString("lastError"));
        } catch (JSONException ignored) {
            preferences = Preferences.defaults();
            papers.clear();
            favoriteFolders.clear();
            favoriteFolders.add(FavoriteFolder.defaultFolder());
        }
    }

    void save() {
        try {
            JSONObject store = new JSONObject();
            store.put("preferences", preferences.toJson());
            JSONArray paperArray = new JSONArray();
            for (Paper paper : papers) paperArray.put(paper.toJson());
            store.put("papers", paperArray);
            JSONArray folderArray = new JSONArray();
            for (FavoriteFolder folder : favoriteFolders) folderArray.put(folder.toJson());
            store.put("favoriteFolders", folderArray);
            store.put("favorites", favorites);
            store.put("skipped", skipped);
            store.put("translations", translations);
            store.put("lastFetchAt", lastFetchAt);
            store.put("lastQuery", lastQuery);
            store.put("lastError", lastError);
            sharedPreferences.edit().putString(STORE_KEY, store.toString()).apply();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void clear() {
        sharedPreferences.edit().clear().commit();
        preferences = Preferences.defaults();
        papers.clear();
        favoriteFolders.clear();
        favoriteFolders.add(FavoriteFolder.defaultFolder());
        clearObject(favorites);
        clearObject(skipped);
        clearObject(translations);
        lastFetchAt = "";
        lastQuery = "";
        lastError = "";
    }

    boolean isStale() {
        if (lastFetchAt.isEmpty() || papers.isEmpty()) return true;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(lastFetchAt);
            return date == null || System.currentTimeMillis() - date.getTime() > 12L * 60L * 60L * 1000L;
        } catch (Exception ignored) {
            return true;
        }
    }

    void replacePapers(ArrayList<Paper> nextPapers, String query) {
        papers.clear();
        papers.addAll(nextPapers);
        lastQuery = query;
        lastFetchAt = nowIso();
        lastError = "";
        save();
    }

    ArrayList<Paper> visiblePapers() {
        ArrayList<Paper> values = new ArrayList<>();
        for (Paper paper : papers) values.add(withUserState(paper));
        return values;
    }

    ArrayList<Paper> queue() {
        ArrayList<Paper> unread = new ArrayList<>();
        ArrayList<Paper> seen = new ArrayList<>();
        for (Paper paper : papers) {
            Paper next = withUserState(paper);
            if (next.favoriteAt.isEmpty() && next.skippedAt.isEmpty()) unread.add(next);
            else seen.add(next);
        }
        ArrayList<Paper> values = new ArrayList<>();
        values.addAll(unread);
        values.addAll(seen);
        return values;
    }

    int unreadCount() {
        int count = 0;
        for (Paper paper : papers) {
            Paper next = withUserState(paper);
            if (next.favoriteAt.isEmpty() && next.skippedAt.isEmpty()) count++;
        }
        return count;
    }

    ArrayList<Paper> favoritePapers(String folderId) {
        ArrayList<Paper> values = new ArrayList<>();
        Iterator<String> keys = favorites.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = favorites.optJSONObject(key);
            if (entry == null) continue;
            String entryFolderId = PaperModels.normalize(entry.optString("folderId", PaperModels.DEFAULT_FOLDER_ID));
            if (!"all".equals(folderId) && !entryFolderId.equals(folderId)) continue;
            Paper paper = Paper.fromJson(entry.optJSONObject("paper"));
            values.add(withUserState(paper));
        }
        Collections.sort(values, (a, b) -> b.favoriteAt.compareTo(a.favoriteAt));
        return values;
    }

    Paper getPaper(String id) {
        for (Paper paper : papers) {
            if (paper.id.equals(id) || paper.readerId.equals(id)) return withUserState(paper);
        }
        Iterator<String> keys = favorites.keys();
        while (keys.hasNext()) {
            JSONObject entry = favorites.optJSONObject(keys.next());
            Paper paper = Paper.fromJson(entry == null ? null : entry.optJSONObject("paper"));
            if (paper.id.equals(id) || paper.readerId.equals(id)) return withUserState(paper);
        }
        return null;
    }

    Paper withUserState(Paper source) {
        Paper paper = source.copy();
        JSONObject favorite = favorites.optJSONObject(paper.id);
        JSONObject skip = skipped.optJSONObject(paper.id);
        JSONObject translationEntry = translations.optJSONObject(paper.id);
        paper.favoriteAt = PaperModels.normalize(favorite == null ? "" : favorite.optString("favoriteAt"));
        paper.favoriteFolderId = PaperModels.normalize(favorite == null ? "" : favorite.optString("folderId", PaperModels.DEFAULT_FOLDER_ID));
        paper.skippedAt = PaperModels.normalize(skip == null ? "" : skip.optString("skippedAt"));
        paper.translation = translationEntry == null ? null : Translation.fromJson(translationEntry.optJSONObject("translation"));
        return paper;
    }

    void favorite(Paper paper, String folderId) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("paper", paper.toJson());
            entry.put("favoriteAt", nowIso());
            entry.put("folderId", validFolderId(folderId));
            favorites.put(paper.id, entry);
            skipped.remove(paper.id);
            save();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void skip(Paper paper) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("paperId", paper.id);
            entry.put("skippedAt", nowIso());
            skipped.put(paper.id, entry);
            favorites.remove(paper.id);
            save();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void clearAction(Paper paper) {
        favorites.remove(paper.id);
        skipped.remove(paper.id);
        save();
    }

    void saveTranslation(Paper paper, Translation translation) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("paperId", paper.id);
            entry.put("translatedAt", nowIso());
            entry.put("translation", translation.toJson());
            translations.put(paper.id, entry);
            save();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    FavoriteFolder createFolder(String name) {
        String clean = PaperModels.normalize(name);
        if (clean.isEmpty()) throw new IllegalArgumentException("文件夹名称不能为空");
        if (clean.length() > 24) clean = clean.substring(0, 24);
        for (FavoriteFolder folder : favoriteFolders) {
            if (folder.name.equals(clean)) throw new IllegalArgumentException("文件夹已存在");
        }
        FavoriteFolder folder = new FavoriteFolder(UUID.randomUUID().toString(), clean, nowIso());
        favoriteFolders.add(folder);
        save();
        return folder;
    }

    void deleteFolder(String folderId) {
        if (PaperModels.DEFAULT_FOLDER_ID.equals(folderId)) throw new IllegalArgumentException("默认收藏不能删除");
        for (int i = favoriteFolders.size() - 1; i >= 0; i--) {
            if (favoriteFolders.get(i).id.equals(folderId)) favoriteFolders.remove(i);
        }
        Iterator<String> keys = favorites.keys();
        while (keys.hasNext()) {
            JSONObject entry = favorites.optJSONObject(keys.next());
            if (entry != null && folderId.equals(entry.optString("folderId"))) {
                try {
                    entry.put("folderId", PaperModels.DEFAULT_FOLDER_ID);
                } catch (JSONException ignored) {
                }
            }
        }
        ensureDefaultFolder();
        save();
    }

    String folderName(String folderId) {
        for (FavoriteFolder folder : favoriteFolders) {
            if (folder.id.equals(folderId)) return folder.name;
        }
        return "默认收藏";
    }

    String validFolderId(String folderId) {
        for (FavoriteFolder folder : favoriteFolders) {
            if (folder.id.equals(folderId)) return folder.id;
        }
        return PaperModels.DEFAULT_FOLDER_ID;
    }

    int favoriteCount() {
        return favorites.length();
    }

    int skippedCount() {
        return skipped.length();
    }

    String bibtex(String folderId) {
        StringBuilder builder = new StringBuilder();
        ArrayList<Paper> values = favoritePapers(folderId);
        for (int i = 0; i < values.size(); i++) {
            Paper paper = values.get(i);
            String key = "arxiv" + paper.id.replaceAll("[^0-9A-Za-z]", "");
            if (i > 0) builder.append("\n\n");
            builder.append("@article{").append(key).append(",\n")
                    .append("  title={").append(paper.title).append("},\n")
                    .append("  author={").append(joinAuthors(paper.authors)).append("},\n")
                    .append("  year={").append(yearFromDate(paper.published)).append("},\n")
                    .append("  eprint={").append(paper.id).append("},\n")
                    .append("  archivePrefix={arXiv},\n")
                    .append("  primaryClass={").append(paper.category).append("},\n")
                    .append("  url={").append(paper.absUrl).append("}\n")
                    .append("}");
        }
        return builder.toString();
    }

    private void loadPapers(JSONArray array) {
        papers.clear();
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) papers.add(Paper.fromJson(array.optJSONObject(i)));
    }

    private void loadFolders(JSONArray array) {
        favoriteFolders.clear();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                FavoriteFolder folder = FavoriteFolder.fromJson(array.optJSONObject(i));
                if (!folder.id.isEmpty() && !folder.name.isEmpty()) favoriteFolders.add(folder);
            }
        }
        ensureDefaultFolder();
    }

    private void ensureDefaultFolder() {
        boolean hasDefault = false;
        for (FavoriteFolder folder : favoriteFolders) {
            if (PaperModels.DEFAULT_FOLDER_ID.equals(folder.id)) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault) favoriteFolders.add(0, FavoriteFolder.defaultFolder());
        Collections.sort(favoriteFolders, Comparator.comparing(folder -> PaperModels.DEFAULT_FOLDER_ID.equals(folder.id) ? "" : folder.createdAt));
    }

    private static void copyObject(JSONObject source, JSONObject target) {
        clearObject(target);
        if (source == null) return;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                target.put(key, source.opt(key));
            } catch (JSONException ignored) {
            }
        }
    }

    private static void clearObject(JSONObject object) {
        Iterator<String> keys = object.keys();
        ArrayList<String> names = new ArrayList<>();
        while (keys.hasNext()) names.add(keys.next());
        for (String name : names) object.remove(name);
    }

    private static String joinAuthors(ArrayList<String> authors) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < authors.size(); i++) {
            if (i > 0) builder.append(" and ");
            builder.append(authors.get(i));
        }
        return builder.toString();
    }

    private static String yearFromDate(String date) {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }
}
