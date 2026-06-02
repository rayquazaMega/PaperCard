import "dotenv/config";
import cors from "cors";
import express from "express";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import { XMLParser } from "fast-xml-parser";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const dataDir = path.join(rootDir, "data");
const storePath = path.join(dataDir, "store.json");
const pdfCacheDir = path.join(dataDir, "pdf-cache");
const htmlCacheDir = path.join(dataDir, "html-cache");
const port = Number(process.env.PORT || 4173);
const maxPdfBytes = 80 * 1024 * 1024;
const maxHtmlBytes = 4 * 1024 * 1024;
const maxUiHtmlChars = 60000;
const maxPromptHtmlChars = 45000;
const maxPaperImages = 80;
const maxDeepReadPapers = 6;
const maxDeepReadMessages = 14;

const defaultPreferences = {
  keywords: ["large language model", "retrieval augmented generation", "agent"],
  categories: ["cs.AI", "cs.CL", "cs.LG"],
  maxResults: 36,
  language: "zh-CN",
  pdfReadingMode: "paged"
};

const defaultFavoriteFolder = {
  id: "default",
  name: "默认收藏",
  createdAt: "2026-01-01T00:00:00.000Z"
};

const defaultStore = {
  preferences: defaultPreferences,
  papers: [],
  favoriteFolders: [defaultFavoriteFolder],
  favorites: {},
  skipped: {},
  translations: {},
  lastFetchAt: null,
  lastQuery: "",
  lastError: null
};

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@_",
  textNodeName: "#text"
});

function asArray(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

function normalizeText(value = "") {
  return String(value).replace(/\s+/g, " ").trim();
}

function normalizeFavoriteFolders(value) {
  const folders = asArray(value)
    .map((folder) => ({
      id: normalizeText(folder?.id).replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 48),
      name: normalizeText(folder?.name).slice(0, 24),
      createdAt: folder?.createdAt || new Date().toISOString()
    }))
    .filter((folder) => folder.id && folder.name);

  const withDefault = folders.some((folder) => folder.id === defaultFavoriteFolder.id)
    ? folders
    : [defaultFavoriteFolder, ...folders];

  return withDefault
    .filter((folder, index, list) => list.findIndex((item) => item.id === folder.id) === index)
    .slice(0, 24);
}

function getFavoriteFolderId(store, folderId) {
  const clean = normalizeText(folderId || "");
  return store.favoriteFolders.some((folder) => folder.id === clean) ? clean : defaultFavoriteFolder.id;
}

function readerIdFromArxivId(arxivId) {
  return arxivId.replace(/[^a-zA-Z0-9._-]/g, "_");
}

function validatePdfUrl(rawUrl) {
  const url = new URL(normalizeText(rawUrl));
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "https:" || (host !== "arxiv.org" && !host.endsWith(".arxiv.org"))) {
    throw new Error("Only HTTPS arXiv PDFs are allowed.");
  }
  return url;
}

function isAllowedHtmlAssetHost(hostname) {
  const host = normalizeText(hostname).toLowerCase();
  return host === "arxiv.org" || host.endsWith(".arxiv.org");
}

function validateHtmlSourceUrl(rawUrl) {
  const url = new URL(normalizeText(rawUrl));
  if (url.protocol !== "https:" || !isAllowedHtmlAssetHost(url.hostname)) {
    throw new Error("Only HTTPS arXiv HTML sources are allowed.");
  }
  return url;
}

function arxivIdPath(arxivId) {
  return normalizeText(arxivId)
    .split("/")
    .filter(Boolean)
    .map((part) => encodeURIComponent(part))
    .join("/");
}

function buildHtmlCandidates(paper) {
  const idPath = arxivIdPath(paper.id || paper.readerId);
  const candidates = [];

  try {
    const absUrl = new URL(normalizeText(paper.absUrl || `https://arxiv.org/abs/${idPath}`));
    const host = absUrl.hostname.toLowerCase();
    if (absUrl.protocol === "https:" && (host === "arxiv.org" || host.endsWith(".arxiv.org"))) {
      const htmlPath = absUrl.pathname.includes("/abs/")
        ? absUrl.pathname.replace("/abs/", "/html/")
        : `/html/${idPath}`;
      candidates.push(new URL(htmlPath, "https://arxiv.org").href);
    }
  } catch {
    // Fall back to the normalized id path below.
  }

  if (idPath) {
    candidates.push(`https://arxiv.org/html/${idPath}`);
    candidates.push(`https://ar5iv.labs.arxiv.org/html/${idPath}`);
  }

  return candidates.filter((url, index, list) => url && list.indexOf(url) === index);
}

