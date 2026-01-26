/**
 * ZenReader shared header + nav
 * - Single source of truth for nav items
 * - Excludes current page from nav (toggle EXCLUDE_CURRENT)
 * - Removes legacy header blocks to avoid duplication
 */
const EXCLUDE_CURRENT = true;

const NAV_ITEMS = [
  { label: "About me", href: "/ueber_mich.html" },
  { label: "HOME", href: "/index.html" },
  { label: "Readingdiary", href: "/analytics/" },
  { label: "Contact", href: "/kontaktformular.html" },
  { label: "Newsletter", href: "/newsletter.html" },
  { label: "Shop", href: "/merchandise.html" },
  { label: "FAQ", href: "/faq.html" },
  { label: "Login", href: "https://admin.zenreader.net/" },
  { label: "Youtube", href: "https://www.youtube.com/@zenreader2026", cls: "zr-youtube" },
  { label: "Tiktok", href: "https://www.tiktok.com/@zenreader26", cls: "zr-tiktok" },
  { label: "Instagram", href: "https://www.instagram.com/zenreader26/", cls: "zr-instagram" },
];

function normalizePath(p) {
  if (!p || p === "/") return "/index.html";
  return p;
}

function isInternal(href) {
  return href.startsWith("/");
}

function buildHeader(currentPath) {
  const header = document.createElement("header");
  header.className = "zr-topbar";
  header.innerHTML = `
    <a class="zr-logo" href="/index.html" aria-label="Zenreader home">
      <img src="/assets/images/allgemein/logo.jpeg" alt="Zenreader logo">
    </a>

    <form class="zr-search" action="/books/" method="get" role="search">
      <input type="text" name="q" placeholder="Bücher oder Autoren suchen…">
      <button type="submit" aria-label="Search">🔎</button>
    </form>

    <nav class="zr-nav" aria-label="Primary"></nav>
  `;

  const navEl = header.querySelector(".zr-nav");
  const curr = normalizePath(currentPath);

  const items = NAV_ITEMS.filter(item => {
    if (!EXCLUDE_CURRENT) return true;
    if (!isInternal(item.href)) return true;
    const itemPath = normalizePath(new URL(item.href, location.origin).pathname);
    return itemPath !== curr;
  });

  for (const item of items) {
    const a = document.createElement("a");
    a.className = `zr-btn ${item.cls ?? ""}`.trim();
    a.href = item.href;
    a.textContent = item.label;
    navEl.appendChild(a);
  }

  if (!EXCLUDE_CURRENT) {
    for (const a of navEl.querySelectorAll("a.zr-btn")) {
      const url = new URL(a.href, location.origin);
      const p = normalizePath(url.pathname);
      if (p === curr) {
        a.setAttribute("aria-current", "page");
        a.classList.add("zr-btn--current");
        a.removeAttribute("href");
      }
    }
  }

  return header;
}

function ensureStyles() {
  if (document.getElementById("zr-shared-header-styles")) return;
  const style = document.createElement("style");
  style.id = "zr-shared-header-styles";
  style.textContent = `
    .zr-topbar{
      display:flex;
      align-items:center;
      gap:12px;
      padding:12px 16px;
      flex-wrap:wrap;
    }
    .zr-logo img{ height:72px; width:auto; display:block; }
    .zr-search{ display:flex; gap:8px; align-items:center; flex:1 1 260px; }
    .zr-search input{
      width:100%;
      max-width:520px;
      padding:8px 10px;
      border-radius:10px;
      border:1px solid #cfcfcf;
      background:#fff;
    }
    .zr-search button{
      padding:8px 10px;
      border-radius:10px;
      border:1px solid #cfcfcf;
      background:#fff;
      cursor:pointer;
    }
    .zr-nav{
      display:flex;
      flex-wrap:wrap;
      gap:8px;
      justify-content:flex-end;
      margin-left:auto;
    }
    .zr-btn{
      background:#d300bd;
      color:#fff;
      padding:6px 10px;
      border:2px solid #fff;
      border-radius:8px;
      text-decoration:none;
      font-size:14px;
      line-height:1.2;
      display:inline-block;
      white-space:nowrap;
    }
    .zr-btn:hover{ filter:brightness(0.98); }
    .zr-btn--current{ opacity:0.75; cursor:default; }
    .zr-youtube{ background:#e60000; }
    .zr-tiktok{ background:#111; }
    .zr-instagram{ background:#d300bd; }
  `;
  document.head.appendChild(style);
}

function removeLegacyHeaders() {
  const legacy = document.querySelector("header.zr-topbar, body > .row.disp");
  if (legacy) legacy.remove();
}

function init() {
  ensureStyles();
  removeLegacyHeaders();
  const header = buildHeader(location.pathname);
  document.body.prepend(header);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
