package com.papercard.reader;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

final class PdfCache {
    private static final long MAX_PDF_BYTES = 80L * 1024L * 1024L;

    private PdfCache() {
    }

    static File fileFor(Context context, String readerId) throws Exception {
        File dir = new File(context.getCacheDir(), "pdfs");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 PDF 缓存目录");
        String safeReaderId = PaperModels.readerIdFromArxivId(readerId);
        if (safeReaderId.isEmpty()) throw new IllegalArgumentException("PDF 标识为空");
        return new File(dir, safeReaderId + ".pdf");
    }

    static File downloadIfNeeded(Context context, String readerId, String rawUrl) throws Exception {
        File outFile = fileFor(context, readerId);
        if (outFile.exists() && outFile.length() > 0) return outFile;

        URL url = validatedUrl(rawUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("User-Agent", "PaperCard/1.0 native-android");

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("PDF 下载失败：" + status);
        long contentLength = connection.getContentLengthLong();
        if (contentLength > MAX_PDF_BYTES) throw new IllegalStateException("PDF 文件过大");

        File tmp = new File(outFile.getParentFile(), outFile.getName() + "." + Thread.currentThread().getId() + ".tmp");
        long total = 0;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_PDF_BYTES) throw new IllegalStateException("PDF 文件过大");
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            tmp.delete();
            throw exception;
        } finally {
            connection.disconnect();
        }

        synchronized (PdfCache.class) {
            if (outFile.exists() && outFile.length() > 0) {
                tmp.delete();
                return outFile;
            }
            if (!tmp.renameTo(outFile)) {
                tmp.delete();
                throw new IllegalStateException("PDF 缓存写入失败");
            }
        }
        return outFile;
    }

    private static URL validatedUrl(String rawUrl) throws Exception {
        URL url = new URL(PaperModels.normalize(rawUrl));
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IllegalArgumentException("PDF 只允许通过 HTTPS 下载");
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        if (!"arxiv.org".equals(host) && !host.endsWith(".arxiv.org")) {
            throw new IllegalArgumentException("仅允许下载 arXiv PDF");
        }
        return url;
    }
}
