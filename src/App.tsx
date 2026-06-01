import {
  ArrowDownToLine,
  ArrowRight,
  ArrowUp,
  BookOpen,
  Bookmark,
  BookmarkCheck,
  Check,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Folder,
  FolderPlus,
  Languages,
  Loader2,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Trash2,
  X
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.mjs?url";
import {
  createFavoriteFolder,
  deleteFavoriteFolder,
  fetchFavorites,
  fetchPapers,
  getApiBase,
  makeApiUrl,
  savePreferences,
  sendAction,
  setApiBase,
  translatePaper
} from "./api";
import type { FavoriteFolder, Paper, Preferences, Translation } from "./types";

GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

const defaultPreferences: Preferences = {
  keywords: ["large language model", "retrieval augmented generation", "agent"],
  categories: ["cs.AI", "cs.CL", "cs.LG"],
  maxResults: 36,
  language: "zh-CN",
  pdfReadingMode: "paged"
};

const defaultFolder: FavoriteFolder = {
  id: "default",
  name: "默认收藏",
  createdAt: "2026-01-01T00:00:00.000Z"
};

const categories = ["cs.AI", "cs.CL", "cs.LG", "cs.CV", "cs.RO", "stat.ML", "math.OC", "q-bio.NC"];

type Tab = "today" | "favorites" | "settings";
type Toast = { tone: "ok" | "warn"; text: string } | null;
type PaperAction = "favorite" | "skip" | "clear";

function hasSeenPaper(paper: Paper) {
  return Boolean(paper.favoriteAt || paper.skippedAt);
}

function buildReviewQueueIds(papers: Paper[]) {
  return [...papers]
    .sort((a, b) => Number(hasSeenPaper(a)) - Number(hasSeenPaper(b)))
    .map((paper) => paper.id);
}

function formatDate(value: string) {
  if (!value) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" }).format(new Date(value));
}

function formatFullDate(value: string | null) {
  if (!value) return "尚未同步";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

function authorLine(paper: Paper) {
  if (!paper.authors.length) return "arXiv";
  const first = paper.authors.slice(0, 3).join(", ");
  return paper.authors.length > 3 ? `${first} 等` : first;
}

function folderName(folders: FavoriteFolder[], folderId: string) {
  return folders.find((folder) => folder.id === folderId)?.name || defaultFolder.name;
}

function App() {
  const [papers, setPapers] = useState<Paper[]>([]);
  const [favorites, setFavorites] = useState<Paper[]>([]);
  const [favoriteFolders, setFavoriteFolders] = useState<FavoriteFolder[]>([defaultFolder]);
  const [activeFolderId, setActiveFolderId] = useState(defaultFolder.id);
  const [favoriteFilterId, setFavoriteFilterId] = useState("all");
  const [folderDraft, setFolderDraft] = useState("");
  const [preferences, setPreferences] = useState<Preferences>(defaultPreferences);
  const [tab, setTab] = useState<Tab>("today");
  const [cursor, setCursor] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState("");
  const [lastFetchAt, setLastFetchAt] = useState<string | null>(null);
  const [lastError, setLastError] = useState<string | null>(null);
  const [toast, setToast] = useState<Toast>(null);
  const [pdfPaper, setPdfPaper] = useState<Paper | null>(null);
  const [originalPaper, setOriginalPaper] = useState<Paper | null>(null);
  const [translatingIds, setTranslatingIds] = useState<Set<string>>(() => new Set());
  const [reviewQueueIds, setReviewQueueIds] = useState<string[]>([]);
  const [keywordDraft, setKeywordDraft] = useState("");
  const [apiDraft, setApiDraft] = useState(getApiBase());
  const [autoTranslateBlocked, setAutoTranslateBlocked] = useState(false);
  const autoTranslateTried = useRef<Set<string>>(new Set());
  const pdfPrefetchTried = useRef<Set<string>>(new Set());
  const pdfPrefetchQueue = useRef<Paper[]>([]);
  const pdfPrefetchBusy = useRef(false);

  const queue = useMemo(() => {
    const byId = new Map(papers.map((paper) => [paper.id, paper]));
    const ids = reviewQueueIds.length ? reviewQueueIds : buildReviewQueueIds(papers);
    return ids.map((id) => byId.get(id)).filter((paper): paper is Paper => Boolean(paper));
  }, [papers, reviewQueueIds]);
  const currentPaper = cursor < queue.length ? queue[cursor] : null;

  const stats = useMemo(() => {
    const saved = papers.filter((paper) => paper.favoriteAt).length;
    const skipped = papers.filter((paper) => paper.skippedAt).length;
    const remaining = papers.filter((paper) => !hasSeenPaper(paper)).length;
    return { saved, skipped, remaining };
  }, [papers, queue.length]);

  const applyPaper = useCallback((updated: Paper) => {
    setPapers((items) => items.map((item) => (item.id === updated.id ? updated : item)));
    setFavorites((items) => {
      if (!updated.favoriteAt) return items.filter((item) => item.id !== updated.id);
      const next = [updated, ...items.filter((item) => item.id !== updated.id)];
      return next.sort((a, b) => new Date(b.favoriteAt || 0).getTime() - new Date(a.favoriteAt || 0).getTime());
    });
  }, []);

  const showToast = useCallback((nextToast: Toast) => {
    setToast(nextToast);
    window.setTimeout(() => setToast(null), 2600);
  }, []);

  const load = useCallback(async (force = false) => {
    try {
      force ? setRefreshing(true) : setLoading(true);
      setError("");
      const response = await fetchPapers(force);
      setPapers(response.papers);
      setReviewQueueIds(buildReviewQueueIds(response.papers));
      setPreferences(response.preferences);
      setFavoriteFolders(response.favoriteFolders?.length ? response.favoriteFolders : [defaultFolder]);
      setLastFetchAt(response.lastFetchAt);
      setLastError(response.lastError);
      setCursor(0);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : String(loadError));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  const loadFavorites = useCallback(async () => {
    try {
      const response = await fetchFavorites(favoriteFilterId);
      setFavorites(response.favorites);
      setFavoriteFolders(response.favoriteFolders?.length ? response.favoriteFolders : [defaultFolder]);
    } catch (favoriteError) {
      showToast({ tone: "warn", text: favoriteError instanceof Error ? favoriteError.message : String(favoriteError) });
    }
  }, [favoriteFilterId, showToast]);

  useEffect(() => {
    load(false);
  }, [load]);

  useEffect(() => {
    if (tab === "favorites") loadFavorites();
  }, [tab, loadFavorites]);

  useEffect(() => {
    if (cursor > queue.length) setCursor(queue.length);
  }, [cursor, queue.length]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (tab !== "today" || !currentPaper || pdfPaper || event.repeat) return;
      if (event.key === "ArrowRight") void handleAction(currentPaper, "favorite");
      if (event.key === "ArrowUp") void handleAction(currentPaper, "skip");
      if (event.key.toLowerCase() === "t") void handleTranslate(currentPaper, Boolean(currentPaper.translation));
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  });

  useEffect(() => {
    if (!currentPaper || autoTranslateBlocked) return;
    if (translatingIds.size) return;
    const paper = queue
      .slice(cursor)
      .find((item) => !hasSeenPaper(item) && !item.translation && !autoTranslateTried.current.has(item.id));
    if (!paper) return;
    autoTranslateTried.current.add(paper.id);
    void handleTranslate(paper, false, true);
  });

  useEffect(() => {
    queue.slice(cursor).forEach((paper) => {
      if (hasSeenPaper(paper)) return;
      if (pdfPrefetchTried.current.has(paper.id)) return;
      pdfPrefetchTried.current.add(paper.id);
      pdfPrefetchQueue.current.push(paper);
    });
    pumpPdfPrefetch();
  }, [cursor, queue]);

  const pumpPdfPrefetch = useCallback(() => {
    if (pdfPrefetchBusy.current) return;
    const paper = pdfPrefetchQueue.current.shift();
    if (!paper) return;
    pdfPrefetchBusy.current = true;
    void fetch(makeApiUrl(`/api/papers/${encodeURIComponent(paper.readerId)}/pdf`), { cache: "force-cache" })
      .catch(() => undefined)
      .finally(() => {
        pdfPrefetchBusy.current = false;
        pumpPdfPrefetch();
      });
  }, []);

  async function handleAction(paper: Paper, action: PaperAction) {
    try {
      const response = await sendAction(paper.id, action, action === "favorite" ? activeFolderId : undefined);
      applyPaper(response.paper);
      if (action === "favorite" || action === "skip") {
        setCursor((value) => Math.min(value + 1, reviewQueueIds.length || queue.length));
      }
      if (action === "favorite") showToast({ tone: "ok", text: `已收藏到「${folderName(favoriteFolders, activeFolderId)}」` });
      if (action === "skip") showToast({ tone: "warn", text: "已略过" });
      if (action === "clear") showToast({ tone: "ok", text: "已移出" });
    } catch (actionError) {
      showToast({ tone: "warn", text: actionError instanceof Error ? actionError.message : String(actionError) });
    }
  }

  async function handleUndo() {
    setCursor((value) => Math.max(0, value - 1));
  }

  async function handleTranslate(paper: Paper, force = false, silent = false) {
    try {
      setTranslatingIds((value) => new Set(value).add(paper.id));
      const response = await translatePaper(paper.id, force);
      if (!silent) {
        const updated = { ...paper, translation: response.translation };
        applyPaper(updated);
        showToast({ tone: "ok", text: "翻译已更新" });
      }
    } catch (translateError) {
      const message = translateError instanceof Error ? translateError.message : String(translateError);
      if (silent && message.includes("AGNES_API_KEY")) {
        setAutoTranslateBlocked(true);
        return;
      }
      if (!silent) showToast({ tone: "warn", text: message });
    } finally {
      setTranslatingIds((value) => {
        const next = new Set(value);
        next.delete(paper.id);
        return next;
      });
    }
  }

  async function handleCreateFolder() {
    const name = folderDraft.trim();
    if (!name) return;
    try {
      const response = await createFavoriteFolder(name);
      setFavoriteFolders(response.favoriteFolders);
      setActiveFolderId(response.favoriteFolder.id);
      setFavoriteFilterId(response.favoriteFolder.id);
      setFolderDraft("");
      showToast({ tone: "ok", text: "文件夹已创建" });
      if (tab === "favorites") void loadFavorites();
    } catch (folderError) {
      showToast({ tone: "warn", text: folderError instanceof Error ? folderError.message : String(folderError) });
    }
  }

  async function handleDeleteFolder(folderId: string) {
    try {
      const response = await deleteFavoriteFolder(folderId);
      setFavoriteFolders(response.favoriteFolders);
      if (activeFolderId === folderId) setActiveFolderId(defaultFolder.id);
      if (favoriteFilterId === folderId) setFavoriteFilterId("all");
      showToast({ tone: "ok", text: "文件夹已删除，论文已移回默认收藏" });
      if (tab === "favorites") void loadFavorites();
    } catch (folderError) {
      showToast({ tone: "warn", text: folderError instanceof Error ? folderError.message : String(folderError) });
    }
  }

  async function handleSavePreferences() {
    try {
      setRefreshing(true);
      const response = await savePreferences(preferences);
      setPapers(response.papers);
      setReviewQueueIds(buildReviewQueueIds(response.papers));
      setFavoriteFolders(response.favoriteFolders?.length ? response.favoriteFolders : [defaultFolder]);
      setLastFetchAt(response.lastFetchAt);
      setLastError(response.lastError);
      setTab("today");
      setCursor(0);
      showToast({ tone: "ok", text: "偏好已保存" });
    } catch (saveError) {
      showToast({ tone: "warn", text: saveError instanceof Error ? saveError.message : String(saveError) });
    } finally {
      setRefreshing(false);
    }
  }

  function addKeyword() {
    const next = keywordDraft.trim();
    if (!next || preferences.keywords.includes(next)) return;
    setPreferences((value) => ({ ...value, keywords: [...value.keywords, next].slice(0, 12) }));
    setKeywordDraft("");
  }

  function removeKeyword(keyword: string) {
    setPreferences((value) => ({
      ...value,
      keywords: value.keywords.filter((item) => item !== keyword)
    }));
  }

  function toggleCategory(category: string) {
    setPreferences((value) => {
      const selected = value.categories.includes(category);
      return {
        ...value,
        categories: selected
          ? value.categories.filter((item) => item !== category)
          : [...value.categories, category].slice(0, 12)
      };
    });
  }

  function saveApiBase() {
    setApiBase(apiDraft);
    showToast({ tone: "ok", text: "服务地址已保存" });
    void load(true);
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <button className="brand" type="button" onClick={() => setTab("today")}>
          <span className="brand-mark">
            <FileText size={18} />
          </span>
          <span>
            <strong>PaperCard</strong>
            <small>arXiv Daily</small>
          </span>
        </button>

        <nav className="topnav" aria-label="主导航">
          <TabButton active={tab === "today"} icon={<BookOpen size={17} />} label="今日" onClick={() => setTab("today")} />
          <TabButton active={tab === "favorites"} icon={<BookmarkCheck size={17} />} label="收藏" onClick={() => setTab("favorites")} />
          <TabButton active={tab === "settings"} icon={<SlidersHorizontal size={17} />} label="偏好" onClick={() => setTab("settings")} />
        </nav>

        <button className="icon-button" type="button" onClick={() => load(true)} disabled={refreshing} title="同步">
          <RefreshCw size={18} className={refreshing ? "spin" : ""} />
        </button>
      </header>

      <main className="workspace">
        <aside className="side-panel">
          <section className="quiet-panel">
            <p className="eyebrow">今日同步</p>
            <h1>每天一叠值得看的论文</h1>
            <div className="sync-row">
              <span>{formatFullDate(lastFetchAt)}</span>
              {lastError ? <span className="status-warn">同步异常</span> : <span className="status-ok">可阅读</span>}
            </div>
          </section>

          <section className="metrics">
            <Metric label="未读" value={stats.remaining} />
            <Metric label="收藏" value={stats.saved} />
            <Metric label="略过" value={stats.skipped} />
          </section>

          <section className="quiet-panel compact">
            <div className="panel-title">
              <Search size={16} />
              <span>关键词</span>
            </div>
            <div className="chips">
              {preferences.keywords.map((keyword) => (
                <span className="chip" key={keyword}>
                  {keyword}
                </span>
              ))}
            </div>
          </section>
        </aside>

        <section className="main-stage">
          {tab === "today" ? (
            <TodayView
              currentPaper={currentPaper}
              favoriteFolders={favoriteFolders}
              activeFolderId={activeFolderId}
              loading={loading}
              error={error}
              refreshing={refreshing}
              remaining={queue.length}
              total={papers.length}
              translatingIds={translatingIds}
              onActiveFolder={setActiveFolderId}
              onRefresh={() => load(true)}
              onAction={handleAction}
              onUndo={handleUndo}
              onTranslate={handleTranslate}
              onPdf={setPdfPaper}
              onOriginal={setOriginalPaper}
              onSettings={() => setTab("settings")}
              canUndo={cursor > 0}
            />
          ) : null}

          {tab === "favorites" ? (
            <FavoritesView
              favorites={favorites}
              favoriteFolders={favoriteFolders}
              favoriteFilterId={favoriteFilterId}
              folderDraft={folderDraft}
              translatingIds={translatingIds}
              onFolderDraft={setFolderDraft}
              onFavoriteFilter={setFavoriteFilterId}
              onCreateFolder={handleCreateFolder}
              onDeleteFolder={handleDeleteFolder}
              onPdf={setPdfPaper}
              onClear={(paper) => handleAction(paper, "clear")}
              onTranslate={handleTranslate}
            />
          ) : null}

          {tab === "settings" ? (
            <SettingsView
              preferences={preferences}
              keywordDraft={keywordDraft}
              apiDraft={apiDraft}
              refreshing={refreshing}
              onKeywordDraft={setKeywordDraft}
              onAddKeyword={addKeyword}
              onRemoveKeyword={removeKeyword}
              onToggleCategory={toggleCategory}
              onMaxResults={(maxResults) => setPreferences((value) => ({ ...value, maxResults }))}
              onPdfReadingMode={(pdfReadingMode) => setPreferences((value) => ({ ...value, pdfReadingMode }))}
              onApiDraft={setApiDraft}
              onSaveApiBase={saveApiBase}
              onSave={handleSavePreferences}
            />
          ) : null}
        </section>

        <aside className="detail-panel">
          <section className="quiet-panel">
            <p className="eyebrow">阅读队列</p>
            <div className="mini-list">
              {queue.slice(0, 5).map((paper) => (
                <button className="mini-paper" type="button" key={paper.id} onClick={() => setPdfPaper(paper)}>
                  <span>{paper.category}</span>
                  <strong>{paper.translation?.title_zh || paper.title}</strong>
                </button>
              ))}
              {!queue.length ? <p className="empty-copy">今日队列已清空</p> : null}
            </div>
          </section>
        </aside>
      </main>

      <nav className="bottom-nav" aria-label="移动导航">
        <TabButton active={tab === "today"} icon={<BookOpen size={18} />} label="今日" onClick={() => setTab("today")} />
        <TabButton active={tab === "favorites"} icon={<Bookmark size={18} />} label="收藏" onClick={() => setTab("favorites")} />
        <TabButton active={tab === "settings"} icon={<Settings size={18} />} label="偏好" onClick={() => setTab("settings")} />
      </nav>

      {pdfPaper ? <PdfModal paper={pdfPaper} onClose={() => setPdfPaper(null)} /> : null}
      {originalPaper ? <OriginalModal paper={originalPaper} onClose={() => setOriginalPaper(null)} /> : null}
      {toast ? <div className={`toast ${toast.tone}`}>{toast.text}</div> : null}
    </div>
  );
}

function TabButton({ active, icon, label, onClick }: { active: boolean; icon: ReactNode; label: string; onClick: () => void }) {
  return (
    <button type="button" className={`tab-button ${active ? "active" : ""}`} onClick={onClick}>
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

interface TodayViewProps {
  currentPaper: Paper | null;
  favoriteFolders: FavoriteFolder[];
  activeFolderId: string;
  loading: boolean;
  error: string;
  refreshing: boolean;
  remaining: number;
  total: number;
  translatingIds: Set<string>;
  canUndo: boolean;
  onActiveFolder: (folderId: string) => void;
  onRefresh: () => void;
  onSettings: () => void;
  onAction: (paper: Paper, action: PaperAction) => Promise<void>;
  onUndo: () => Promise<void>;
  onTranslate: (paper: Paper, force?: boolean, silent?: boolean) => Promise<void>;
  onPdf: (paper: Paper) => void;
  onOriginal: (paper: Paper) => void;
}

function TodayView({
  currentPaper,
  favoriteFolders,
  activeFolderId,
  loading,
  error,
  refreshing,
  remaining,
  total,
  translatingIds,
  canUndo,
  onActiveFolder,
  onRefresh,
  onSettings,
  onAction,
  onUndo,
  onTranslate,
  onPdf,
  onOriginal
}: TodayViewProps) {
  if (loading) {
    return (
      <div className="center-state">
        <Loader2 className="spin" size={28} />
        <span>同步论文中</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="center-state">
        <strong>连接失败</strong>
        <span>{error}</span>
        <button className="primary-button" type="button" onClick={onRefresh}>
          <RefreshCw size={17} />
          重试
        </button>
        <button className="soft-button" type="button" onClick={onSettings}>
          <Settings size={17} />
          服务地址
        </button>
      </div>
    );
  }

  if (!currentPaper) {
    return (
      <div className="center-state">
        <BookmarkCheck size={30} />
        <strong>今日已处理完</strong>
        <span>{total ? "收藏库里已经有你的筛选结果" : "暂时没有匹配的 arXiv 论文"}</span>
        <button className="primary-button" type="button" onClick={onRefresh} disabled={refreshing}>
          <RefreshCw size={17} className={refreshing ? "spin" : ""} />
          重新同步
        </button>
      </div>
    );
  }

  return (
    <div className="deck-wrap">
      <div className="deck-meta">
        <span>{remaining} 篇待看</span>
        <span>{total} 篇同步</span>
      </div>
      <div className="deck-controls">
        <label className="folder-select">
          <Folder size={15} />
          <select value={activeFolderId} onChange={(event) => onActiveFolder(event.target.value)}>
            {favoriteFolders.map((folder) => (
              <option value={folder.id} key={folder.id}>
                {folder.name}
              </option>
            ))}
          </select>
        </label>
        <span>
          <ArrowRight size={15} /> 右划收藏
        </span>
        <span>
          <ArrowUp size={15} /> 上划略过
        </span>
      </div>
      <PaperCard
        key={currentPaper.id}
        paper={currentPaper}
        translating={translatingIds.has(currentPaper.id)}
        onAction={onAction}
        onTranslate={onTranslate}
        onPdf={onPdf}
        onOriginal={onOriginal}
      />
      <div className="action-row">
        <button className="round-action reject" type="button" onClick={() => onAction(currentPaper, "skip")} title="略过">
          <ArrowUp size={24} />
        </button>
        <button className="round-action neutral" type="button" onClick={onUndo} disabled={!canUndo} title="上一条">
          <RotateCcw size={22} />
        </button>
        <button className="round-action accept" type="button" onClick={() => onAction(currentPaper, "favorite")} title="收藏">
          <Check size={25} />
        </button>
      </div>
    </div>
  );
}

interface PaperCardProps {
  paper: Paper;
  translating: boolean;
  onAction: (paper: Paper, action: PaperAction) => Promise<void>;
  onTranslate: (paper: Paper, force?: boolean, silent?: boolean) => Promise<void>;
  onPdf: (paper: Paper) => void;
  onOriginal: (paper: Paper) => void;
}

function PaperCard({ paper, translating, onAction, onTranslate, onPdf, onOriginal }: PaperCardProps) {
  const [start, setStart] = useState<{ x: number; y: number } | null>(null);
  const [drag, setDrag] = useState({ x: 0, y: 0 });
  const rotation = drag.x / 24;
  const accept = drag.x > 70;
  const reject = drag.y < -70;

  function finishDrag() {
    if (drag.x > 110) void onAction(paper, "favorite");
    else if (drag.y < -110) void onAction(paper, "skip");
    setStart(null);
    setDrag({ x: 0, y: 0 });
  }

  return (
    <article
      className="paper-card"
      style={{
        transform: `translate(${drag.x}px, ${drag.y}px) rotate(${rotation}deg)`
      }}
      onPointerDown={(event) => {
        setStart({ x: event.clientX, y: event.clientY });
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      onPointerMove={(event) => {
        if (!start) return;
        setDrag({ x: event.clientX - start.x, y: event.clientY - start.y });
      }}
      onPointerUp={finishDrag}
      onPointerCancel={finishDrag}
    >
      <div className={`swipe-stamp accept ${accept ? "visible" : ""}`}>收藏</div>
      <div className={`swipe-stamp reject ${reject ? "visible" : ""}`}>略过</div>

      <div className="paper-head">
        <div>
          {hasSeenPaper(paper) ? <span className="category seen">看过</span> : null}
          <span className="category">{paper.category}</span>
          <span className="date">{formatDate(paper.published)}</span>
        </div>
        <strong className="score">{paper.relevance}</strong>
      </div>

      <h2>{paper.title}</h2>
      {paper.translation?.title_zh ? <p className="translated-title">{paper.translation.title_zh}</p> : null}
      {!paper.translation?.title_zh && translating ? <p className="translated-title muted">正在生成中文标题...</p> : null}
      <p className="authors">{authorLine(paper)}</p>
      <p className="abstract">{paper.translation?.summary_zh || paper.summary}</p>

      {paper.translation ? <TranslationBlock translation={paper.translation} /> : null}

      <div className="card-tools">
        <button className="soft-button" type="button" onClick={() => onTranslate(paper, Boolean(paper.translation))}>
          {translating ? <Loader2 className="spin" size={17} /> : <Languages size={17} />}
          翻译
        </button>
        <button className="soft-button" type="button" onClick={() => onPdf(paper)}>
          <BookOpen size={17} />
          PDF
        </button>
        <button className="soft-button" type="button" onClick={() => onOriginal(paper)}>
          <ExternalLink size={17} />
          原文
        </button>
      </div>
    </article>
  );
}

function TranslationBlock({ translation }: { translation: Translation }) {
  return (
    <div className="translation-block">
      {translation.key_points.length ? (
        <ul>
          {translation.key_points.map((point) => (
            <li key={point}>{point}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

interface FavoritesViewProps {
  favorites: Paper[];
  favoriteFolders: FavoriteFolder[];
  favoriteFilterId: string;
  folderDraft: string;
  translatingIds: Set<string>;
  onFolderDraft: (value: string) => void;
  onFavoriteFilter: (folderId: string) => void;
  onCreateFolder: () => void;
  onDeleteFolder: (folderId: string) => void;
  onPdf: (paper: Paper) => void;
  onClear: (paper: Paper) => Promise<void>;
  onTranslate: (paper: Paper, force?: boolean, silent?: boolean) => Promise<void>;
}

function FavoritesView({
  favorites,
  favoriteFolders,
  favoriteFilterId,
  folderDraft,
  translatingIds,
  onFolderDraft,
  onFavoriteFilter,
  onCreateFolder,
  onDeleteFolder,
  onPdf,
  onClear,
  onTranslate
}: FavoritesViewProps) {
  return (
    <div className="library">
      <div className="section-head">
        <div>
          <p className="eyebrow">收藏库</p>
          <h2>{favorites.length} 篇论文</h2>
        </div>
        <a className="soft-button" href={makeApiUrl(`/api/export/bibtex?folderId=${encodeURIComponent(favoriteFilterId)}`)}>
          <ArrowDownToLine size={17} />
          BibTeX
        </a>
      </div>

      <section className="folder-manager">
        <div className="folder-filter">
          <button className={`folder-chip ${favoriteFilterId === "all" ? "selected" : ""}`} type="button" onClick={() => onFavoriteFilter("all")}>
            <Folder size={15} />
            全部收藏
          </button>
          {favoriteFolders.map((folder) => (
            <button
              className={`folder-chip ${favoriteFilterId === folder.id ? "selected" : ""}`}
              type="button"
              key={folder.id}
              onClick={() => onFavoriteFilter(folder.id)}
            >
              <Folder size={15} />
              {folder.name}
            </button>
          ))}
        </div>
        <div className="input-row">
          <input
            value={folderDraft}
            onChange={(event) => onFolderDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") onCreateFolder();
            }}
            maxLength={24}
            placeholder="新建文件夹"
          />
          <button className="soft-button" type="button" onClick={onCreateFolder}>
            <FolderPlus size={17} />
            创建
          </button>
        </div>
      </section>

      {favoriteFilterId !== "all" && favoriteFilterId !== defaultFolder.id ? (
        <button className="danger-button" type="button" onClick={() => onDeleteFolder(favoriteFilterId)}>
          <Trash2 size={16} />
          删除当前文件夹
        </button>
      ) : null}

      {favorites.length ? (
        <div className="favorite-list">
          {favorites.map((paper) => (
            <article className="favorite-item" key={paper.id}>
              <div>
                <span className="category">{paper.category}</span>
                <span className="folder-label">{folderName(favoriteFolders, paper.favoriteFolderId || defaultFolder.id)}</span>
                <h3>{paper.translation?.title_zh || paper.title}</h3>
                <p>{authorLine(paper)}</p>
              </div>
              <div className="favorite-actions">
                <button className="icon-button" type="button" onClick={() => onTranslate(paper, Boolean(paper.translation))} title="翻译">
                  {translatingIds.has(paper.id) ? <Loader2 className="spin" size={17} /> : <Languages size={17} />}
                </button>
                <button className="icon-button" type="button" onClick={() => onPdf(paper)} title="PDF">
                  <BookOpen size={17} />
                </button>
                <button className="icon-button" type="button" onClick={() => onClear(paper)} title="移出收藏">
                  <X size={17} />
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="center-state small">
          <Bookmark size={28} />
          <span>这里还没有收藏</span>
        </div>
      )}
    </div>
  );
}

interface SettingsViewProps {
  preferences: Preferences;
  keywordDraft: string;
  apiDraft: string;
  refreshing: boolean;
  onKeywordDraft: (value: string) => void;
  onAddKeyword: () => void;
  onRemoveKeyword: (keyword: string) => void;
  onToggleCategory: (category: string) => void;
  onMaxResults: (value: number) => void;
  onPdfReadingMode: (value: "paged" | "continuous") => void;
  onApiDraft: (value: string) => void;
  onSaveApiBase: () => void;
  onSave: () => void;
}

function SettingsView({
  preferences,
  keywordDraft,
  apiDraft,
  refreshing,
  onKeywordDraft,
  onAddKeyword,
  onRemoveKeyword,
  onToggleCategory,
  onMaxResults,
  onPdfReadingMode,
  onApiDraft,
  onSaveApiBase,
  onSave
}: SettingsViewProps) {
  return (
    <div className="settings-view">
      <div className="section-head">
        <div>
          <p className="eyebrow">个性化</p>
          <h2>订阅偏好</h2>
        </div>
        <button className="primary-button" type="button" onClick={onSave} disabled={refreshing}>
          {refreshing ? <Loader2 className="spin" size={17} /> : <Sparkles size={17} />}
          保存
        </button>
      </div>

      <section className="setting-section">
        <label className="field-label" htmlFor="keyword-input">
          关键词
        </label>
        <div className="input-row">
          <input
            id="keyword-input"
            value={keywordDraft}
            onChange={(event) => onKeywordDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") onAddKeyword();
            }}
            maxLength={48}
            placeholder="例如 multimodal agent"
          />
          <button className="soft-button" type="button" onClick={onAddKeyword}>
            <Plus size={17} />
            添加
          </button>
        </div>
        <div className="chips editable">
          {preferences.keywords.map((keyword) => (
            <button className="chip removable" type="button" key={keyword} onClick={() => onRemoveKeyword(keyword)}>
              {keyword}
              <X size={14} />
            </button>
          ))}
        </div>
      </section>

      <section className="setting-section">
        <label className="field-label">分类</label>
        <div className="category-grid">
          {categories.map((category) => (
            <button
              className={`category-toggle ${preferences.categories.includes(category) ? "selected" : ""}`}
              type="button"
              key={category}
              onClick={() => onToggleCategory(category)}
            >
              {category}
            </button>
          ))}
        </div>
      </section>

      <section className="setting-section">
        <label className="field-label" htmlFor="max-results">
          每次同步 {preferences.maxResults} 篇
        </label>
        <input
          id="max-results"
          className="range"
          type="range"
          min={12}
          max={80}
          step={4}
          value={preferences.maxResults}
          onChange={(event) => onMaxResults(Number(event.target.value))}
        />
      </section>

      <section className="setting-section">
        <label className="field-label">PDF 阅读方式</label>
        <div className="category-grid">
          <button
            className={`category-toggle ${preferences.pdfReadingMode !== "continuous" ? "selected" : ""}`}
            type="button"
            onClick={() => onPdfReadingMode("paged")}
          >
            翻页阅读
          </button>
          <button
            className={`category-toggle ${preferences.pdfReadingMode === "continuous" ? "selected" : ""}`}
            type="button"
            onClick={() => onPdfReadingMode("continuous")}
          >
            连续阅读
          </button>
        </div>
      </section>

      <section className="setting-section">
        <label className="field-label" htmlFor="api-base">
          服务地址
        </label>
        <div className="input-row">
          <input id="api-base" value={apiDraft} onChange={(event) => onApiDraft(event.target.value)} placeholder="http://192.168.1.10:4173" />
          <button className="soft-button" type="button" onClick={onSaveApiBase}>
            应用
          </button>
        </div>
      </section>
    </div>
  );
}

function OriginalModal({ paper, onClose }: { paper: Paper; onClose: () => void }) {
  return (
    <div className="modal-backdrop">
      <section className="original-modal">
        <header>
          <div>
            <span>{paper.category}</span>
            <strong>{paper.title}</strong>
          </div>
          <button className="icon-button" type="button" onClick={onClose} title="关闭">
            <X size={18} />
          </button>
        </header>
        <div className="original-body">
          <p className="authors">{authorLine(paper)}</p>
          <p className="abstract">{paper.summary}</p>
          <a className="soft-button" href={paper.absUrl} target="_blank" rel="noreferrer">
            <ExternalLink size={17} />
            打开 arXiv
          </a>
        </div>
      </section>
    </div>
  );
}

function PdfModal({ paper, onClose }: { paper: Paper; onClose: () => void }) {
  const pdfUrl = makeApiUrl(`/api/papers/${encodeURIComponent(paper.readerId)}/pdf`);
  const downloadUrl = `${pdfUrl}?download=1`;
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const renderTaskRef = useRef<RenderTask | null>(null);
  const [documentProxy, setDocumentProxy] = useState<PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [busy, setBusy] = useState(true);
  const [loadError, setLoadError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setBusy(true);
    setLoadError("");
    setDocumentProxy(null);
    setPage(1);
    setPages(0);

    const loadingTask = getDocument({ url: pdfUrl });
    loadingTask.promise
      .then((pdf) => {
        if (cancelled) return;
        setDocumentProxy(pdf);
        setPages(pdf.numPages);
      })
      .catch((error) => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error));
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });

    return () => {
      cancelled = true;
      renderTaskRef.current?.cancel();
      void loadingTask.destroy();
    };
  }, [pdfUrl]);

  useEffect(() => {
    if (!documentProxy || !canvasRef.current) return;
    let cancelled = false;
    setBusy(true);
    setLoadError("");
    renderTaskRef.current?.cancel();

    documentProxy
      .getPage(page)
      .then((pdfPage) => {
        if (cancelled || !canvasRef.current) return;
        const canvas = canvasRef.current;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Canvas is unavailable.");

        const containerWidth = canvas.parentElement?.clientWidth || window.innerWidth;
        const baseViewport = pdfPage.getViewport({ scale: 1 });
        const fitScale = Math.max(0.55, Math.min(1.6, (containerWidth - 28) / baseViewport.width));
        const viewport = pdfPage.getViewport({ scale: fitScale * zoom });
        const ratio = window.devicePixelRatio || 1;

        canvas.width = Math.floor(viewport.width * ratio);
        canvas.height = Math.floor(viewport.height * ratio);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.clearRect(0, 0, viewport.width, viewport.height);

        const renderTask = pdfPage.render({ canvas, canvasContext: context, viewport });
        renderTaskRef.current = renderTask;
        return renderTask.promise;
      })
      .catch((error) => {
        if (!cancelled && error?.name !== "RenderingCancelledException") {
          setLoadError(error instanceof Error ? error.message : String(error));
        }
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });

    return () => {
      cancelled = true;
      renderTaskRef.current?.cancel();
    };
  }, [documentProxy, page, zoom]);

  return (
    <div className="modal-backdrop">
      <section className="pdf-modal">
        <header>
          <div>
            <span>{paper.category}</span>
            <strong>{paper.translation?.title_zh || paper.title}</strong>
          </div>
          <div className="pdf-actions">
            <a className="icon-button" href={downloadUrl} title="下载 PDF">
              <ArrowDownToLine size={18} />
            </a>
            <button className="icon-button" type="button" onClick={onClose} title="关闭">
              <X size={18} />
            </button>
          </div>
        </header>
        <div className="pdf-toolbar">
          <button className="icon-button" type="button" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1}>
            <ChevronLeft size={18} />
          </button>
          <span>
            {page} / {pages || "-"}
          </span>
          <button className="icon-button" type="button" onClick={() => setPage((value) => Math.min(pages || value, value + 1))} disabled={!pages || page >= pages}>
            <ChevronRight size={18} />
          </button>
        </div>
        <div
          className="pdf-reader"
          onWheel={(event) => {
            if (!event.ctrlKey) return;
            event.preventDefault();
            setZoom((value) => Math.max(0.75, Math.min(1.9, value + (event.deltaY < 0 ? 0.12 : -0.12))));
          }}
        >
          <div className="pdf-zoom-float">
            <button className="icon-button" type="button" onClick={() => setZoom((value) => Math.max(0.75, value - 0.15))} title="缩小 PDF">
              -
            </button>
            <button className="icon-button" type="button" onClick={() => setZoom((value) => Math.min(1.9, value + 0.15))} title="放大 PDF">
              +
            </button>
          </div>
          {busy ? (
            <div className="pdf-loading">
              <Loader2 className="spin" size={24} />
              <span>载入 PDF</span>
            </div>
          ) : null}
          {loadError ? <div className="pdf-error">{loadError}</div> : null}
          <canvas ref={canvasRef} />
        </div>
      </section>
    </div>
  );
}

export default App;