function decodeHtmlEntities(value = "") {
  return String(value)
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, (_match, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_match, code) => String.fromCodePoint(Number.parseInt(code, 16)));
}

function extractAttr(tag, name) {
  const match = String(tag).match(new RegExp(`${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, "i"));
  return decodeHtmlEntities(match?.[1] || match?.[2] || match?.[3] || "");
}

function stripTags(value = "") {
  return normalizeText(decodeHtmlEntities(String(value).replace(/<[^>]+>/g, " ")));
}

function normalizeImageUrl(rawUrl, baseUrl) {
  const raw = normalizeText(decodeHtmlEntities(rawUrl));
  if (!raw || raw.startsWith("data:") || raw.startsWith("blob:")) return "";

  try {
    const url = new URL(raw, baseUrl);
    if (url.protocol !== "https:" || !isAllowedHtmlAssetHost(url.hostname)) return "";
    if (url.pathname.includes("/static/browse/") || /arxiv-logo/i.test(url.pathname)) return "";
    return url.href;
  } catch {
    return "";
  }
}

function extractPaperImages(html, sourceUrl) {
  const images = [];
  const seen = new Set();

  function addImage(tag, caption = "") {
    const url = normalizeImageUrl(extractAttr(tag, "src") || extractAttr(tag, "data-src"), sourceUrl);
    if (!url || seen.has(url)) return;
    seen.add(url);
    images.push({
      url,
      alt: stripTags(extractAttr(tag, "alt")).slice(0, 180),
      caption: stripTags(caption).slice(0, 260)
    });
  }

  for (const figureMatch of String(html).matchAll(/<figure\b[\s\S]*?<\/figure>/gi)) {
    const figure = figureMatch[0];
    const caption = figure.match(/<figcaption\b[\s\S]*?<\/figcaption>/i)?.[0] || "";
    for (const imageMatch of figure.matchAll(/<img\b[^>]*>/gi)) {
      addImage(imageMatch[0], caption);
      if (images.length >= maxPaperImages) return images;
    }
  }

  for (const imageMatch of String(html).matchAll(/<img\b[^>]*>/gi)) {
    addImage(imageMatch[0], "");
    if (images.length >= maxPaperImages) return images;
  }

  return images;
}

function compactHtml(html, limit) {
  const cleaned = String(html)
    .replace(/<script\b[\s\S]*?<\/script>/gi, "")
    .replace(/<style\b[\s\S]*?<\/style>/gi, "")
    .replace(/<noscript\b[\s\S]*?<\/noscript>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\s{2,}/g, " ")
    .trim();
  return cleaned.slice(0, limit);
}

async function fetchPaperHtml(paper) {
  const errors = [];
  for (const candidate of buildHtmlCandidates(paper)) {
    try {
      validateHtmlSourceUrl(candidate);
      const upstream = await fetch(candidate, {
        headers: {
          "User-Agent": "PaperCard/0.1 contact: local-prototype"
        },
        signal: AbortSignal.timeout(25000)
      });
      const sourceUrl = validateHtmlSourceUrl(upstream.url || candidate).href;
      if (!upstream.ok) {
        errors.push(`${sourceUrl}: ${upstream.status}`);
        continue;
      }

      const contentType = normalizeText(upstream.headers.get("content-type")).toLowerCase();
      if (contentType && !contentType.includes("text/html")) {
        errors.push(`${sourceUrl}: unsupported content type ${contentType}`);
        continue;
      }

      const contentLength = Number(upstream.headers.get("content-length") || 0);
      if (contentLength > maxHtmlBytes) throw new Error("HTML is too large.");

      const html = await upstream.text();
      if (Buffer.byteLength(html, "utf8") > maxHtmlBytes) throw new Error("HTML is too large.");
      return { html, sourceUrl };
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }
  }

  const message = errors.length ? errors.join("; ") : "No HTML source candidate found.";
  const error = new Error(`Unable to fetch HTML: ${message}`);
  error.status = 502;
  throw error;
}

async function readPaperHtml(paper, force = false) {
  await fsp.mkdir(htmlCacheDir, { recursive: true });
  const safeReaderId = readerIdFromArxivId(paper.readerId || paper.id);
  const htmlPath = path.join(htmlCacheDir, `${safeReaderId}.html`);
  const metaPath = path.join(htmlCacheDir, `${safeReaderId}.json`);

  if (!force && fs.existsSync(htmlPath) && fs.statSync(htmlPath).size > 0) {
    const html = await fsp.readFile(htmlPath, "utf8");
    let meta = {};
    try {
      meta = JSON.parse(await fsp.readFile(metaPath, "utf8"));
    } catch {
      // Old or manually cleaned cache entries can still be served.
    }
    return {
      html,
      sourceUrl: normalizeText(meta.sourceUrl) || buildHtmlCandidates(paper)[0] || paper.absUrl,
      fetchedAt: normalizeText(meta.fetchedAt) || null
    };
  }

  const { html, sourceUrl } = await fetchPaperHtml(paper);
  const fetchedAt = new Date().toISOString();
  const tmpHtmlPath = `${htmlPath}.${Date.now()}.tmp`;
  const tmpMetaPath = `${metaPath}.${Date.now()}.tmp`;
  await fsp.writeFile(tmpHtmlPath, html, "utf8");
  await fsp.rename(tmpHtmlPath, htmlPath);
  await fsp.writeFile(tmpMetaPath, JSON.stringify({ sourceUrl, fetchedAt }, null, 2), "utf8");
  await fsp.rename(tmpMetaPath, metaPath);
  return { html, sourceUrl, fetchedAt };
}

async function paperHtmlPayload(paper, { includeHtml = false, force = false } = {}) {
  const record = await readPaperHtml(paper, force);
  const images = extractPaperImages(record.html, record.sourceUrl);
  return {
    paperId: paper.id,
    readerId: paper.readerId,
    sourceUrl: record.sourceUrl,
    fetchedAt: record.fetchedAt,
    htmlLength: record.html.length,
    images,
    imageTruncated: images.length >= maxPaperImages,
    html: includeHtml ? compactHtml(record.html, maxUiHtmlChars) : undefined,
    htmlTruncated: includeHtml ? record.html.length > maxUiHtmlChars : undefined
  };
}

function sendPdf(response, filePath, fileName, download) {
  response.setHeader("Content-Type", "application/pdf");
  response.setHeader(
    "Content-Disposition",
    download ? `attachment; filename="${fileName}"` : `inline; filename="${fileName}"`
  );
  fs.createReadStream(filePath).pipe(response);
}

async function cachePdf(paper, filePath) {
  const url = validatePdfUrl(paper.pdfUrl);
  const upstream = await fetch(url, {
    headers: {
      "User-Agent": "PaperCard/0.1 contact: local-prototype"
    }
  });
  if (!upstream.ok || !upstream.body) {
    const error = new Error(`Unable to fetch PDF: ${upstream.status}`);
    error.status = upstream.status || 502;
    throw error;
  }

  const contentLength = Number(upstream.headers.get("content-length") || 0);
  if (contentLength > maxPdfBytes) throw new Error("PDF is too large.");

  await fsp.mkdir(pdfCacheDir, { recursive: true });
  const tmpPath = `${filePath}.${Date.now()}.tmp`;
  let total = 0;
  try {
    await new Promise((resolve, reject) => {
      const input = Readable.fromWeb(upstream.body);
      const output = fs.createWriteStream(tmpPath);
      input.on("data", (chunk) => {
        total += chunk.length;
        if (total > maxPdfBytes) input.destroy(new Error("PDF is too large."));
      });
      input.on("error", reject);
      output.on("error", reject);
      output.on("finish", resolve);
      input.pipe(output);
    });
    await fsp.rename(tmpPath, filePath);
  } catch (error) {
    await fsp.rm(tmpPath, { force: true });
    throw error;
  }
}

async function ensureStore() {
  await fsp.mkdir(dataDir, { recursive: true });
  if (!fs.existsSync(storePath)) {
    await saveStore(defaultStore);
    return structuredClone(defaultStore);
  }

  try {
    const loaded = JSON.parse(await fsp.readFile(storePath, "utf8"));
    return {
      ...structuredClone(defaultStore),
      ...loaded,
      preferences: {
        ...defaultPreferences,
        ...(loaded.preferences || {})
      },
      favoriteFolders: normalizeFavoriteFolders(loaded.favoriteFolders),
      favorites: loaded.favorites || {},
      skipped: loaded.skipped || {},
      translations: loaded.translations || {}
    };
  } catch (error) {
    console.warn("Failed to read store.json, starting with defaults:", error);
    return structuredClone(defaultStore);
  }
}

async function saveStore(store) {
  await fsp.mkdir(dataDir, { recursive: true });
  const tmpPath = `${storePath}.tmp`;
  await fsp.writeFile(tmpPath, JSON.stringify(store, null, 2), "utf8");
  await fsp.rename(tmpPath, storePath);
}

function sanitizePreferences(input = {}) {
  const keywords = asArray(input.keywords)
    .map((item) => normalizeText(item).slice(0, 48))
    .filter(Boolean)
    .filter((item, index, list) => list.indexOf(item) === index)
    .slice(0, 12);

  const categories = asArray(input.categories)
    .map((item) => normalizeText(item))
    .filter((item) => /^[a-z-]+(\.[A-Z]{2})?$/.test(item))
    .filter((item, index, list) => list.indexOf(item) === index)
    .slice(0, 12);

  const maxResults = Math.min(Math.max(Number(input.maxResults || defaultPreferences.maxResults), 12), 80);

  return {
    ...defaultPreferences,
    keywords: keywords.length ? keywords : defaultPreferences.keywords,
    categories: categories.length ? categories : defaultPreferences.categories,
    maxResults,
    language: normalizeText(input.language || defaultPreferences.language) || defaultPreferences.language,
    pdfReadingMode: input.pdfReadingMode === "continuous" ? "continuous" : "paged"
  };
}

function quoteTerm(term) {
  const cleaned = term.replace(/["()]/g, " ").replace(/\s+/g, " ").trim();
  if (!cleaned) return "";
  return cleaned.includes(" ") ? `"${cleaned}"` : cleaned;
}

function buildArxivQuery(preferences) {
  const keywordParts = preferences.keywords
    .map(quoteTerm)
    .filter(Boolean)
    .flatMap((term) => [`ti:${term}`, `abs:${term}`]);

  const categoryParts = preferences.categories.map((category) => `cat:${category}`);

  if (keywordParts.length && categoryParts.length) {
    return `(${keywordParts.join(" OR ")}) AND (${categoryParts.join(" OR ")})`;
  }

  if (keywordParts.length) return keywordParts.join(" OR ");
  return categoryParts.join(" OR ");
}

function calculateRelevance(entry, preferences) {
  const haystack = `${entry.title} ${entry.summary} ${entry.category}`.toLowerCase();
  const matchedKeywords = preferences.keywords.filter((keyword) => haystack.includes(keyword.toLowerCase()));
  const categoryBoost = preferences.categories.includes(entry.category) ? 14 : 0;
  const keywordBoost = Math.min(matchedKeywords.length * 16, 32);
  const publishedAt = new Date(entry.published).getTime();
  const ageDays = Number.isFinite(publishedAt) ? (Date.now() - publishedAt) / 86400000 : 30;
  const recencyBoost = Math.max(0, 18 - Math.floor(ageDays));
  return Math.max(38, Math.min(99, 45 + categoryBoost + keywordBoost + recencyBoost));
}

function parsePaper(entry, preferences) {
  const absUrl = normalizeText(entry.id);
  const arxivId = absUrl.split("/abs/").pop() || absUrl.split("/").pop() || randomUUID();
  const links = asArray(entry.link);
  const pdfLink =
    links.find((link) => link?.["@_title"] === "pdf") ||
    links.find((link) => normalizeText(link?.["@_type"]) === "application/pdf");
  const category =
    entry["arxiv:primary_category"]?.["@_term"] ||
    asArray(entry.category)[0]?.["@_term"] ||
    "arXiv";

  const paper = {
    id: arxivId,
    readerId: readerIdFromArxivId(arxivId),
    title: normalizeText(entry.title),
    summary: normalizeText(entry.summary),
    authors: asArray(entry.author)
      .map((author) => normalizeText(author?.name || author))
      .filter(Boolean)
      .slice(0, 10),
    published: entry.published,
    updated: entry.updated,
    category,
    absUrl,
    pdfUrl: normalizeText(pdfLink?.["@_href"]) || `https://arxiv.org/pdf/${arxivId}`,
    relevance: 0
  };

  paper.relevance = calculateRelevance(paper, preferences);
  return paper;
}

async function fetchArxiv(preferences) {
  const query = buildArxivQuery(preferences);
  const url = new URL("https://export.arxiv.org/api/query");
  url.searchParams.set("search_query", query);
  url.searchParams.set("start", "0");
  url.searchParams.set("max_results", String(preferences.maxResults));
  url.searchParams.set("sortBy", "submittedDate");
  url.searchParams.set("sortOrder", "descending");

  const response = await fetch(url, {
    headers: {
      "User-Agent": "PaperCard/0.1 contact: local-prototype"
    }
  });

  if (!response.ok) {
    throw new Error(`arXiv returned ${response.status}`);
  }

  const xml = await response.text();
  const data = parser.parse(xml);
  const entries = asArray(data?.feed?.entry);
  const papers = entries.map((entry) => parsePaper(entry, preferences));

  return { papers, query };
}

async function refreshIfNeeded(store, force = false) {
  const lastFetch = store.lastFetchAt ? new Date(store.lastFetchAt).getTime() : 0;
  const stale = Date.now() - lastFetch > 12 * 60 * 60 * 1000;
  if (!force && store.papers.length && !stale) return store;

  try {
    const { papers, query } = await fetchArxiv(store.preferences);
    store.papers = papers;
    store.lastQuery = query;
    store.lastFetchAt = new Date().toISOString();
    store.lastError = null;
    await saveStore(store);
  } catch (error) {
    store.lastError = error instanceof Error ? error.message : String(error);
    await saveStore(store);
  }

  return store;
}

function withUserState(store, paper) {
  const favorite = store.favorites[paper.id];
  return {
    ...paper,
    favoriteAt: favorite?.favoriteAt || null,
    favoriteFolderId: favorite?.folderId || null,
    skippedAt: store.skipped[paper.id]?.skippedAt || null,
    translation: store.translations[paper.id]?.translation || null
  };
}

function getPaper(store, paperId) {
  const paper =
    store.papers.find((item) => item.id === paperId || item.readerId === paperId) ||
    store.favorites[paperId]?.paper ||
    Object.values(store.favorites).find((item) => item.paper?.readerId === paperId)?.paper;
  return paper || null;
}

function parseTranslationContent(content) {
  const trimmed = normalizeText(content);
  try {
    return JSON.parse(trimmed);
  } catch {
    const match = content.match(/\{[\s\S]*\}/);
    if (match) {
      try {
        return JSON.parse(match[0]);
      } catch {
        // Fall through to a plain summary if the model returned commentary.
      }
    }
  }

  return {
    title_zh: "",
    summary_zh: trimmed,
    key_points: [],
    reading_note: ""
  };
}

async function translatePaper(paper) {
  const apiKey = process.env.AGNES_API_KEY;
  if (!apiKey) {
    const error = new Error("AGNES_API_KEY is not configured on the server.");
    error.status = 400;
    throw error;
  }

  const apiUrl = process.env.AGNES_API_URL || "https://apihub.agnes-ai.com/v1/chat/completions";
  const model = process.env.AGNES_MODEL || "agnes-2.0-flash";
  const response = await fetch(apiUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model,
      temperature: 0.2,
      max_tokens: 900,
      messages: [
        {
          role: "system",
          content:
            "你是严谨的科研论文助手。请只返回合法 JSON，不要使用 Markdown，不要添加 JSON 之外的解释。"
        },
        {
          role: "user",
          content: `请把下面 arXiv 论文转换成简体中文读者卡片。字段必须包含 title_zh、summary_zh、key_points。key_points 是 3 条以内的中文字符串数组。不要生成适合人群、阅读建议或“适合 XX 人员阅读”之类内容，直接总结论文即可。\n\n标题：${paper.title}\n作者：${paper.authors.join(", ")}\n分类：${paper.category}\n摘要：${paper.summary}`
        }
      ]
    })
  });

  const raw = await response.text();
  if (!response.ok) {
    throw new Error(`Agnes returned ${response.status}: ${raw.slice(0, 240)}`);
  }

  const data = JSON.parse(raw);
  const content = data?.choices?.[0]?.message?.content;
  if (!content) throw new Error("Agnes returned an empty response.");
  const translation = parseTranslationContent(content);

  return {
    title_zh: normalizeText(translation.title_zh),
    summary_zh: normalizeText(translation.summary_zh),
    key_points: asArray(translation.key_points).map(normalizeText).filter(Boolean).slice(0, 3),
    reading_note: normalizeText(translation.reading_note)
  };
}

