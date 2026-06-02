import type {
  DeepReadChatResponse,
  DeepReadMessage,
  FavoriteFolder,
  Paper,
  PaperHtmlResponse,
  PapersResponse,
  Preferences,
  TranslateResponse
} from "./types";

const API_BASE_KEY = "papercard.apiBase";

export function getApiBase() {
  const fromEnv = import.meta.env.VITE_API_BASE_URL as string | undefined;
  const saved = localStorage.getItem(API_BASE_KEY) || "";
  const nativeDefault =
    typeof window !== "undefined" && window.Capacitor?.isNativePlatform?.() ? "http://10.0.2.2:4173" : "";
  return (saved || fromEnv || nativeDefault).replace(/\/$/, "");
}

export function setApiBase(value: string) {
  const clean = value.trim().replace(/\/$/, "");
  if (clean) localStorage.setItem(API_BASE_KEY, clean);
  else localStorage.removeItem(API_BASE_KEY);
}

export function makeApiUrl(path: string) {
  const base = getApiBase();
  return `${base}${path}`;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(makeApiUrl(path), {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers || {})
    }
  });

  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(data?.error || `Request failed: ${response.status}`);
  }
  return data as T;
}

export function fetchPapers(refresh = false) {
  return request<PapersResponse>(`/api/papers${refresh ? "?refresh=1" : ""}`);
}

export function savePreferences(preferences: Preferences) {
  return request<{
    preferences: Preferences;
    papers: Paper[];
    favoriteFolders: FavoriteFolder[];
    lastFetchAt: string | null;
    lastError: string | null;
  }>(
    "/api/preferences",
    {
      method: "PUT",
      body: JSON.stringify(preferences)
    }
  );
}

export function sendAction(paperId: string, action: "favorite" | "skip" | "clear", folderId?: string) {
  return request<{ paper: Paper; favorites: number; skipped: number }>("/api/actions", {
    method: "POST",
    body: JSON.stringify({ paperId, action, folderId })
  });
}

export function translatePaper(paperId: string, force = false) {
  return request<TranslateResponse>("/api/translate", {
    method: "POST",
    body: JSON.stringify({ paperId, force })
  });
}

export function fetchPaperHtml(readerId: string, includeHtml = false, refresh = false) {
  const query = new URLSearchParams();
  if (includeHtml) query.set("includeHtml", "1");
  if (refresh) query.set("refresh", "1");
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<PaperHtmlResponse>(`/api/papers/${encodeURIComponent(readerId)}/html${suffix}`);
}

export function chatDeepRead(paperIds: string[], messages: Pick<DeepReadMessage, "role" | "content">[]) {
  return request<DeepReadChatResponse>("/api/deep-read/chat", {
    method: "POST",
    body: JSON.stringify({ paperIds, messages })
  });
}

export function fetchFavorites(folderId = "all") {
  return request<{ favorites: Paper[]; favoriteFolders: FavoriteFolder[] }>(
    `/api/favorites?folderId=${encodeURIComponent(folderId)}`
  );
}

export function fetchFavoriteFolders() {
  return request<{ favoriteFolders: FavoriteFolder[] }>("/api/folders");
}

export function createFavoriteFolder(name: string) {
  return request<{ favoriteFolder: FavoriteFolder; favoriteFolders: FavoriteFolder[] }>("/api/folders", {
    method: "POST",
    body: JSON.stringify({ name })
  });
}

export function deleteFavoriteFolder(folderId: string) {
  return request<{ favoriteFolders: FavoriteFolder[] }>("/api/folders/" + encodeURIComponent(folderId), {
    method: "DELETE"
  });
}
