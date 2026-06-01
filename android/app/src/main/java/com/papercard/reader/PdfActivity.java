package com.papercard.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<ImageView> pageImages = new ArrayList<>();
    private LinearLayout body;
    private ScrollView vertical;
    private ImageView imageView;
    private TextView pageView;
    private TextView statusView;
    private ProgressBar progressBar;
    private ScaleGestureDetector scaleDetector;
    private File pdfFile;
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private int pageIndex = 0;
    private int renderGeneration = 0;
    private float zoom = 1f;
    private float touchStartX;
    private float touchStartY;
    private boolean continuousMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        continuousMode = "continuous".equals(getIntent().getStringExtra("readingMode"));
        buildUi();
        downloadIfNeeded();
    }

    @Override
    protected void onDestroy() {
        closeRenderer();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(232, 236, 239));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        vertical = new ScrollView(this);
        vertical.setFillViewport(true);
        body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(72), dp(12), dp(92));

        progressBar = new ProgressBar(this);
        statusView = new TextView(this);
        statusView.setText("正在载入 PDF");
        statusView.setTextColor(Color.rgb(104, 114, 116));
        statusView.setGravity(Gravity.CENTER);
        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setBackgroundColor(Color.WHITE);
        body.addView(progressBar);
        body.addView(statusView);
        body.addView(imageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vertical.addView(body);
        horizontal.addView(vertical);
        root.addView(horizontal, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(topOverlay(), overlayParams(Gravity.TOP));
        root.addView(zoomOverlay(), zoomOverlayParams());
        root.addView(bottomOverlay(), overlayParams(Gravity.BOTTOM));

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(0.75f, Math.min(2.6f, zoom * detector.getScaleFactor()));
                mainHandler.removeCallbacks(zoomRenderRunnable);
                mainHandler.postDelayed(zoomRenderRunnable, 120);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mainHandler.removeCallbacks(zoomRenderRunnable);
                renderCurrentMode();
            }
        });
        root.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
            } else if (event.getAction() == MotionEvent.ACTION_UP && !scaleDetector.isInProgress()) {
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (!continuousMode && Math.abs(dx) > dp(110) && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    if (dx < 0) nextPage();
                    else previousPage();
                }
            }
            return false;
        });

        setContentView(root);
    }

    private final Runnable zoomRenderRunnable = this::renderCurrentMode;

    private View topOverlay() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        bar.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextColor(Color.rgb(23, 33, 38));
        title.setTextSize(14);
        title.setMaxLines(1);
        title.setBackground(pill(Color.rgb(255, 253, 248)));
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button share = floatingButton("分享");
        share.setOnClickListener(view -> sharePdf());
        Button close = floatingButton("关闭");
        close.setOnClickListener(view -> finish());
        bar.addView(share);
        bar.addView(close);
        return bar;
    }

    private View zoomOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(pill(Color.rgb(255, 253, 248)));
        box.setElevation(dp(8));

        Button zoomOut = floatingButton("-");
        zoomOut.setContentDescription("缩小 PDF");
        zoomOut.setOnClickListener(view -> {
            zoom = Math.max(0.75f, zoom - 0.15f);
            renderCurrentMode();
        });
        Button zoomIn = floatingButton("+");
        zoomIn.setContentDescription("放大 PDF");
        zoomIn.setOnClickListener(view -> {
            zoom = Math.min(2.6f, zoom + 0.15f);
            renderCurrentMode();
        });

        box.addView(zoomOut);
        box.addView(zoomIn);
        return box;
    }

    private View bottomOverlay() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(10), dp(10), dp(10), dp(14));
        bar.setBackgroundColor(Color.TRANSPARENT);

        Button prev = floatingButton("‹");
        prev.setTextSize(24);
        prev.setOnClickListener(view -> previousPage());
        pageView = new TextView(this);
        pageView.setText("- / -");
        pageView.setTextColor(Color.rgb(23, 33, 38));
        pageView.setGravity(Gravity.CENTER);
        pageView.setBackground(pill(Color.rgb(255, 253, 248)));
        pageView.setPadding(dp(14), dp(9), dp(14), dp(9));
        Button next = floatingButton("›");
        next.setTextSize(24);
        next.setOnClickListener(view -> nextPage());
        Button save = floatingButton("保存");
        save.setOnClickListener(view -> saveCopy());

        bar.addView(prev);
        bar.addView(pageView);
        bar.addView(next);
        bar.addView(save);
        return bar;
    }

    private void downloadIfNeeded() {
        progressBar.setVisibility(View.VISIBLE);
        statusView.setText("正在下载 PDF");
        executor.execute(() -> {
            try {
                String readerId = PaperModels.readerIdFromArxivId(getIntent().getStringExtra("readerId"));
                pdfFile = PdfCache.downloadIfNeeded(this, readerId, getIntent().getStringExtra("pdfUrl"));
                runOnUiThread(this::openRenderer);
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusView.setText(exception.getMessage());
                });
            }
        });
    }

    private void openRenderer() {
        try {
            closeRenderer();
            descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            pageIndex = 0;
            renderCurrentMode();
        } catch (Exception exception) {
            progressBar.setVisibility(View.GONE);
            statusView.setText(exception.getMessage());
        }
    }

    private void renderPage() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        int generation = ++renderGeneration;
        progressBar.setVisibility(View.VISIBLE);
        statusView.setText("");
        executor.execute(() -> {
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                Bitmap bitmap = renderBitmap(page);
                runOnUiThread(() -> {
                    if (generation != renderGeneration) return;
                    clearContinuousPages();
                    imageView.setVisibility(View.VISIBLE);
                    imageView.setImageBitmap(bitmap);
                    pageView.setText((pageIndex + 1) + " / " + renderer.getPageCount());
                    progressBar.setVisibility(View.GONE);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showRenderError(generation, exception));
            }
        });
    }

    private void renderContinuous() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        int generation = ++renderGeneration;
        progressBar.setVisibility(View.VISIBLE);
        statusView.setText("");
        imageView.setVisibility(View.GONE);
        clearContinuousPages();
        pageView.setText("连续 · " + renderer.getPageCount() + " 页");
        executor.execute(() -> {
            for (int index = 0; index < renderer.getPageCount(); index++) {
                if (generation != renderGeneration) return;
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    Bitmap bitmap = renderBitmap(page);
                    runOnUiThread(() -> {
                        if (generation != renderGeneration) return;
                        ImageView pageImage = new ImageView(this);
                        pageImage.setAdjustViewBounds(true);
                        pageImage.setBackgroundColor(Color.WHITE);
                        pageImage.setImageBitmap(bitmap);
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        params.setMargins(0, 0, 0, dp(14));
                        body.addView(pageImage, params);
                        pageImages.add(pageImage);
                        progressBar.setVisibility(View.GONE);
                    });
                } catch (Exception exception) {
                    runOnUiThread(() -> showRenderError(generation, exception));
                    return;
                }
            }
        });
    }

    private void renderCurrentMode() {
        if (continuousMode) renderContinuous();
        else renderPage();
    }

    private Bitmap renderBitmap(PdfRenderer.Page page) {
        int baseWidth = Math.max(dp(320), getResources().getDisplayMetrics().widthPixels - dp(24));
        int targetWidth = Math.max(dp(260), (int) (baseWidth * zoom));
        int targetHeight = Math.max(1, targetWidth * page.getHeight() / Math.max(1, page.getWidth()));
        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
    }

    private void showRenderError(int generation, Exception exception) {
        if (generation != renderGeneration) return;
        progressBar.setVisibility(View.GONE);
        statusView.setText(exception.getMessage());
    }

    private void clearContinuousPages() {
        for (ImageView pageImage : pageImages) body.removeView(pageImage);
        pageImages.clear();
    }

    private void previousPage() {
        if (continuousMode) {
            vertical.smoothScrollBy(0, -Math.max(dp(280), vertical.getHeight() - dp(96)));
            return;
        }
        if (renderer != null && pageIndex > 0) {
            pageIndex--;
            renderPage();
        }
    }

    private void nextPage() {
        if (continuousMode) {
            vertical.smoothScrollBy(0, Math.max(dp(280), vertical.getHeight() - dp(96)));
            return;
        }
        if (renderer != null && pageIndex < renderer.getPageCount() - 1) {
            pageIndex++;
            renderPage();
        }
    }

    private void sharePdf() {
        if (pdfFile == null || !pdfFile.exists()) {
            Toast.makeText(this, "PDF 尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("content://" + PaperFileProvider.AUTHORITY + "/pdf/" + Uri.encode(pdfFile.getName()));
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享 PDF"));
    }

    private void saveCopy() {
        if (pdfFile == null || !pdfFile.exists()) return;
        executor.execute(() -> {
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IllegalStateException("无法访问下载目录");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建下载目录");
                File out = new File(dir, pdfFile.getName());
                try (java.nio.channels.FileChannel source = new java.io.FileInputStream(pdfFile).getChannel();
                     java.nio.channels.FileChannel target = new java.io.FileOutputStream(out).getChannel()) {
                    target.transferFrom(source, 0, source.size());
                }
                runOnUiThread(() -> Toast.makeText(this, "已保存到 " + out.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void closeRenderer() {
        renderGeneration++;
        try {
            if (renderer != null) renderer.close();
            if (descriptor != null) descriptor.close();
        } catch (Exception ignored) {
        }
        renderer = null;
        descriptor = null;
    }

    private Button floatingButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setTextColor(Color.rgb(23, 33, 38));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(pill(Color.rgb(255, 253, 248)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        button.setElevation(dp(6));
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.rgb(216, 221, 213));
        return drawable;
    }

    private FrameLayout.LayoutParams overlayParams(int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = gravity;
        return params;
    }

    private FrameLayout.LayoutParams zoomOverlayParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.setMargins(0, dp(62), dp(10), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