function chatText(value = "", limit = 2000) {
  return String(value)
    .replace(/\r/g, "")
    .replace(/\n{4,}/g, "\n\n\n")
    .trim()
    .slice(0, limit);
}

function getDeepReadApiConfig() {
  const apiKey = normalizeText(process.env.TEST_API_KEY || process.env.AGNES_API_KEY);
  if (!apiKey) {
    const error = new Error("TEST_API_KEY or AGNES_API_KEY is not configured on the server.");
    error.status = 400;
    throw error;
  }

  return {
    apiKey,
    apiUrl:
      normalizeText(process.env.TEST_API_URL || process.env.AGNES_API_URL) ||
      "https://apihub.agnes-ai.com/v1/chat/completions",
    model: normalizeText(process.env.TEST_API_MODEL || process.env.AGNES_MODEL) || "agnes-2.0-flash"
  };
}

function formatPaperContext(paper, htmlRecord) {
  const images = extractPaperImages(htmlRecord.html, htmlRecord.sourceUrl);
  const imageLines = images
    .slice(0, 24)
    .map((image, index) => `  ${index + 1}. ${image.caption || image.alt || "paper image"} ${image.url}`)
    .join("\n");

  return [
    `标题：${paper.title}`,
    `arXiv：${paper.id}`,
    `作者：${paper.authors.join(", ") || "arXiv"}`,
    `分类：${paper.category}`,
    `摘要：${paper.summary}`,
    `HTML 来源：${htmlRecord.sourceUrl}`,
    imageLines ? `图片：\n${imageLines}` : "图片：HTML 中未解析到论文图片。",
    `HTML（已移除 script/style，并限制长度）：\n${compactHtml(htmlRecord.html, maxPromptHtmlChars)}`
  ].join("\n");
}

