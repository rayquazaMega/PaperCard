package com.papercard.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

class AgnesClient {
    Translation translate(Paper paper, Preferences preferences) throws Exception {
        String key = PaperModels.normalize(preferences.agnesApiKey);
        if (key.isEmpty()) throw new IllegalStateException("请先在设置中填写 Agnes 测试 key");

        JSONObject body = new JSONObject();
        body.put("model", PaperModels.normalize(preferences.agnesModel).isEmpty() ? "agnes-2.0-flash" : preferences.agnesModel);
        body.put("temperature", 0.2);
        body.put("max_tokens", 900);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", "你是严谨的科研论文助手。请只返回合法 JSON，不要使用 Markdown，不要添加 JSON 之外的解释。"));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", "请把下面 arXiv 论文转换成简体中文读者卡片。字段必须包含 title_zh、summary_zh、key_points。"
                        + "key_points 是 3 条以内的中文字符串数组。不要生成适合人群、阅读建议或“适合 XX 人员阅读”之类内容，直接总结论文即可。\n\n"
                        + "标题：" + paper.title + "\n"
                        + "作者：" + joinAuthors(paper) + "\n"
                        + "分类：" + paper.category + "\n"
                        + "摘要：" + paper.summary));
        body.put("messages", messages);

        String endpoint = PaperModels.normalize(preferences.agnesApiUrl);
        if (endpoint.isEmpty()) endpoint = "https://apihub.agnes-ai.com/v1/chat/completions";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(45000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int status = connection.getResponseCode();
        String raw = readString(connection, status >= 200 && status < 300);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Agnes 返回 " + status + ": " + preview(raw));
        }

        JSONObject data = new JSONObject(raw);
        String content = data.optJSONArray("choices")
                .optJSONObject(0)
                .optJSONObject("message")
                .optString("content");
        if (PaperModels.normalize(content).isEmpty()) throw new IllegalStateException("Agnes 返回为空");
        return parseTranslation(content);
    }

    private static Translation parseTranslation(String content) throws Exception {
        String clean = content.trim();
        JSONObject json;
        try {
            json = new JSONObject(clean);
        } catch (Exception first) {
            int start = clean.indexOf('{');
            int end = clean.lastIndexOf('}');
            if (start < 0 || end <= start) throw first;
            json = new JSONObject(clean.substring(start, end + 1));
        }
        Translation translation = Translation.fromJson(json);
        if (translation.keyPoints.size() > 3) {
            while (translation.keyPoints.size() > 3) translation.keyPoints.remove(translation.keyPoints.size() - 1);
        }
        return translation;
    }

    private static String readString(HttpURLConnection connection, boolean ok) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(ok ? connection.getInputStream() : connection.getErrorStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String preview(String raw) {
        String clean = PaperModels.normalize(raw);
        return clean.length() > 240 ? clean.substring(0, 240) : clean;
    }

    private static String joinAuthors(Paper paper) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paper.authors.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append(paper.authors.get(i));
        }
        return builder.toString();
    }
}
