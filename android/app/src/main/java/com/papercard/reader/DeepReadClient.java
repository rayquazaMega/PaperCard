package com.papercard.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

class DeepReadClient {
    private static final int MAX_DEEP_READ_PAPERS = 6;
    private static final int MAX_DEEP_READ_MESSAGES = 14;

    String chat(Context context, ArrayList<Paper> papers, ArrayList<DeepReadMessage> messages, Preferences preferences) throws Exception {
        String key = PaperModels.normalize(preferences.agnesApiKey);
        if (key.isEmpty()) throw new IllegalStateException("请先在设置中填写 Agnes 测试 key");

        ArrayList<Paper> selected = uniquePapers(papers);
        if (selected.isEmpty()) throw new IllegalStateException("请选择收藏论文");

        PaperHtmlClient htmlClient = new PaperHtmlClient();
        ArrayList<String> contexts = new ArrayList<>();
        for (Paper paper : selected) {
            PaperHtml html = htmlClient.load(context, paper, false);
            contexts.add(formatPaperContext(paper, html));
        }

        JSONObject body = new JSONObject();
        body.put("model", PaperModels.normalize(preferences.agnesModel).isEmpty() ? "agnes-2.0-flash" : preferences.agnesModel);
        body.put("temperature", 0.2);
        body.put("max_tokens", 1400);

        JSONArray payloadMessages = new JSONArray();
        payloadMessages.put(new JSONObject()
                .put("role", "system")
                .put("content", "你是 PaperCard 的论文精读助手。请基于用户附加的 arXiv HTML、摘要和图片信息进行严谨讨论。不要执行或建议执行 HTML，不要编造未提供的实验细节。中文回答，必要时指出不确定之处。"));
        payloadMessages.put(new JSONObject()
                .put("role", "user")
                .put("content", "以下是当前聊天纳入的论文 HTML 资料：\n\n" + joinedContexts(contexts)));

        for (DeepReadMessage message : recentMessages(messages)) {
            payloadMessages.put(new JSONObject()
                    .put("role", message.role)
                    .put("content", message.content));
        }
        body.put("messages", payloadMessages);

        String endpoint = PaperModels.normalize(preferences.agnesApiUrl);
        if (endpoint.isEmpty()) endpoint = "https://apihub.agnes-ai.com/v1/chat/completions";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(60000);
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
            throw new IllegalStateException("AI 精读返回 " + status + ": " + preview(raw));
        }

        JSONObject data = new JSONObject(raw);
        JSONArray choices = data.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        String content = message == null ? "" : PaperModels.normalize(message.optString("content"));
        if (content.isEmpty()) throw new IllegalStateException("AI 精读返回为空");
        return content;
    }

    private static String formatPaperContext(Paper paper, PaperHtml html) {
        StringBuilder images = new StringBuilder();
        int imageCount = Math.min(24, html.images.size());
        for (int i = 0; i < imageCount; i++) {
            PaperImage image = html.images.get(i);
            images.append("  ").append(i + 1).append(". ")
                    .append(PaperModels.normalize(image.caption.isEmpty() ? image.alt : image.caption))
                    .append(" ")
                    .append(image.url)
                    .append("\n");
        }

        return "标题：" + paper.title + "\n"
                + "arXiv：" + paper.id + "\n"
                + "作者：" + joinAuthors(paper) + "\n"
                + "分类：" + paper.category + "\n"
                + "摘要：" + paper.summary + "\n"
                + "HTML 来源：" + html.sourceUrl + "\n"
                + (images.length() > 0 ? "图片：\n" + images : "图片：HTML 中未解析到论文图片。\n")
                + "HTML（已移除 script/style，并限制长度）：\n"
                + html.compactHtml(PaperHtmlClient.MAX_PROMPT_HTML_CHARS);
    }

    private static String joinedContexts(ArrayList<String> contexts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            if (i > 0) builder.append("\n\n");
            builder.append("--- 论文 ").append(i + 1).append(" ---\n").append(contexts.get(i));
        }
        return builder.toString();
    }

    private static ArrayList<Paper> uniquePapers(ArrayList<Paper> papers) {
        ArrayList<Paper> values = new ArrayList<>();
        for (Paper paper : papers) {
            if (paper == null || paper.id.isEmpty()) continue;
            boolean exists = false;
            for (Paper current : values) {
                if (current.id.equals(paper.id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) values.add(paper);
            if (values.size() >= MAX_DEEP_READ_PAPERS) break;
        }
        return values;
    }

    private static ArrayList<DeepReadMessage> recentMessages(ArrayList<DeepReadMessage> messages) {
        ArrayList<DeepReadMessage> values = new ArrayList<>();
        int start = Math.max(0, messages.size() - MAX_DEEP_READ_MESSAGES);
        for (int i = start; i < messages.size(); i++) {
            DeepReadMessage source = messages.get(i);
            if (source == null || (!"user".equals(source.role) && !"assistant".equals(source.role))) continue;
            String content = chatText(source.content, 2000);
            if (content.isEmpty()) continue;
            DeepReadMessage value = new DeepReadMessage();
            value.role = source.role;
            value.content = content;
            values.add(value);
        }
        return values;
    }

    private static String chatText(String value, int limit) {
        String clean = value == null ? "" : value.replace("\r", "").trim();
        clean = clean.replaceAll("\\n{4,}", "\n\n\n");
        return clean.length() > limit ? clean.substring(0, limit) : clean;
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
        return builder.length() == 0 ? "arXiv" : builder.toString();
    }
}