async function deepReadCompletion(store, paperIds, messages) {
  const ids = asArray(paperIds)
    .map(normalizeText)
    .filter(Boolean)
    .filter((id, index, list) => list.indexOf(id) === index)
    .slice(0, maxDeepReadPapers);

  const papers = ids.map((id) => getPaper(store, id)).filter(Boolean);
  if (!papers.length) {
    const error = new Error("No readable favorite papers were provided.");
    error.status = 404;
    throw error;
  }

  const paperContexts = [];
  for (const paper of papers) {
    const htmlRecord = await readPaperHtml(paper, false);
    paperContexts.push(formatPaperContext(paper, htmlRecord));
  }

  const recentMessages = asArray(messages)
    .filter((message) => message && (message.role === "user" || message.role === "assistant"))
    .slice(-maxDeepReadMessages)
    .map((message) => ({
      role: message.role,
      content: chatText(message.content)
    }))
    .filter((message) => message.content);

  const config = getDeepReadApiConfig();
  const response = await fetch(config.apiUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${config.apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model: config.model,
      temperature: 0.2,
      max_tokens: 1400,
      messages: [
        {
          role: "system",
          content:
            "你是 PaperCard 的论文精读助手。请基于用户附加的 arXiv HTML、摘要和图片信息进行严谨讨论。不要执行或建议执行 HTML，不要编造未提供的实验细节。中文回答，必要时指出不确定之处。"
        },
        {
          role: "user",
          content: `以下是当前聊天纳入的论文 HTML 资料：\n\n${paperContexts
            .map((context, index) => `--- 论文 ${index + 1} ---\n${context}`)
            .join("\n\n")}`
        },
        ...recentMessages
      ]
    })
  });

  const raw = await response.text();
  if (!response.ok) {
    throw new Error(`test_api returned ${response.status}: ${raw.slice(0, 240)}`);
  }

  const data = JSON.parse(raw);
  const content = String(data?.choices?.[0]?.message?.content || "").trim();
  if (!content) throw new Error("test_api returned an empty response.");
  return content;
}

