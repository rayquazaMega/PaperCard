package com.papercard.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TAB_TODAY = 0;
    private static final int TAB_FAVORITES = 1;
    private static final int TAB_SETTINGS = 2;
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
    private final HashSet<String> autoTranslationTried = new HashSet<>();
    private final HashSet<String> translatingIds = new HashSet<>();
    private final HashSet<String> queuedTranslationIds = new HashSet<>();
    private final HashSet<String> queuedPdfIds = new HashSet<>();
    private LinearLayout root;
    private FrameLayout content;
    private final ArrayList<String> reviewQueueIds = new ArrayList<>();
    private int activeTab = TAB_TODAY;
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
        } else {
            renderSettings();
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
        original.setOnClickListener(view -> openUrl(paper.absUrl));
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
        Button translate = button("翻译");
        translate.setOnClickListener(view -> translatePaper(paper, true, false));
        Button pdf = button("PDF");
        pdf.setOnClickListener(view -> openPdf(paper));
        Button remove = dangerButton("移出");
        remove.setOnClickListener(view -> {
            store.clearAction(paper);
            render();
        });
        tools.addView(translate);
        tools.addView(pdf);
        tools.addView(remove);
        item.addView(tools);
        return item;
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
                    if (!silent) toast("翻译已更新");
                    render();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    clearTranslationQueued(target);
                    if (!silent) toast(exception.getMessage());
                    render();
                });
            }
        });
    }

    private void scheduleBackgroundWork(ArrayList<Paper> queue, int startIndex) {
        schedulePdfPrefetch(queue, startIndex);
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

    private void openPdf(Paper paper) {
        Intent intent = new Intent(this, PdfActivity.class);
        intent.putExtra("title", paper.translation != null && !paper.translation.titleZh.isEmpty() ? paper.translation.titleZh : paper.title);
        intent.putExtra("readerId", paper.readerId);
        intent.putExtra("pdfUrl", paper.pdfUrl);
        intent.putExtra("readingMode", store.preferences.pdfReadingMode);
        startActivity(intent);
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
