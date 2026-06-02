package com.papercard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TAB_TODAY = 0;
    private static final int TAB_FAVORITES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_DEEP_READ = 3;
    private static final int COLOR_BG = 0xFFF0F3F6;
    private static final int COLOR_PANEL = 0xFFFFFEFA;
    private static final int COLOR_PANEL_SOFT = 0xFFF7F9F8;
    private static final int COLOR_INK = 0xFF172126;
    private static final int COLOR_MUTED = 0xFF687274;
    private static final int COLOR_LINE = 0xFFD8E0E4;
    private static final int COLOR_BLUE = 0xFF31596C;
    private static final int COLOR_BRICK = 0xFF8B4B3F;
    private static final int COLOR_GREEN = 0xFF2F5F46;

    private PaperStore store;
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService pdfPrefetchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService htmlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService deepReadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final HashSet<String> autoTranslationTried = new HashSet<>();
    private final HashSet<String> translatingIds = new HashSet<>();
    private final HashSet<String> queuedTranslationIds = new HashSet<>();
    private final HashSet<String> queuedPdfIds = new HashSet<>();
    private final HashSet<String> queuedHtmlIds = new HashSet<>();
    private final HashSet<String> loadingHtmlIds = new HashSet<>();
    private final HashMap<String, PaperHtml> htmlByPaperId = new HashMap<>();
    private final HashMap<String, Bitmap> imageCache = new HashMap<>();
    private final HashSet<String> loadingImageUrls = new HashSet<>();
    private final ArrayList<DeepReadThread> deepReadThreads = new ArrayList<>();
    private final HashSet<String> deepReadSelectedIds = new HashSet<>();
    private final HashSet<String> busyDeepReadThreadIds = new HashSet<>();
    private LinearLayout root;
    private FrameLayout content;
    private final ArrayList<String> reviewQueueIds = new ArrayList<>();
    private int activeTab = TAB_TODAY;
    private String activeDeepReadThreadId = "";
    private String deepReadDraft = "";
    private int cursor = 0;
    private String activeFolderId = PaperModels.DEFAULT_FOLDER_ID;
    private String favoriteFilterId = "all";
    private Paper lastAction;
    private boolean busy = false;
    private String busyText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PaperStore(this);
        buildShell();
        render();
        if (store.isStale()) syncPapers(false);
    }

    @Override
    protected void onDestroy() {
        syncExecutor.shutdownNow();
        translationExecutor.shutdownNow();
        pdfPrefetchExecutor.shutdownNow();
        htmlExecutor.shutdownNow();
        deepReadExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        setContentView(root);
    }

    private void render() {
        root.removeAllViews();
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(tabBar());
        if (busy) {
            renderBusy();
        } else if (activeTab == TAB_TODAY) {
            renderToday();
        } else if (activeTab == TAB_FAVORITES) {
            renderFavorites();
        } else if (activeTab == TAB_SETTINGS) {
            renderSettings();
        } else {
            renderDeepRead();
        }
    }

    private View tabBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(10));
        tabs.setBackgroundColor(COLOR_PANEL);
        tabs.setElevation(dp(8));
        tabs.addView(tabButton("今日", TAB_TODAY), weight());
        tabs.addView(tabButton("收藏", TAB_FAVORITES), weight());
        tabs.addView(tabButton("设置", TAB_SETTINGS), weight());
        tabs.addView(tabButton("精读", TAB_DEEP_READ), weight());
        return tabs;
    }

    private Button tabButton(String label, int tab) {
        Button button = button(label);
        button.setTextColor(activeTab == tab ? Color.WHITE : COLOR_MUTED);
        button.setBackground(cardBackground(activeTab == tab ? COLOR_INK : Color.TRANSPARENT, Color.TRANSPARENT, dp(8)));
        button.setOnClickListener(view -> {
            activeTab = tab;
            render();
        });
        return button;
    }

    private void renderBusy() {
        LinearLayout view = center();
        ProgressBar progressBar = new ProgressBar(this);
        TextView label = text(busyText.isEmpty() ? "处理中" : busyText, 16, COLOR_MUTED);
        label.setGravity(Gravity.CENTER);
        view.addView(progressBar);
        view.addView(label);
        content.addView(view);
    }

    private void renderToday() {
        LinearLayout page = page();
        content.addView(page);

        ArrayList<Paper> queue = reviewQueue();
        if (cursor < 0) cursor = 0;
        if (cursor > queue.size()) cursor = queue.size();
        Paper paper = cursor < queue.size() ? queue.get(cursor) : null;
        if (paper == null) {
            LinearLayout empty = cardPanel();
            empty.setGravity(Gravity.CENTER);
            TextView title = title("今日队列已清空");
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            empty.addView(text(store.papers.isEmpty() ? "还没有本地论文缓存，点同步开始。" : "收藏里已经有你的筛选结果。", 15, COLOR_MUTED));
            Button sync = primaryButton("重新同步");
            sync.setOnClickListener(view -> syncPapers(true));
            empty.addView(sync);
            Button previous = button("上一条");
            previous.setEnabled(cursor > 0);
            previous.setOnClickListener(view -> recoverPreviousAction());
            empty.addView(previous);
            page.addView(empty);
            return;
        }

        page.addView(paperCard(paper));
        scheduleBackgroundWork(queue, cursor);
    }

    private View paperCard(Paper paper) {
        LinearLayout card = cardPanel();
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("PaperCard", 15, COLOR_INK);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        meta.addView(brand);
        meta.getChildAt(0).setPadding(0, 0, dp(8), 0);
        TextView stats = text("未读 " + store.unreadCount() + " · 收藏 " + store.favoriteCount() + " · 略过 " + store.skippedCount(), 12, COLOR_MUTED);
        stats.setGravity(Gravity.RIGHT);
        meta.addView(stats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sync = button("同步");
        sync.setOnClickListener(view -> syncPapers(true));
        meta.addView(sync);
        card.addView(meta);

        LinearLayout categoryLine = new LinearLayout(this);
        categoryLine.setGravity(Gravity.CENTER_VERTICAL);
        categoryLine.setPadding(0, dp(14), 0, 0);
        if (!paper.favoriteAt.isEmpty() || !paper.skippedAt.isEmpty()) {
            TextView seen = tag("看过");
            seen.setTextColor(COLOR_BRICK);
            seen.setBackground(cardBackground(Color.rgb(248, 233, 227), Color.TRANSPARENT, dp(8)));
            LinearLayout.LayoutParams seenParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seenParams.setMargins(0, 0, dp(6), 0);
            categoryLine.addView(seen, seenParams);
            seen.setPadding(dp(8), dp(5), dp(8), dp(5));
        }
        categoryLine.addView(tag(paper.category));
        TextView date = text("  " + shortDate(paper.published), 13, COLOR_MUTED);
        categoryLine.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView score = tag(String.valueOf(paper.relevance));
        score.setTextColor(Color.WHITE);
        score.setBackground(cardBackground(COLOR_INK, Color.TRANSPARENT, dp(8)));
        categoryLine.addView(score);
        card.addView(categoryLine);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(paper.title, 25, COLOR_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(14), 0, dp(8));
        body.addView(title);
        if (paper.translation != null && !paper.translation.titleZh.isEmpty()) {
            TextView translated = text(paper.translation.titleZh, 18, COLOR_BLUE);
            translated.setTypeface(null, android.graphics.Typeface.BOLD);
            body.addView(translated);
        } else if (isTranslating(paper)) {
            TextView translating = text("正在后台生成中文卡片，先看英文原文", 13, COLOR_MUTED);
            translating.setPadding(0, 0, 0, dp(6));
            body.addView(translating);
        }
        body.addView(text(authorLine(paper), 14, COLOR_BRICK));
        String summary = paper.translation != null && !paper.translation.summaryZh.isEmpty() ? paper.translation.summaryZh : paper.summary;
        TextView abstractView = text(summary, 16, Color.rgb(45, 56, 60));
        abstractView.setPadding(0, dp(12), 0, dp(8));
        body.addView(abstractView);
        if (paper.translation != null) body.addView(translationBlock(paper.translation));
        body.addView(htmlFigureStrip(paper));
        scrollView.addView(body);
        card.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(0, dp(10), 0, 0);
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setGravity(Gravity.CENTER);
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER);
        Button translate = button(isTranslating(paper) ? "翻译中" : paper.translation == null ? "翻译" : "重译");
        translate.setEnabled(!isTranslating(paper));
        translate.setOnClickListener(view -> translatePaper(paper, true, false));
        Button pdf = button("PDF");
        pdf.setOnClickListener(view -> openPdf(paper));
        Button original = button("原文");
        original.setOnClickListener(view -> showOriginalPaper(paper));
        Button previous = button("上一条");
        previous.setEnabled(cursor > 0);
        previous.setOnClickListener(view -> recoverPreviousAction());
        Button skip = button("略过");
        skip.setOnClickListener(view -> skipPaper(paper));
        Button favorite = primaryButton("收藏");
        favorite.setOnClickListener(view -> favoritePaper(paper));
        linkRow.addView(translate, compactWeight());
        linkRow.addView(pdf, compactWeight());
        linkRow.addView(original, compactWeight());
        actionRow.addView(previous, compactWeight());
        actionRow.addView(skip, compactWeight());
        actionRow.addView(favorite, compactWeight());
        tools.addView(linkRow);
        tools.addView(actionRow);
        card.addView(tools);

        final float[] start = new float[2];
        card.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                start[0] = event.getRawX();
                start[1] = event.getRawY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getRawX() - start[0];
                float dy = event.getRawY() - start[1];
                if (dx > dp(90)) favoritePaper(paper);
                else if (dy < -dp(90)) skipPaper(paper);
                return true;
            }
            return true;
        });
        return card;
    }

    private View translationBlock(Translation translation) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackgroundColor(Color.rgb(240, 243, 237));
        for (String point : translation.keyPoints) block.addView(text("· " + point, 14, Color.rgb(49, 65, 59)));
        return block;
    }

    private void renderFavorites() {
        ScrollView scroll = scroll();
        LinearLayout page = page();
        scroll.addView(page);
        content.addView(scroll);

        page.addView(title("收藏库"));
        page.addView(favoriteControls());
        ArrayList<Paper> favorites = store.favoritePapers(favoriteFilterId);
        if (favorites.isEmpty()) {
            LinearLayout empty = panel();
            empty.setGravity(Gravity.CENTER);
            empty.setMinimumHeight(dp(220));
            empty.addView(text("这里还没有收藏", 16, COLOR_MUTED));
            page.addView(empty);
            return;
        }
        for (Paper paper : favorites) page.addView(favoriteItem(paper));
    }

    private View favoriteControls() {
        LinearLayout box = panel();
        Button copyBib = button("复制 BibTeX");
        copyBib.setOnClickListener(view -> {
            String text = store.bibtex(favoriteFilterId);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("papercard-favorites.bib", text));
            toast("BibTeX 已复制");
        });
        box.addView(copyBib);

        Spinner filter = new Spinner(this);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        labels.add("全部收藏");
        ids.add("all");
        int selection = "all".equals(favoriteFilterId) ? 0 : -1;
        for (int i = 0; i < store.favoriteFolders.size(); i++) {
            FavoriteFolder folder = store.favoriteFolders.get(i);
            labels.add(folder.name);
            ids.add(folder.id);
            if (folder.id.equals(favoriteFilterId)) selection = i + 1;
        }
        if (selection < 0) selection = 0;
        filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        filter.setSelection(selection);
        filter.setOnItemSelectedListener(new SimpleItemSelected(position -> {
            favoriteFilterId = ids.get(position);
            render();
        }));
        box.addView(filter);

        LinearLayout createRow = new LinearLayout(this);
        createRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText name = input("新建文件夹");
        Button create = button("创建");
        create.setOnClickListener(view -> {
            try {
                FavoriteFolder folder = store.createFolder(name.getText().toString());
                favoriteFilterId = folder.id;
                activeFolderId = folder.id;
                render();
            } catch (Exception exception) {
                toast(exception.getMessage());
            }
        });
        createRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        createRow.addView(create);
        box.addView(createRow);

        if (!"all".equals(favoriteFilterId) && !PaperModels.DEFAULT_FOLDER_ID.equals(favoriteFilterId)) {
            Button delete = dangerButton("删除当前文件夹");
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("删除文件夹")
                    .setMessage("文件夹会删除，里面的论文会移回默认收藏。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        store.deleteFolder(favoriteFilterId);
                        favoriteFilterId = "all";
                        activeFolderId = PaperModels.DEFAULT_FOLDER_ID;
                        render();
                    })
                    .show());
            box.addView(delete);
        }
        return box;
    }

    private View favoriteItem(Paper paper) {
        LinearLayout item = panel();
        item.addView(tag(paper.category + " · " + store.folderName(paper.favoriteFolderId)));
        TextView title = text(paper.translation != null && !paper.translation.titleZh.isEmpty() ? paper.translation.titleZh : paper.title, 17, COLOR_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        item.addView(title);
        item.addView(text(authorLine(paper), 13, COLOR_MUTED));
        LinearLayout tools = new LinearLayout(this);
        Button ai = button("AI精读");
        ai.setOnClickListener(view -> startDeepReadWithPapers(singlePaperList(paper)));
        Button translate = button("翻译");
        translate.setOnClickListener(view -> translatePaper(paper, true, false));
        Button pdf = button("PDF");
        pdf.setOnClickListener(view -> openPdf(paper));
        Button remove = dangerButton("移出");
        remove.setOnClickListener(view -> {
            store.clearAction(paper);
                render();
        });
        tools.addView(ai);
        tools.addView(translate);
        tools.addView(pdf);
        tools.addView(remove);
        item.addView(tools);
        item.addView(htmlFigureStrip(paper));
        return item;
    }

    private void renderDeepRead() {
        ScrollView scroll = scroll();
        LinearLayout page = page();
        scroll.addView(page);
        content.addView(scroll);

        page.addView(title("AI 精读"));
        page.addView(deepReadPicker());
        page.addView(deepReadThreadList());
        page.addView(deepReadChatPanel());
    }

    private View deepReadPicker() {
        LinearLayout box = panel();
        box.addView(label("收藏论文"));
        ArrayList<Paper> favorites = store.favoritePapers("all");
        if (favorites.isEmpty()) {
            box.addView(text("这里还没有收藏", 14, COLOR_MUTED));
        } else {
            for (Paper paper : favorites) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(shortTitle(paper.translation != null && !paper.translation.titleZh.isEmpty() ? paper.translation.titleZh : paper.title, 54));
                checkBox.setTextColor(COLOR_INK);
                checkBox.setTextSize(14);
                checkBox.setChecked(deepReadSelectedIds.contains(paper.id));
                checkBox.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) deepReadSelectedIds.add(paper.id);
                    else deepReadSelectedIds.remove(paper.id);
                    render();
                });
                box.addView(checkBox);
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button create = primaryButton("新建精读");
        create.setEnabled(!deepReadSelectedIds.isEmpty());
        create.setOnClickListener(view -> startDeepReadWithPapers(selectedFavoritePapers()));
        Button attach = button("纳入当前");
        attach.setEnabled(!deepReadSelectedIds.isEmpty());
        attach.setOnClickListener(view -> addSelectedPapersToActiveThread());
        actions.addView(create, compactWeight());
        actions.addView(attach, compactWeight());
        box.addView(actions);
        return box;
    }

    private View deepReadThreadList() {
        LinearLayout box = panel();
        box.addView(label("聊天窗口"));
        if (deepReadThreads.isEmpty()) {
            box.addView(text("选择收藏论文即可开始", 14, COLOR_MUTED));
            return box;
        }
        for (DeepReadThread thread : deepReadThreads) {
            Button button = button(thread.paperIds.size() + " 篇 · " + thread.title);
            button.setTextColor(thread.id.equals(activeDeepReadThreadId) ? Color.WHITE : COLOR_INK);
            button.setBackground(cardBackground(thread.id.equals(activeDeepReadThreadId) ? COLOR_INK : COLOR_PANEL_SOFT, COLOR_LINE, dp(8)));
            button.setOnClickListener(view -> {
                activeDeepReadThreadId = thread.id;
                render();
            });
            box.addView(button);
        }
        return box;
    }

    private View deepReadChatPanel() {
        LinearLayout box = panel();
        DeepReadThread thread = activeDeepReadThread();
        if (thread == null) {
            box.setMinimumHeight(dp(220));
            box.setGravity(Gravity.CENTER);
            box.addView(text("精读窗口", 18, COLOR_INK));
            box.addView(text("从收藏页点 AI精读，或在这里多选收藏论文。", 14, COLOR_MUTED));
            return box;
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label(thread.title);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(tag(thread.paperIds.size() + " 篇 HTML"));
        box.addView(header);

        for (DeepReadMessage message : thread.messages) box.addView(chatMessageView(message));
        if (busyDeepReadThreadIds.contains(thread.id)) box.addView(pendingMessageView());

        EditText input = input("继续追问论文细节");
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setText(deepReadDraft);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                deepReadDraft = s == null ? "" : s.toString();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        Button send = primaryButton(busyDeepReadThreadIds.contains(thread.id) ? "精读中" : "发送");
        send.setEnabled(!busyDeepReadThreadIds.contains(thread.id));
        send.setOnClickListener(view -> submitDeepReadQuestion());

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(10), 0, 0);
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        composer.addView(send);
        box.addView(composer);
        return box;
    }

    private View chatMessageView(DeepReadMessage message) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity("user".equals(message.role) ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        int color = "user".equals(message.role) ? Color.rgb(229, 237, 223) : Color.rgb(248, 250, 244);
        bubble.setBackground(cardBackground(color, COLOR_LINE, dp(8)));

        for (String paperId : message.paperIds) {
            Paper paper = store.getPaper(paperId);
            if (paper != null) bubble.addView(paperAttachmentBlock(paper));
        }
        if (!message.content.isEmpty()) bubble.addView(text(message.content, 15, Color.rgb(45, 56, 60)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins("user".equals(message.role) ? dp(42) : 0, 0, "user".equals(message.role) ? 0 : dp(42), 0);
        row.addView(bubble, params);
        return row;
    }

    private View pendingMessageView() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.LEFT);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView bubble = text("正在精读", 15, COLOR_MUTED);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackground(cardBackground(Color.rgb(248, 250, 244), COLOR_LINE, dp(8)));
        row.addView(bubble);
        return row;
    }

    private View paperAttachmentBlock(Paper paper) {
        PaperHtml html = htmlByPaperId.get(paper.id);
        if (html == null && !loadingHtmlIds.contains(paper.id)) scheduleHtmlLoad(paper, false, false);

        Button button = button("HTML · " + shortTitle(paper.translation != null && !paper.translation.titleZh.isEmpty() ? paper.translation.titleZh : paper.title, 28)
                + (html == null ? " · 读取中" : " · " + Math.max(1, html.htmlLength / 1024) + " KB · " + html.images.size() + " 图"));
        button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(view -> showHtmlAttachment(paper));
        return button;
    }

    private void showHtmlAttachment(Paper paper) {
        PaperHtml html = htmlByPaperId.get(paper.id);
        if (html == null) {
            scheduleHtmlLoad(paper, false, true);
            toast("正在读取 HTML");
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(4));
        body.addView(text(html.sourceUrl, 12, COLOR_BLUE));
        TextView preview = text(html.compactHtml(PaperHtmlClient.MAX_UI_HTML_CHARS)
                + (html.htmlLength > PaperHtmlClient.MAX_UI_HTML_CHARS ? "\n..." : ""), 11, Color.rgb(45, 56, 60));
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setPadding(0, dp(10), 0, 0);
        body.addView(preview);
        scrollView.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("HTML 附件")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开来源", (dialog, which) -> openUrl(html.sourceUrl))
                .show();
    }

    private void renderSettings() {
        ScrollView scroll = scroll();
        LinearLayout page = page();
        scroll.addView(page);
        content.addView(scroll);

        page.addView(title("订阅偏好"));
        page.addView(keywordSettings());
        page.addView(categorySettings());
        page.addView(maxResultsSettings());
        page.addView(pdfReadingSettings());
        page.addView(translationSettings());
        page.addView(resetSettings());
    }

    private View keywordSettings() {
        LinearLayout box = panel();
        box.addView(label("关键词"));
        LinearLayout addRow = new LinearLayout(this);
        EditText keyword = input("例如 multimodal agent");
        Button add = button("添加");
        add.setOnClickListener(view -> {
            String value = PaperModels.normalize(keyword.getText().toString());
            if (!value.isEmpty() && !store.preferences.keywords.contains(value) && store.preferences.keywords.size() < 12) {
                store.preferences.keywords.add(value);
                store.save();
                render();
            }
        });
        addRow.addView(keyword, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(add);
        box.addView(addRow);
        for (String value : new ArrayList<>(store.preferences.keywords)) {
            Button chip = button(value + "  ×");
            chip.setOnClickListener(view -> {
                store.preferences.keywords.remove(value);
                store.save();
                render();
            });
            box.addView(chip);
        }
        return box;
    }

    private View categorySettings() {
        LinearLayout box = panel();
        box.addView(label("分类"));
        String[] categories = {"cs.AI", "cs.CL", "cs.LG", "cs.CV", "cs.RO", "stat.ML", "math.OC", "q-bio.NC"};
        for (String category : categories) {
            CheckBox checkbox = new CheckBox(this);
            checkbox.setText(category);
            checkbox.setTextSize(15);
            checkbox.setChecked(store.preferences.categories.contains(category));
            checkbox.setOnCheckedChangeListener((button, checked) -> {
                if (checked && !store.preferences.categories.contains(category) && store.preferences.categories.size() < 12) {
                    store.preferences.categories.add(category);
                } else if (!checked) {
                    store.preferences.categories.remove(category);
                }
                if (store.preferences.categories.isEmpty()) store.preferences.categories.add("cs.AI");
                store.save();
            });
            box.addView(checkbox);
        }
        return box;
    }

    private View maxResultsSettings() {
        LinearLayout box = panel();
        TextView label = label("每次同步 " + store.preferences.maxResults + " 篇");
        box.addView(label);
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(17);
        seekBar.setProgress((store.preferences.maxResults - 12) / 4);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                store.preferences.maxResults = 12 + progress * 4;
                label.setText("每次同步 " + store.preferences.maxResults + " 篇");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                store.save();
            }
        });
        box.addView(seekBar);
        return box;
    }

    private View pdfReadingSettings() {
        LinearLayout box = panel();
        box.addView(label("PDF 阅读方式"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paged = button("翻页阅读");
        Button continuous = button("连续阅读");
        boolean isContinuous = "continuous".equals(store.preferences.pdfReadingMode);
        paged.setTextColor(isContinuous ? COLOR_INK : Color.WHITE);
        continuous.setTextColor(isContinuous ? Color.WHITE : COLOR_INK);
        paged.setBackground(cardBackground(isContinuous ? COLOR_PANEL_SOFT : COLOR_INK, isContinuous ? COLOR_LINE : Color.TRANSPARENT, dp(8)));
        continuous.setBackground(cardBackground(isContinuous ? COLOR_INK : COLOR_PANEL_SOFT, isContinuous ? Color.TRANSPARENT : COLOR_LINE, dp(8)));
        paged.setOnClickListener(view -> {
            store.preferences.pdfReadingMode = "paged";
            store.save();
            render();
        });
        continuous.setOnClickListener(view -> {
            store.preferences.pdfReadingMode = "continuous";
            store.save();
            render();
        });
        row.addView(paged, compactWeight());
        row.addView(continuous, compactWeight());
        box.addView(row);
        box.addView(text("缩放使用双指手势，阅读器内不再显示加号和减号。", 13, COLOR_MUTED));
        return box;
    }

    private View translationSettings() {
        LinearLayout box = panel();
        box.addView(label("Agnes 翻译"));
        EditText key = input("测试 key");
        key.setSingleLine(false);
        key.setMinLines(1);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(store.preferences.agnesApiKey);
        EditText url = input("API 地址");
        url.setText(store.preferences.agnesApiUrl);
        EditText model = input("模型");
        model.setText(store.preferences.agnesModel);
        Button save = primaryButton("保存翻译设置");
        save.setOnClickListener(view -> {
            store.preferences.agnesApiKey = key.getText().toString().trim();
            store.preferences.agnesApiUrl = url.getText().toString().trim();
            store.preferences.agnesModel = model.getText().toString().trim();
            store.save();
            toast("翻译设置已保存");
        });
        box.addView(key);
        box.addView(url);
        box.addView(model);
        box.addView(save);
        return box;
    }

    private View resetSettings() {
        LinearLayout box = panel();
        box.addView(label("本机数据"));
        TextView copy = text("清空会删除偏好、缓存论文、收藏、略过记录、翻译缓存和应用私有 PDF 缓存。", 14, COLOR_MUTED);
        box.addView(copy);
        Button reset = dangerButton("清空本机数据");
        reset.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("清空本机数据")
                .setMessage("此操作不会访问应用目录之外的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    store.clear();
                    deleteRecursively(new File(getCacheDir(), "pdfs"));
                    deleteRecursively(new File(getCacheDir(), "htmls"));
                    htmlByPaperId.clear();
                    imageCache.clear();
                    queuedHtmlIds.clear();
                    loadingHtmlIds.clear();
                    deepReadThreads.clear();
                    deepReadSelectedIds.clear();
                    busyDeepReadThreadIds.clear();
                    activeDeepReadThreadId = "";
                    deepReadDraft = "";
                    File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (downloads != null) deleteRecursively(downloads);
                    activeTab = TAB_TODAY;
                    cursor = 0;
                    reviewQueueIds.clear();
                    render();
                })
                .show());
        box.addView(reset);
        return box;
    }

    private void syncPapers(boolean force) {
        if (busy) return;
        if (!force && !store.isStale()) return;
        busy = true;
        busyText = "正在直接同步 arXiv";
        render();
        syncExecutor.execute(() -> {
            try {
                ArxivClient.Result result = new ArxivClient().fetch(store.preferences);
                store.replacePapers(result.papers, result.query);
                cursor = 0;
                rebuildReviewQueue();
                runOnUiThread(() -> {
                    busy = false;
                    toast("同步完成：" + result.papers.size() + " 篇");
                    render();
                });
            } catch (Exception exception) {
                store.lastError = exception.getMessage();
                store.save();
                runOnUiThread(() -> {
                    busy = false;
                    toast("同步失败：" + exception.getMessage());
                    render();
                });
            }
        });
    }

    private void translatePaper(Paper paper, boolean force, boolean silent) {
        Paper latest = store.getPaper(paper.id);
        if (!force && latest != null && latest.translation != null) return;
        if (PaperModels.normalize(store.preferences.agnesApiKey).isEmpty()) {
            if (!silent) toast("请先在设置中填写 Agnes 测试 key");
            return;
        }
        Paper target = latest == null ? paper : latest;
        if (!markTranslationQueued(target, force)) return;
        if (!silent) {
            toast("已开始后台翻译");
            render();
        }
        translationExecutor.execute(() -> {
            try {
                Translation translation = new AgnesClient().translate(target, store.preferences);
                synchronized (store) {
                    store.saveTranslation(target, translation);
                }
                runOnUiThread(() -> {
                    clearTranslationQueued(target);
                    if (!silent) {
                        toast("翻译已更新");
                        render();
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    clearTranslationQueued(target);
                    if (!silent) toast(exception.getMessage());
                    if (!silent) render();
                });
            }
        });
    }

    private void scheduleBackgroundWork(ArrayList<Paper> queue, int startIndex) {
        schedulePdfPrefetch(queue, startIndex);
        scheduleHtmlPrefetch(queue, startIndex);
        if (!PaperModels.normalize(store.preferences.agnesApiKey).isEmpty()) {
            content.postDelayed(() -> {
                for (int i = startIndex; i < queue.size(); i++) {
                    Paper paper = queue.get(i);
                    if (!paper.favoriteAt.isEmpty() || !paper.skippedAt.isEmpty()) continue;
                    if (paper.translation == null && !autoTranslationTried.contains(paper.id)) {
                        autoTranslationTried.add(paper.id);
                        translatePaper(paper, false, true);
                    }
                }
            }, 220);
        }
    }

    private boolean markTranslationQueued(Paper paper, boolean force) {
        if (queuedTranslationIds.contains(paper.id)) return false;
        queuedTranslationIds.add(paper.id);
        translatingIds.add(paper.id);
        return true;
    }

    private void clearTranslationQueued(Paper paper) {
        queuedTranslationIds.remove(paper.id);
        translatingIds.remove(paper.id);
    }

    private boolean isTranslating(Paper paper) {
        return translatingIds.contains(paper.id);
    }

    private void schedulePdfPrefetch(ArrayList<Paper> queue, int startIndex) {
        for (int i = startIndex; i < queue.size(); i++) {
            Paper paper = queue.get(i);
            if (!paper.favoriteAt.isEmpty() || !paper.skippedAt.isEmpty()) continue;
            if (queuedPdfIds.contains(paper.id)) continue;
            queuedPdfIds.add(paper.id);
            pdfPrefetchExecutor.execute(() -> {
                try {
                    PdfCache.downloadIfNeeded(this, paper.readerId, paper.pdfUrl);
                } catch (Exception ignored) {
                    // Prefetch is opportunistic; the reader will surface priority download errors.
                }
            });
        }
    }

    private void scheduleHtmlPrefetch(ArrayList<Paper> queue, int startIndex) {
        int end = Math.min(queue.size(), startIndex + 5);
        for (int i = startIndex; i < end; i++) {
            Paper paper = queue.get(i);
            if (queuedHtmlIds.contains(paper.id)) continue;
            queuedHtmlIds.add(paper.id);
            scheduleHtmlLoad(paper, false, false);
        }
    }

    private void scheduleHtmlLoad(Paper paper, boolean force, boolean notify) {
        if (paper == null || paper.id.isEmpty()) return;
        if (!force && htmlByPaperId.containsKey(paper.id)) return;
        if (loadingHtmlIds.contains(paper.id)) return;
        loadingHtmlIds.add(paper.id);
        htmlExecutor.execute(() -> {
            try {
                Paper target = store.getPaper(paper.id);
                PaperHtml html = new PaperHtmlClient().load(this, target == null ? paper : target, force);
                runOnUiThread(() -> {
                    htmlByPaperId.put(paper.id, html);
                    loadingHtmlIds.remove(paper.id);
                    render();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    loadingHtmlIds.remove(paper.id);
                    if (notify) toast(exception.getMessage());
                    render();
                });
            }
        });
    }

    private View htmlFigureStrip(Paper paper) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        PaperHtml html = htmlByPaperId.get(paper.id);
        if (html == null) {
            scheduleHtmlLoad(paper, false, false);
            box.addView(text(loadingHtmlIds.contains(paper.id) ? "解析 HTML 图片" : "等待 HTML 图片", 12, COLOR_MUTED));
            return box;
        }

        TextView heading = text(html.images.isEmpty() ? "HTML 中未找到图片" : html.images.size() + " 张图片", 12, COLOR_BLUE);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(heading);
        if (html.images.isEmpty()) return box;

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        for (PaperImage image : html.images) {
            LinearLayout thumb = imageThumb(image);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(126), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(8), 0);
            row.addView(thumb, params);
        }
        scrollView.addView(row);
        box.addView(scrollView);
        return box;
    }

    private LinearLayout imageThumb(PaperImage image) {
        LinearLayout thumb = new LinearLayout(this);
        thumb.setOrientation(LinearLayout.VERTICAL);
        thumb.setPadding(dp(5), dp(5), dp(5), dp(5));
        thumb.setBackground(cardBackground(COLOR_PANEL_SOFT, COLOR_LINE, dp(8)));
        thumb.setOnClickListener(view -> openUrl(image.url));

        ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.WHITE);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageView.setTag(image.url);
        thumb.addView(imageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));
        loadImageInto(imageView, image.url);

        String caption = image.caption.isEmpty() ? image.alt : image.caption;
        TextView captionView = text(caption.isEmpty() ? "论文图片" : caption, 10, COLOR_MUTED);
        captionView.setMaxLines(2);
        captionView.setPadding(0, dp(5), 0, 0);
        thumb.addView(captionView);
        return thumb;
    }

    private void loadImageInto(ImageView imageView, String rawUrl) {
        Bitmap cached = imageCache.get(rawUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        if (loadingImageUrls.contains(rawUrl)) return;
        loadingImageUrls.add(rawUrl);
        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                URL url = new URL(rawUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol()) || !PaperHtmlClient.isAllowedArxivHost(url.getHost())) {
                    throw new IllegalArgumentException("invalid image url");
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(22000);
                connection.setRequestProperty("User-Agent", "PaperCard/1.0 native-android");
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
                    bitmap = BitmapFactory.decodeStream(input);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
            }
            Bitmap loaded = bitmap;
            runOnUiThread(() -> {
                if (loaded != null) {
                    imageCache.put(rawUrl, loaded);
                    if (rawUrl.equals(imageView.getTag())) imageView.setImageBitmap(loaded);
                }
                loadingImageUrls.remove(rawUrl);
            });
        });
    }

    private ArrayList<Paper> reviewQueue() {
        if (reviewQueueIds.isEmpty() && !store.papers.isEmpty()) rebuildReviewQueue();
        ArrayList<Paper> values = new ArrayList<>();
        for (String paperId : reviewQueueIds) {
            Paper paper = store.getPaper(paperId);
            if (paper != null) values.add(paper);
        }
        if (values.size() != reviewQueueIds.size()) {
            rebuildReviewQueue();
            values.clear();
            for (String paperId : reviewQueueIds) {
                Paper paper = store.getPaper(paperId);
                if (paper != null) values.add(paper);
            }
        }
        return values;
    }

    private void rebuildReviewQueue() {
        reviewQueueIds.clear();
        for (Paper paper : store.queue()) reviewQueueIds.add(paper.id);
        if (cursor > reviewQueueIds.size()) cursor = reviewQueueIds.size();
    }

    private void favoritePaper(Paper paper) {
        lastAction = paper;
        store.favorite(paper, activeFolderId);
        cursor = Math.min(cursor + 1, reviewQueueIds.size());
        toast("已收藏到 " + store.folderName(activeFolderId));
        render();
    }

    private void skipPaper(Paper paper) {
        lastAction = paper;
        store.skip(paper);
        cursor = Math.min(cursor + 1, reviewQueueIds.size());
        toast("已略过");
        render();
    }

    private void recoverPreviousAction() {
        if (cursor <= 0) return;
        cursor--;
        render();
    }

    private ArrayList<Paper> singlePaperList(Paper paper) {
        ArrayList<Paper> values = new ArrayList<>();
        if (paper != null) values.add(paper);
        return values;
    }

    private ArrayList<Paper> selectedFavoritePapers() {
        ArrayList<Paper> values = new ArrayList<>();
        for (Paper paper : store.favoritePapers("all")) {
            if (deepReadSelectedIds.contains(paper.id)) values.add(paper);
        }
        return uniquePapers(values);
    }

    private void startDeepReadWithPapers(ArrayList<Paper> papers) {
        ArrayList<Paper> selected = uniquePapers(papers);
        if (selected.isEmpty()) {
            toast("请选择收藏论文");
            return;
        }

        for (Paper paper : selected) scheduleHtmlLoad(paper, false, false);
        String now = PaperStore.nowIso();
        DeepReadThread thread = new DeepReadThread();
        thread.id = makeId("thread");
        thread.title = shortTitle(selected.get(0).translation != null && !selected.get(0).translation.titleZh.isEmpty()
                ? selected.get(0).translation.titleZh
                : selected.get(0).title, 28);
        thread.createdAt = now;
        thread.updatedAt = now;
        for (Paper paper : selected) thread.paperIds.add(paper.id);

        DeepReadMessage message = new DeepReadMessage();
        message.id = makeId("message");
        message.role = "user";
        message.createdAt = now;
        message.content = selected.size() > 1
                ? "请先对这些论文做对照精读，概括共同问题、方法差异和进一步追问点。"
                : "请先精读这篇论文，概括核心问题、方法、结论和可以继续追问的点。";
        message.paperIds.addAll(thread.paperIds);
        thread.messages.add(message);

        deepReadThreads.add(0, thread);
        activeDeepReadThreadId = thread.id;
        deepReadSelectedIds.clear();
        deepReadDraft = "";
        activeTab = TAB_DEEP_READ;
        render();
        askDeepReadAssistant(thread.id);
    }

    private void addSelectedPapersToActiveThread() {
        ArrayList<Paper> selected = selectedFavoritePapers();
        if (selected.isEmpty()) {
            toast("请选择收藏论文");
            return;
        }
        DeepReadThread thread = activeDeepReadThread();
        if (thread == null) {
            startDeepReadWithPapers(selected);
            return;
        }

        ArrayList<String> addedIds = new ArrayList<>();
        for (Paper paper : selected) {
            scheduleHtmlLoad(paper, false, false);
            if (!thread.paperIds.contains(paper.id) && thread.paperIds.size() < 6) thread.paperIds.add(paper.id);
            if (!addedIds.contains(paper.id)) addedIds.add(paper.id);
        }

        String now = PaperStore.nowIso();
        DeepReadMessage message = new DeepReadMessage();
        message.id = makeId("message");
        message.role = "user";
        message.createdAt = now;
        message.content = addedIds.size() > 1
                ? "请把这些论文纳入当前讨论，并给出它们与已有论文之间的关联。"
                : "请把这篇论文纳入当前讨论，并说明它和已有论文的联系。";
        message.paperIds.addAll(addedIds);
        thread.messages.add(message);
        thread.updatedAt = now;
        deepReadSelectedIds.clear();
        render();
        askDeepReadAssistant(thread.id);
    }

    private void submitDeepReadQuestion() {
        DeepReadThread thread = activeDeepReadThread();
        String content = PaperModels.normalize(deepReadDraft);
        if (thread == null || content.isEmpty() || busyDeepReadThreadIds.contains(thread.id)) return;

        String now = PaperStore.nowIso();
        DeepReadMessage message = new DeepReadMessage();
        message.id = makeId("message");
        message.role = "user";
        message.content = content;
        message.createdAt = now;
        thread.messages.add(message);
        thread.updatedAt = now;
        deepReadDraft = "";
        render();
        askDeepReadAssistant(thread.id);
    }

    private void askDeepReadAssistant(String threadId) {
        DeepReadThread thread = findDeepReadThread(threadId);
        if (thread == null || busyDeepReadThreadIds.contains(thread.id)) return;
        if (PaperModels.normalize(store.preferences.agnesApiKey).isEmpty()) {
            toast("请先在设置中填写 Agnes 测试 key");
            return;
        }

        busyDeepReadThreadIds.add(thread.id);
        ArrayList<Paper> papers = papersForIds(thread.paperIds);
        ArrayList<DeepReadMessage> messages = new ArrayList<>(thread.messages);
        render();
        deepReadExecutor.execute(() -> {
            try {
                String content = new DeepReadClient().chat(this, papers, messages, store.preferences);
                runOnUiThread(() -> {
                    DeepReadThread latest = findDeepReadThread(threadId);
                    if (latest != null) {
                        DeepReadMessage answer = new DeepReadMessage();
                        answer.id = makeId("message");
                        answer.role = "assistant";
                        answer.content = content;
                        answer.createdAt = PaperStore.nowIso();
                        latest.messages.add(answer);
                        latest.updatedAt = answer.createdAt;
                    }
                    busyDeepReadThreadIds.remove(threadId);
                    render();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    busyDeepReadThreadIds.remove(threadId);
                    toast(exception.getMessage());
                    render();
                });
            }
        });
    }

    private DeepReadThread activeDeepReadThread() {
        DeepReadThread active = findDeepReadThread(activeDeepReadThreadId);
        if (active != null) return active;
        return deepReadThreads.isEmpty() ? null : deepReadThreads.get(0);
    }

    private DeepReadThread findDeepReadThread(String threadId) {
        for (DeepReadThread thread : deepReadThreads) {
            if (thread.id.equals(threadId)) return thread;
        }
        return null;
    }

    private ArrayList<Paper> papersForIds(ArrayList<String> paperIds) {
        ArrayList<Paper> values = new ArrayList<>();
        for (String paperId : paperIds) {
            Paper paper = store.getPaper(paperId);
            if (paper != null) values.add(paper);
        }
        return values;
    }

    private ArrayList<Paper> uniquePapers(ArrayList<Paper> papers) {
        ArrayList<Paper> values = new ArrayList<>();
        for (Paper paper : papers) {
            if (paper == null || paper.id.isEmpty()) continue;
            boolean exists = false;
            for (Paper value : values) {
                if (value.id.equals(paper.id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) values.add(paper);
            if (values.size() >= 6) break;
        }
        return values;
    }

    private void openPdf(Paper paper) {
        Intent intent = new Intent(this, PdfActivity.class);
        intent.putExtra("title", paper.translation != null && !paper.translation.titleZh.isEmpty() ? paper.translation.titleZh : paper.title);
        intent.putExtra("readerId", paper.readerId);
        intent.putExtra("pdfUrl", paper.pdfUrl);
        intent.putExtra("readingMode", store.preferences.pdfReadingMode);
        startActivity(intent);
    }

    private void showOriginalPaper(Paper paper) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(4));

        TextView title = text(paper.title, 18, COLOR_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title);
        TextView meta = text(paper.category + " · " + authorLine(paper), 13, COLOR_MUTED);
        meta.setPadding(0, dp(10), 0, dp(12));
        body.addView(meta);
        body.addView(text(paper.summary, 15, Color.rgb(45, 56, 60)));
        scrollView.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("英文原文")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exception) {
            toast("无法打开链接");
        }
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(12), dp(12), dp(12));
        return page;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(cardBackground(COLOR_PANEL, COLOR_LINE, dp(8)));
        panel.setElevation(dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        panel.setLayoutParams(params);
        return panel;
    }

    private LinearLayout cardPanel() {
        LinearLayout card = panel();
        card.setElevation(dp(12));
        card.setMinimumHeight(dp(0));
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return card;
    }

    private LinearLayout center() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(24), dp(24), dp(24), dp(24));
        return view;
    }

    private ScrollView scroll() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        return scrollView;
    }

    private TextView title(String value) {
        TextView text = text(value, 23, COLOR_INK);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(4), 0, dp(8));
        return text;
    }

    private TextView label(String value) {
        TextView text = text(value, 15, COLOR_INK);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(4), 0, dp(8));
        return text;
    }

    private TextView tag(String value) {
        TextView text = text(value, 12, COLOR_BLUE);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(dp(8), dp(5), dp(8), dp(5));
        text.setBackground(cardBackground(Color.rgb(231, 238, 240), Color.TRANSPARENT, dp(8)));
        return text;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setPadding(dp(10), 0, dp(10), 0);
        return input;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(COLOR_INK);
        button.setBackground(cardBackground(COLOR_PANEL_SOFT, COLOR_LINE, dp(8)));
        button.setElevation(dp(1));
        return button;
    }

    private Button primaryButton(String label) {
        Button button = button(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(cardBackground(COLOR_INK, Color.TRANSPARENT, dp(8)));
        return button;
    }

    private Button dangerButton(String label) {
        Button button = button(label);
        button.setTextColor(COLOR_BRICK);
        button.setBackground(cardBackground(Color.rgb(248, 233, 227), Color.TRANSPARENT, dp(8)));
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams compactWeight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private GradientDrawable cardBackground(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private String authorLine(Paper paper) {
        if (paper.authors.isEmpty()) return "arXiv";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(3, paper.authors.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(", ");
            builder.append(paper.authors.get(i));
        }
        if (paper.authors.size() > 3) builder.append(" 等");
        return builder.toString();
    }

    private String shortTitle(String value, int limit) {
        String clean = PaperModels.normalize(value);
        return clean.length() > limit ? clean.substring(0, limit) + "..." : clean;
    }

    private String makeId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String shortDate(String value) {
        if (value == null || value.length() < 10) return "";
        return value.substring(5, 10);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message == null ? "" : message, Toast.LENGTH_SHORT).show();
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private interface SelectionCallback {
        void selected(int position);
    }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        private final SelectionCallback callback;
        private boolean first = true;

        SimpleItemSelected(SelectionCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (first) {
                first = false;
                return;
            }
            callback.selected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