const app = express();
app.use(cors());
app.use(express.json({ limit: "1mb" }));

app.get("/api/health", async (_request, response) => {
  const store = await ensureStore();
  response.json({
    ok: true,
    lastFetchAt: store.lastFetchAt,
    papers: store.papers.length,
    hasAgnesKey: Boolean(process.env.AGNES_API_KEY)
  });
});

app.get("/api/preferences", async (_request, response) => {
  const store = await ensureStore();
  response.json(store.preferences);
});

app.put("/api/preferences", async (request, response) => {
  const store = await ensureStore();
  store.preferences = sanitizePreferences(request.body);
  store.lastFetchAt = null;
  await saveStore(store);
  await refreshIfNeeded(store, true);
  response.json({
    preferences: store.preferences,
    papers: store.papers.map((paper) => withUserState(store, paper)),
    favoriteFolders: store.favoriteFolders,
    lastFetchAt: store.lastFetchAt,
    lastError: store.lastError
  });
});

app.get("/api/papers", async (request, response) => {
  const store = await ensureStore();
  await refreshIfNeeded(store, request.query.refresh === "1");
  response.json({
    papers: store.papers.map((paper) => withUserState(store, paper)),
    favoriteFolders: store.favoriteFolders,
    lastFetchAt: store.lastFetchAt,
    lastQuery: store.lastQuery,
    lastError: store.lastError,
    preferences: store.preferences
  });
});

