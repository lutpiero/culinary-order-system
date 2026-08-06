/**
 * DSP Pratama Remote QR client
 *
 * The "https://remoteqr.dspratama.co.id" dashboard has no public JSON API.
 * It's a session-based (CodeIgniter) admin panel:
 *   1. POST /login (username, password) -> sets a `ci_session` cookie
 *   2. GET /trx-qr?fromDate=&toDate=&mid= -> server-rendered HTML page that
 *      embeds the transaction rows as HTML-entity-encoded JSON inside a
 *      hidden <input name="reportData" value='...'> field.
 *
 * This module logs in, fetches transactions for a date range, and parses
 * that embedded JSON. The session cookie is cached in-memory for the
 * lifetime of the warm lambda container to avoid re-logging in on every
 * invocation.
 */

let cachedCookie = null;
let cachedCookieAt = 0;
const COOKIE_TTL_MS = 8 * 60 * 1000; // Refresh well before the 10h server TTL, conservatively

function getEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function decodeHtmlEntities(str) {
  return str
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">");
}

function extractCookie(setCookieHeaders) {
  if (!setCookieHeaders) return null;
  const headers = Array.isArray(setCookieHeaders) ? setCookieHeaders : [setCookieHeaders];
  // Prefer the last non-"deleted" ci_session cookie in the response
  let found = null;
  for (const header of headers) {
    const match = header.match(/ci_session=([^;]+)/);
    if (match && match[1] && match[1] !== "deleted") {
      found = `ci_session=${match[1]}`;
    }
  }
  return found;
}

async function login({ force = false } = {}) {
  const now = Date.now();
  if (!force && cachedCookie && now - cachedCookieAt < COOKIE_TTL_MS) {
    return cachedCookie;
  }

  const baseUrl = getEnv("DS_APP_BASE_URL").replace(/\/+$/, "");
  const username = getEnv("DS_APP_USERNAME");
  const password = getEnv("DS_APP_PASSWORD");

  const body = new URLSearchParams({ username, password }).toString();

  const res = await fetch(`${baseUrl}/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
    redirect: "manual",
  });

  // node-fetch/undici expose multiple Set-Cookie headers via getSetCookie()
  const setCookie =
    typeof res.headers.getSetCookie === "function"
      ? res.headers.getSetCookie()
      : res.headers.get("set-cookie");

  const cookie = extractCookie(setCookie);
  if (!cookie) {
    throw new Error("DSP login failed: no session cookie returned. Check credentials.");
  }

  cachedCookie = cookie;
  cachedCookieAt = now;
  return cookie;
}

function extractReportData(html) {
  const match = html.match(/name="reportData"\s+value='([^']*)'/);
  if (!match) return [];
  const decoded = decodeHtmlEntities(match[1]);
  if (!decoded || decoded === "[]") return [];
  try {
    return JSON.parse(decoded);
  } catch (err) {
    console.error("Failed to parse DSP reportData JSON:", err);
    return [];
  }
}

function formatDate(date) {
  // DSP expects YYYY-MM-DD (WIB/local server date, matches Asia/Jakarta)
  return date.toISOString().slice(0, 10);
}

/**
 * Fetch transactions for a merchant within a date range.
 * @param {Object} opts
 * @param {Date} opts.fromDate
 * @param {Date} opts.toDate
 * @param {string} [opts.mid] - Merchant ID; defaults to DS_APP_MID
 * @param {boolean} [opts.retry] - internal flag to avoid infinite retry loop
 * @returns {Promise<Array<Object>>}
 */
async function fetchTransactions({ fromDate, toDate, mid, retry = true }) {
  const baseUrl = getEnv("DS_APP_BASE_URL").replace(/\/+$/, "");
  const merchantId = mid || getEnv("DS_APP_MID");

  const cookie = await login();

  const params = new URLSearchParams({
    fromDate: formatDate(fromDate),
    toDate: formatDate(toDate),
    mid: merchantId,
  });

  const res = await fetch(`${baseUrl}/trx-qr?${params.toString()}`, {
    method: "GET",
    headers: { Cookie: cookie },
  });

  if (res.status === 401 || res.status === 500) {
    if (retry) {
      // Session likely expired; force a fresh login and retry once.
      await login({ force: true });
      return fetchTransactions({ fromDate, toDate, mid, retry: false });
    }
    throw new Error(`DSP trx-qr request failed with status ${res.status}`);
  }

  if (!res.ok) {
    throw new Error(`DSP trx-qr request failed with status ${res.status}`);
  }

  const html = await res.text();

  // A login page is returned (title contains "Login Page") if the session
  // was rejected without a 401/500 status. Detect and retry once.
  if (retry && /Login Page/i.test(html)) {
    await login({ force: true });
    return fetchTransactions({ fromDate, toDate, mid, retry: false });
  }

  return extractReportData(html);
}

module.exports = {
  login,
  fetchTransactions,
};
