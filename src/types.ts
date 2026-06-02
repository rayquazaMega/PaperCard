export interface Translation {
  title_zh: string;
  summary_zh: string;
  key_points: string[];
  reading_note: string;
}

export interface Paper {
  id: string;
  readerId: string;
  title: string;
  summary: string;
  authors: string[];
  published: string;
  updated: string;
  category: string;
  absUrl: string;
  pdfUrl: string;
  relevance: number;
  favoriteAt: string | null;
  favoriteFolderId: string | null;
  skippedAt: string | null;
  translation: Translation | null;
}

export interface PaperImage {
  url: string;
  alt: string;
  caption: string;
}

export interface PaperHtmlResponse {
  paperId: string;
  readerId: string;
  sourceUrl: string;
  fetchedAt: string | null;
  htmlLength: number;
  images: PaperImage[];
  imageTruncated: boolean;
  html?: string;
  htmlTruncated?: boolean;
}

export interface DeepReadMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  paperIds?: string[];
}

export interface DeepReadThread {
  id: string;
  title: string;
  paperIds: string[];
  messages: DeepReadMessage[];
  createdAt: string;
  updatedAt: string;
}

export interface FavoriteFolder {
  id: string;
  name: string;
  createdAt: string;
}

export interface Preferences {
  keywords: string[];
  categories: string[];
  maxResults: number;
  language: string;
  pdfReadingMode?: "paged" | "continuous";
}

export interface PapersResponse {
  papers: Paper[];
  favoriteFolders: FavoriteFolder[];
  preferences: Preferences;
  lastFetchAt: string | null;
  lastQuery: string;
  lastError: string | null;
}

export interface TranslateResponse {
  paperId: string;
  translatedAt: string;
  translation: Translation;
}

export interface DeepReadChatResponse {
  message: {
    role: "assistant";
    content: string;
    createdAt: string;
  };
}