app.post("/api/actions", async (request, response) => {
  const { paperId, action, folderId } = request.body || {};
  const store = await ensureStore();
  const paper = getPaper(store, paperId);
  if (!paper) return response.status(404).json({ error: "Paper not found" });

  if (action === "favorite") {
    store.favorites[paper.id] = {
      paper,
      favoriteAt: new Date().toISOString(),
      folderId: getFavoriteFolderId(store, folderId)
    };
    delete store.skipped[paper.id];
  } else if (action === "skip") {
    store.skipped[paper.id] = {
      paperId: paper.id,
      skippedAt: new Date().toISOString()
    };
    delete store.favorites[paper.id];
  } else if (action === "clear") {
    delete store.favorites[paper.id];
    delete store.skipped[paper.id];
  } else {
    return response.status(400).json({ error: "Unknown action" });
  }

  await saveStore(store);
  response.json({
    paper: withUserState(store, paper),
    favorites: Object.keys(store.favorites).length,
    skipped: Object.keys(store.skipped).length
  });
});

app.get("/api/favorites", async (request, response) => {
  const store = await ensureStore();
  const folderId = normalizeText(request.query.folderId || "all");
  const favorites = Object.values(store.favorites)
    .filter((item) => folderId === "all" || (item.folderId || defaultFavoriteFolder.id) === folderId)
    .sort((a, b) => new Date(b.favoriteAt).getTime() - new Date(a.favoriteAt).getTime())
    .map((item) => withUserState(store, item.paper));
  response.json({ favorites, favoriteFolders: store.favoriteFolders });
});

app.get("/api/folders", async (_request, response) => {
  const store = await ensureStore();
  response.json({ favoriteFolders: store.favoriteFolders });
});

app.post("/api/folders", async (request, response) => {
  const store = await ensureStore();
  const name = normalizeText(request.body?.name).slice(0, 24);
  if (!name) return response.status(400).json({ error: "Folder name is required" });
  if (store.favoriteFolders.some((folder) => folder.name === name)) {
    return response.status(409).json({ error: "Folder already exists" });
  }

  const favoriteFolder = {
    id: randomUUID(),
    name,
    createdAt: new Date().toISOString()
  };
  store.favoriteFolders = normalizeFavoriteFolders([...store.favoriteFolders, favoriteFolder]);
  await saveStore(store);
  response.json({ favoriteFolder, favoriteFolders: store.favoriteFolders });
});

app.delete("/api/folders/:folderId", async (request, response) => {
  const store = await ensureStore();
  const folderId = normalizeText(request.params.folderId);
  if (folderId === defaultFavoriteFolder.id) {
    return response.status(400).json({ error: "Default folder cannot be deleted" });
  }

  store.favoriteFolders = normalizeFavoriteFolders(store.favoriteFolders.filter((folder) => folder.id !== folderId));
  for (const item of Object.values(store.favorites)) {
    if (item.folderId === folderId) item.folderId = defaultFavoriteFolder.id;
  }
  await saveStore(store);
  response.json({ favoriteFolders: store.favoriteFolders });
});

app.post("/api/translate", async (request, response) => {
  const { paperId, force } = request.body || {};
  const store = await ensureStore();
  const paper = getPaper(store, paperId);
  if (!paper) return response.status(404).json({ error: "Paper not found" });

  if (store.translations[paper.id] && !force) {
    return response.json(store.translations[paper.id]);
  }

  try {
    const translation = await translatePaper(paper);
    store.translations[paper.id] = {
      paperId: paper.id,
      translatedAt: new Date().toISOString(),
      translation
    };
    await saveStore(store);
    response.json(store.translations[paper.id]);
  } catch (error) {
    const status = Number(error?.status || 502);
    response.status(status).json({
      error: error instanceof Error ? error.message : String(error)
    });
  }
});

app.get("/api/papers/:readerId/html", async (request, response) => {
  const store = await ensureStore();
  const paper = getPaper(store, request.params.readerId);
  if (!paper) return response.status(404).json({ error: "Paper not found" });

  try {
    const payload = await paperHtmlPayload(paper, {
      includeHtml: request.query.includeHtml === "1",
      force: request.query.refresh === "1"
    });
    response.json(payload);
  } catch (error) {
    response.status(Number(error?.status || 502)).json({
      error: error instanceof Error ? error.message : String(error)
    });
  }
});

app.post("/api/deep-read/chat", async (request, response) => {
  const store = await ensureStore();
  const { paperIds, messages } = request.body || {};

  try {
    const content = await deepReadCompletion(store, paperIds, messages);
    response.json({
      message: {
        role: "assistant",
        content,
        createdAt: new Date().toISOString()
      }
    });
  } catch (error) {
    response.status(Number(error?.status || 502)).json({
      error: error instanceof Error ? error.message : String(error)
    });
  }
});

app.get("/api/papers/:readerId/pdf", async (request, response) => {
  const store = await ensureStore();
  const paper = getPaper(store, request.params.readerId);
  if (!paper) return response.status(404).send("Paper not found");

  const safeReaderId = readerIdFromArxivId(paper.readerId || paper.id);
  const fileName = `${safeReaderId}.pdf`;
  const cachePath = path.join(pdfCacheDir, fileName);
  const download = request.query.download === "1";

  try {
    if (!fs.existsSync(cachePath) || fs.statSync(cachePath).size === 0) {
      await cachePdf(paper, cachePath);
    }
    sendPdf(response, cachePath, fileName, download);
  } catch (error) {
    response.status(Number(error?.status || 502)).send(error instanceof Error ? error.message : String(error));
  }
});

app.get("/api/export/bibtex", async (request, response) => {
  const store = await ensureStore();
  const folderId = normalizeText(request.query.folderId || "all");
  const entries = Object.values(store.favorites)
    .filter((item) => folderId === "all" || (item.folderId || defaultFavoriteFolder.id) === folderId)
    .map(({ paper }) => {
    const key = `arxiv${paper.id.replace(/[^0-9A-Za-z]/g, "")}`;
    return `@article{${key},
  title={${paper.title}},
  author={${paper.authors.join(" and ")}},
  year={${new Date(paper.published).getFullYear() || ""}},
  eprint={${paper.id}},
  archivePrefix={arXiv},
  primaryClass={${paper.category}},
  url={${paper.absUrl}}
}`;
  });

  response.setHeader("Content-Type", "text/plain; charset=utf-8");
  response.setHeader("Content-Disposition", "attachment; filename=\"papercard-favorites.bib\"");
  response.send(entries.join("\n\n"));
});

const distDir = path.join(rootDir, "dist");
if (fs.existsSync(distDir)) {
  app.use(express.static(distDir));
  app.get(/.*/, (_request, response) => response.sendFile(path.join(distDir, "index.html")));
}

app.listen(port, () => {
  console.log(`PaperCard API listening on http://localhost:${port}`);
});
