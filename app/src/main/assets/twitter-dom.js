(() => {
    if (window.__TW_DOM_INSTALLED__) return;
    window.__TW_DOM_INSTALLED__ = true;

    const BIRD_PATH = "M23.643 4.937c-.835.37-1.732.62-2.675.733.962-.576 1.7-1.49 2.048-2.578-.9.534-1.897.922-2.958 1.13-.85-.904-2.06-1.47-3.4-1.47-2.572 0-4.658 2.086-4.658 4.66 0 .364.042.718.12 1.06-3.873-.195-7.304-2.05-9.602-4.868-.4.69-.63 1.49-.63 2.342 0 1.616.823 3.043 2.072 3.878-.764-.025-1.482-.234-2.11-.583v.06c0 2.257 1.605 4.14 3.737 4.568-.392.106-.803.162-1.227.162-.3 0-.593-.028-.877-.082.593 1.85 2.313 3.198 4.352 3.234-1.595 1.25-3.604 1.995-5.786 1.995-.376 0-.747-.022-1.112-.065 2.062 1.323 4.51 2.093 7.14 2.093 8.57 0 13.255-7.098 13.255-13.254 0-.2-.005-.402-.014-.602.91-.658 1.7-1.477 2.323-2.41z";
    const DOWNLOAD_PATH = "M12 16l-5-5 1.41-1.41L11 12.17V4h2v8.17l2.59-2.58L17 11l-5 5zm-7 2h14v2H5v-2z";

    const origTextMap = new WeakMap();
    const TERM_MAP = new Map([["Post", "Tweet"], ["Posts", "Tweets"], ["Post your reply", "Tweet your reply"], ["Repost", "Retweet"], ["Reposted", "Retweeted"], ["Reposts", "Retweets"], ["Quote", "Quote Tweet"], ["Quotes", "Quote Tweets"], ["What is happening?!", "What's happening?"]]);
    const TERM_SELECTOR = "button span,[role='button'] span,[role='tab'] span,h1 span,h2 span,nav a span,[data-testid='tweetButtonInline'] span,[data-testid='tweetButton'] span,[data-placeholder]";
    const SUGGESTED_FEEDS = new Set(["nfl", "nba", "nhl", "mlb", "baseball", "college football", "cfb", "soccer", "trending", "news", "sports", "entertainment", "gaming", "technology", "politics", "business", "finance", "music", "cricket", "golf", "tennis", "f1", "nascar", "ufc", "boxing", "wwe", "olympics"]);
    const EXTERNAL_SKIP_RE = /^https?:\/\/([^/]+\.)?(x|twitter)\.com\//i;

    let switchedOnThisHomeVisit = false, userPickedTabOnVisit = false, homeVisitEnteredAt = 0;
    let lastDefaultHomeFeed = null, lastKnownPath = "", lastDialogOpen = null;
    let lastReportedFeedsSig = "", lastDatasetSig = "", lastReportedThemeHex = "";
    let fullScanPending = true, themeDirty = true, tabsDirty = true, htmlObserved = false, bodyObserved = false;
    const dirtyRoots = new Set();

    const getCfg = () => window.__TWITTER_CONFIG__ || {};

    function ensureStyleTag() {
        const cssText = window.__TWITTER_CLEAN_CSS__, mount = document.head || document.documentElement;
        if (!cssText || !mount || document.getElementById("tw-mod-style")) return;
        const styleEl = document.createElement("style");
        styleEl.id = "tw-mod-style";
        styleEl.dataset.twOwn = "1";
        styleEl.textContent = cssText;
        mount.appendChild(styleEl);
    }

    function rgbToHex(rgb) {
        const m = rgb && rgb.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/i);
        if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) return null;
        const h = (n) => Math.min(255, Math.max(0, parseInt(n, 10))).toString(16).padStart(2, "0");
        return `#${h(m[1])}${h(m[2])}${h(m[3])}`.toUpperCase();
    }

    function syncDatasetAndTheme(cfg, isUnfolded) {
        if (typeof isUnfolded === "boolean") window.__TWITTER_IS_UNFOLDED__ = isUnfolded;
        const root = document.documentElement;
        if (!root) return;
        if (!htmlObserved) { htmlObserved = true; themeObserver.observe(root, { attributes: true, attributeFilter: ["style", "class"] }); }
        if (document.body && !bodyObserved) { bodyObserved = true; themeObserver.observe(document.body, { attributes: true, attributeFilter: ["style", "class"] }); }
        const forceBottom = window.__TWITTER_IS_UNFOLDED__ === false && !document.querySelector('[data-testid="BottomBar"]') && Boolean(document.querySelector('header[role="banner"] nav[aria-label="Primary"]'));
        const sig = [cfg.classicTwitterBranding !== false, cfg.removeGrok !== false, cfg.removePremiumUpsells !== false, Boolean(cfg.hideAddFeedButton), cfg.hideViewCounts !== false, Boolean(cfg.hideBookmarkCounts), cfg.cleanBottomNav !== false, cfg.verifiedBadgeMode || "SHOW_ALL", forceBottom].join("|");

        if (sig !== lastDatasetSig) {
            lastDatasetSig = sig;
            Object.assign(root.dataset, {
                twClassicBrand: String(cfg.classicTwitterBranding !== false), twRemoveGrok: String(cfg.removeGrok !== false),
                twRemoveUpsells: String(cfg.removePremiumUpsells !== false), twHideAddFeed: String(Boolean(cfg.hideAddFeedButton)),
                twHideViews: String(cfg.hideViewCounts !== false), twHideBookmarks: String(Boolean(cfg.hideBookmarkCounts)),
                twCleanNav: String(cfg.cleanBottomNav !== false), twBadgeMode: String(cfg.verifiedBadgeMode || "SHOW_ALL"),
                twForceBottomNav: String(forceBottom)
            });
            themeDirty = true;
        }
        if (themeDirty && document.body) {
            const hex = rgbToHex(getComputedStyle(document.body).backgroundColor);
            if (hex) {
                themeDirty = false;
                root.style.setProperty("--tw-bottom-nav-bg", hex);
                if (hex !== lastReportedThemeHex) { lastReportedThemeHex = hex; window.TwitterBridge?.onThemeColorChanged?.(hex); }
            }
        }
    }

    window.__applyTwitterConfig = function (cfg, isUnfolded) {
        const nextDefault = String(cfg?.defaultHomeFeed || "For you").trim().toLowerCase();
        if (lastDefaultHomeFeed !== null && nextDefault !== lastDefaultHomeFeed) switchedOnThisHomeVisit = userPickedTabOnVisit = false;
        lastDefaultHomeFeed = nextDefault;
        window.__TWITTER_CONFIG__ = cfg;
        fullScanPending = themeDirty = tabsDirty = true;
        syncDatasetAndTheme(cfg, isUnfolded);
        runDomPass();
    };

    const isXLogoPath = (d) => Boolean(d) && (d.startsWith("M18.244 2.25h3.308") || d.startsWith("M21.742 21.75l-7.563") || d.startsWith("M14.234 10.162"));

    function queryWithinRoots(roots, fullScan, selector) {
        if (fullScan) return document.querySelectorAll(selector);
        const out = [];
        for (const r of roots) {
            if (!r.isConnected) continue;
            if (r.matches?.(selector)) out.push(r);
            const nested = r.querySelectorAll?.(selector);
            if (nested?.length) out.push(...nested);
        }
        return out;
    }

    function updateBrandLogos(useClassic, roots, fullScan) {
        for (const svg of queryWithinRoots(roots, fullScan, useClassic ? "svg" : "svg[data-tw-bird='true']")) {
            const path = svg.querySelector("path");
            if (!path) continue;
            const currentD = path.getAttribute("d") || "";
            if (useClassic) {
                if (path.dataset.twSwapped === "true" && currentD === BIRD_PATH) continue;
                const inNavBar = Boolean(svg.closest('[data-testid="BottomBar"], nav[aria-label="Primary"]'));
                const isBrandSlot = !inNavBar && Boolean(svg.closest('[aria-label="X"], [data-testid="TopNavBar"] a[href="/home"], h1 a[href="/home"]'));
                if (!isXLogoPath(currentD) && !(isBrandSlot && svg.getAttribute("viewBox") === "0 0 24 24")) continue;
                if (!path.dataset.twOrigD && currentD !== BIRD_PATH) {
                    path.dataset.twOrigD = currentD;
                    path.dataset.twOrigFill = path.getAttribute("fill") || "";
                    svg.dataset.twOrigViewbox = svg.getAttribute("viewBox") || "";
                }
                if (path.dataset.twOrigD) {
                    path.setAttribute("d", BIRD_PATH);
                    path.setAttribute("fill", "#1DA1F2");
                    svg.setAttribute("viewBox", "0 0 24 24");
                    svg.dataset.twBird = path.dataset.twSwapped = "true";
                }
            } else if (path.dataset.twSwapped === "true" && path.dataset.twOrigD) {
                path.setAttribute("d", path.dataset.twOrigD);
                path.dataset.twOrigFill ? path.setAttribute("fill", path.dataset.twOrigFill) : path.removeAttribute("fill");
                if (svg.dataset.twOrigViewbox) svg.setAttribute("viewBox", svg.dataset.twOrigViewbox);
                delete svg.dataset.twBird;
                delete path.dataset.twSwapped;
            }
        }
        const t = document.title;
        if (useClassic && (t === "X" || t.endsWith(" / X"))) document.title = t === "X" ? "Twitter" : t.replace(/ \/ X$/, " / Twitter");
        else if (!useClassic && (t === "Twitter" || t.endsWith(" / Twitter"))) document.title = t === "Twitter" ? "X" : t.replace(/ \/ Twitter$/, " / X");
    }

    function rewriteUiTerminology(useClassic, roots, fullScan) {
        for (const el of queryWithinRoots(roots, fullScan, TERM_SELECTOR)) {
            if (el.closest('[data-testid="tweetText"], [data-testid="UserDescription"], [data-testid="UserName"], [data-testid="User-Name"], [contenteditable="true"]')) continue;
            if (el.hasAttribute("data-placeholder")) {
                const ph = el.getAttribute("data-placeholder");
                if (useClassic && TERM_MAP.has(ph)) {
                    if (!el.dataset.twOrigPh) el.dataset.twOrigPh = ph;
                    el.setAttribute("data-placeholder", TERM_MAP.get(ph));
                } else if (!useClassic && el.dataset.twOrigPh) el.setAttribute("data-placeholder", el.dataset.twOrigPh);
            }
            if (el.childNodes.length === 1 && el.firstChild.nodeType === Node.TEXT_NODE) {
                const txt = el.firstChild.nodeValue, trimmed = txt ? txt.trim() : "";
                if (useClassic && TERM_MAP.has(trimmed)) {
                    const nextVal = txt.replace(trimmed, TERM_MAP.get(trimmed));
                    origTextMap.set(el, txt);
                    el.dataset.twReplacedTo = el.firstChild.nodeValue = nextVal;
                } else if (!useClassic && el.dataset.twReplacedTo && txt === el.dataset.twReplacedTo && origTextMap.has(el)) {
                    el.firstChild.nodeValue = origTextMap.get(el);
                    delete el.dataset.twReplacedTo;
                    origTextMap.delete(el);
                } else if (el.dataset.twReplacedTo && txt !== el.dataset.twReplacedTo) {
                    delete el.dataset.twReplacedTo;
                    origTextMap.delete(el);
                }
            }
        }
    }

    function bindLongPress(el, delayMs, onTrigger, handleContextMenu = false) {
        let timer = null, triggered = false;
        const clear = () => { if (timer) { clearTimeout(timer); timer = null; } };
        el.addEventListener("touchstart", () => { triggered = false; clear(); timer = setTimeout(() => { triggered = true; onTrigger(); }, delayMs); }, { passive: true });
        for (const ev of ["touchend", "touchmove", "touchcancel"]) el.addEventListener(ev, clear, { passive: true });
        el.addEventListener("click", (e) => { if (triggered) { e.preventDefault(); e.stopPropagation(); triggered = false; } }, true);
        if (handleContextMenu) el.addEventListener("contextmenu", (e) => { e.preventDefault(); e.stopPropagation(); clear(); if (!triggered) { triggered = true; onTrigger(); } });
    }

    function bindHeaderLongPress() {
        for (const el of document.querySelectorAll('[data-testid="TopNavBar"] a[href="/home"], [data-testid="TopNavBar"] svg[data-tw-bird="true"], header a[href="/home"], [data-testid="BottomBar"] a[href="/home"]')) {
            const host = el.closest("a, div[role='button']") || el;
            if (!host.dataset.twPressBound) { host.dataset.twPressBound = "true"; bindLongPress(host, 500, () => window.TwitterBridge?.openSettings()); }
        }
    }

    function syncDrawerItemStyle(item, link, anchor) {
        const sampleSpan = link.querySelector("span"), sampleSvg = link.querySelector("svg");
        const itemSpan = item.querySelector("span"), itemSvg = item.querySelector("svg");
        if (sampleSpan && itemSpan) {
            const cs = getComputedStyle(sampleSpan);
            for (const p of ["color", "fontFamily", "fontSize", "fontWeight", "lineHeight", "letterSpacing"]) itemSpan.style[p] = cs[p];
        }
        const rowRect = anchor.getBoundingClientRect(), svgRect = sampleSvg?.getBoundingClientRect(), spanRect = sampleSpan?.getBoundingClientRect();
        if (rowRect.height > 0 && svgRect?.width > 0 && spanRect?.width > 0) {
            item.style.padding = `0 ${Math.max(12, Math.round(parseFloat(getComputedStyle(link).paddingRight) || 16))}px 0 ${Math.max(0, Math.round(svgRect.left - rowRect.left))}px`;
            item.style.minHeight = `${Math.round(rowRect.height)}px`;
            item.style.gap = `${Math.max(8, Math.round(spanRect.left - svgRect.right))}px`;
            if (itemSvg) itemSvg.style.width = itemSvg.style.height = `${Math.round(svgRect.width)}px`;
            item.dataset.twMetricsSynced = "true";
        }
    }

    function injectDrawerModSettingsEntry() {
        for (const link of document.querySelectorAll('a[href="/settings"], a[href="/settings/account"]')) {
            if (link.closest('[data-testid="primaryColumn"]')) continue;
            const navContainer = link.closest('nav[aria-label="Primary"], [role="menu"], [data-testid="Drawer"], [role="dialog"]');
            if (!navContainer || (navContainer.tagName === "NAV" && navContainer.closest('html[data-tw-force-bottom-nav="true"] header[role="banner"]'))) continue;
            const anchor = link.parentElement && link.parentElement !== navContainer ? link.parentElement : link;
            const existing = navContainer.querySelector('[data-tw-mod-settings-item="true"]');
            if (existing) { if (!existing.dataset.twMetricsSynced) syncDrawerItemStyle(existing, link, anchor); continue; }
            const item = document.createElement("button");
            item.type = "button";
            item.className = "tw-mod-drawer-item";
            item.dataset.twOwn = "1";
            item.setAttribute("data-tw-mod-settings-item", "true");
            item.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${BIRD_PATH}"></path></svg><span>Twitter Mod Settings</span>`;
            item.addEventListener("click", (e) => { e.preventDefault(); e.stopPropagation(); window.TwitterBridge?.openSettings(); });
            anchor.insertAdjacentElement("beforebegin", item);
            syncDrawerItemStyle(item, link, anchor);
        }
    }

    function classifyHomeFeed(tab, key) {
        if (key === "for you" || key === "following") return "CORE";
        const href = (tab.getAttribute("href") || tab.querySelector("a")?.getAttribute("href") || "").toLowerCase();
        const cached = window.__TW_CACHE__?.getFeedKind?.(key);
        if (cached === "list" || href.includes("/lists/")) return "LIST";
        return (cached === "suggested" || SUGGESTED_FEEDS.has(key)) ? "SUGGESTED" : "LIST";
    }

    function markAddFeedButton(tablist) {
        for (const el of tablist.querySelectorAll('a, button, [role="button"]')) {
            if (!el.closest('[role="tab"]') && !el.querySelector('[role="tab"]')) {
                const target = el.closest('[role="tablist"] > *') || el;
                if (!target.hasAttribute("data-tw-add-feed-btn")) { target.setAttribute("data-tw-add-feed-btn", "true"); target.style.order = "9999"; }
            }
        }
        const sib = tablist.nextElementSibling;
        if (sib && !sib.hasAttribute("data-tw-add-feed-btn") && !sib.querySelector('[role="tab"]') && sib.querySelector("svg") && sib.getBoundingClientRect().width <= 84) {
            sib.setAttribute("data-tw-add-feed-btn", "true");
        }
    }

    function handleHomeTabs(cfg) {
        if (!location.pathname.startsWith("/home")) { switchedOnThisHomeVisit = userPickedTabOnVisit = false; return; }
        if (lastKnownPath !== location.pathname) { switchedOnThisHomeVisit = userPickedTabOnVisit = false; homeVisitEnteredAt = Date.now(); tabsDirty = true; }
        if (!tabsDirty && (switchedOnThisHomeVisit || userPickedTabOnVisit || Date.now() - homeVisitEnteredAt < 3000)) return;
        const tablist = document.querySelector('[data-testid="primaryColumn"] [role="tablist"]') || document.querySelector('[role="tablist"]');
        const tabs = tablist ? tablist.querySelectorAll('[role="tab"]') : [];
        if (!tablist || tabs.length === 0) return;

        if (!tablist.dataset.twTabTapBound) {
            tablist.dataset.twTabTapBound = "1";
            tablist.addEventListener("pointerdown", (e) => { if (e.isTrusted && e.target?.closest?.('[role="tab"]')) userPickedTabOnVisit = true; }, { passive: true });
        }
        const hidden = new Set((cfg.hiddenHomeFeeds || []).map((s) => String(s).trim().toLowerCase()));
        const pinned = new Set((cfg.pinnedHomeFeeds || []).map((s) => String(s).trim().toLowerCase()));
        const order = new Map((cfg.homeFeedOrder || []).map((s, i) => [String(s).trim().toLowerCase(), i]));
        const entries = [];
        for (let i = 0; i < tabs.length; i++) {
            const tab = tabs[i], name = (tab.textContent || "").replace(/\s+/g, " ").trim();
            if (!name) continue;
            const key = name.toLowerCase(), kind = classifyHomeFeed(tab, key);
            const target = tab.parentElement?.getAttribute("role") === "presentation" ? tab.parentElement : tab;
            entries.push({ tab, target, name, key, kind, domIdx: i, visible: !hidden.has(key) && !(kind === "SUGGESTED" && cfg.stripSuggestedHomeFeeds !== false && !pinned.has(key)) });
        }
        if (entries.length === 0) return;
        if (entries.every((e) => !e.visible)) entries[0].visible = true;

        const sig = JSON.stringify(entries.map((e) => ({ name: e.name, kind: e.kind })));
        if (sig !== lastReportedFeedsSig) { lastReportedFeedsSig = sig; window.TwitterBridge?.reportHomeFeeds?.(sig); }

        const sorted = [...entries].sort((a, b) => (order.get(a.key) ?? 1000 + a.domIdx) - (order.get(b.key) ?? 1000 + b.domIdx));
        sorted.forEach((e, idx) => {
            const ord = String(idx);
            if (e.target.style.order !== ord) e.target.style.order = ord;
            if (e.visible) e.target.removeAttribute("data-tw-feed-hidden");
            else if (e.target.getAttribute("data-tw-feed-hidden") !== "true") e.target.setAttribute("data-tw-feed-hidden", "true");
        });
        markAddFeedButton(tablist);
        tabsDirty = false;

        const visible = sorted.filter((e) => e.visible), activeVisible = visible.some((e) => e.tab.getAttribute("aria-selected") === "true");
        if (visible.length > 0 && !userPickedTabOnVisit && (!switchedOnThisHomeVisit || !activeVisible)) {
            const wantKey = String(cfg.defaultHomeFeed || "For you").trim().toLowerCase();
            const exact = visible.find((e) => e.key === wantKey), pick = exact || (!activeVisible ? visible[0] : null);
            if (pick && pick.tab.getAttribute("aria-selected") !== "true") pick.tab.click();
            if (exact || wantKey === "for you" || wantKey === "following" || (Date.now() - homeVisitEnteredAt >= 3000)) switchedOnThisHomeVisit = true;
        }
    }

    function resolveVideoTweetId(article, hasVideo) {
        const quoteStatus = hasVideo.closest('[role="link"]')?.querySelector('a[href*="/status/"]')?.getAttribute("href")?.match(/\/status\/([0-9]+)/);
        if (quoteStatus) return quoteStatus[1];
        const links = article.querySelectorAll('a[href*="/status/"]');
        for (const l of links) { const m = l.getAttribute("href")?.match(/\/status\/([0-9]+)$/); if (m) return m[1]; }
        return links[0]?.getAttribute("href")?.match(/\/status\/([0-9]+)/)?.[1] || location.pathname.match(/\/status\/([0-9]+)/)?.[1] || null;
    }

    function collectCandidateTweets(roots, fullScan) {
        if (fullScan) return document.querySelectorAll('article[data-testid="tweet"]');
        const set = new Set();
        for (const r of roots) {
            if (!r.isConnected) continue;
            const host = r.closest?.('article[data-testid="tweet"]');
            if (host) { set.add(host); continue; }
            if (r.matches?.('article[data-testid="tweet"]')) { set.add(r); continue; }
            for (const a of (r.querySelectorAll?.('article[data-testid="tweet"]') || [])) set.add(a);
        }
        return set;
    }

    function createIconBtn(cls, label, onClick) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = cls;
        btn.dataset.twOwn = "1";
        btn.setAttribute("aria-label", label);
        btn.innerHTML = `<svg viewBox="0 0 24 24"><path d="${DOWNLOAD_PATH}"></path></svg>`;
        btn.addEventListener("click", (e) => { e.preventDefault(); e.stopPropagation(); onClick(); });
        return btn;
    }

    function enhanceTweets(cfg, roots, fullScan) {
        for (const article of collectCandidateTweets(roots, fullScan)) {
            if (cfg.verifiedBadgeMode === "HIDE_PAID_BLUE") {
                for (const badge of article.querySelectorAll('[data-testid="icon-verified"]:not([data-tw-badge-checked])')) {
                    const nameBox = badge.closest('[data-testid="User-Name"]') || article.querySelector('[data-testid="User-Name"]');
                    const handle = nameBox?.querySelector('a[href^="/"]')?.getAttribute("href")?.split("/")[1];
                    const paid = handle ? window.__TW_CACHE__?.isPaidBlueOnly(handle) : null;
                    if (paid !== null && paid !== undefined) { badge.dataset.twBadgeChecked = "true"; if (paid) badge.setAttribute("data-tw-paid-blue", "true"); }
                }
            }
            const hasVideo = article.querySelector('video, [data-testid="videoPlayer"], [data-testid="gifPlayer"]'), existingBtn = article.querySelector(".tw-mod-dl-btn");
            if (cfg.enableVideoDownloader && hasVideo && !existingBtn) {
                const tweetId = resolveVideoTweetId(article, hasVideo), actionGroup = article.querySelector('[role="group"]');
                if (tweetId && actionGroup) {
                    actionGroup.appendChild(createIconBtn("tw-mod-dl-btn", "Download video", () => {
                        const cached = window.__TW_CACHE__?.getVideoUrl(tweetId), videoSrc = article.querySelector("video")?.getAttribute("src");
                        const url = cached || (videoSrc && videoSrc.startsWith("https://") && !videoSrc.startsWith("blob:") ? videoSrc : null);
                        if (url) window.TwitterBridge?.downloadMedia(url, `twitter_${tweetId}.mp4`);
                        else window.TwitterBridge?.showToast("Video stream not cached yet—tap play once first");
                    }));
                }
            } else if (!cfg.enableVideoDownloader && existingBtn) existingBtn.remove();

            for (const a of article.querySelectorAll('a[href^="http"]')) {
                if (cfg.unwrapTcoLinks && a.href.startsWith("https://t.co/") && !a.dataset.twUnwrapped) {
                    const expanded = window.__TW_CACHE__?.getExpandedUrl(a.href);
                    if (expanded) { a.href = expanded; a.dataset.twUnwrapped = "true"; }
                }
                if (!a.dataset.twLinkBound && !a.href.startsWith("https://t.co/") && !EXTERNAL_SKIP_RE.test(a.href)) {
                    a.dataset.twLinkBound = "true";
                    bindLongPress(a, 520, () => window.TwitterBridge?.showLinkActions(a.href), true);
                }
            }
        }
    }

    const toOrigImageUrl = (src) => (!src || src.includes("name=orig")) ? src : (src.includes("name=") ? src.replace(/name=[^&]+/, "name=orig") : src + (src.includes("?") ? "&name=orig" : "?name=orig"));

    function upgradeLightboxImages(cfg) {
        const host = document.querySelector('[data-testid="swipeToDismiss"], [aria-labelledby="modal-header"]');
        if (!host) return;
        const imgs = host.querySelectorAll('img[src*="pbs.twimg.com/media"]');
        if (cfg.forceOrigQualityImages) {
            for (const img of imgs) {
                if (!img.dataset.twOrigUpgraded && !img.src.includes("name=orig")) {
                    const fallbackSrc = img.src;
                    img.dataset.twOrigUpgraded = "1";
                    img.addEventListener("error", () => { if (img.src !== fallbackSrc) img.src = fallbackSrc; }, { once: true });
                    img.src = toOrigImageUrl(fallbackSrc);
                }
            }
        }
        const existingDl = host.querySelector(".tw-mod-img-dl-btn");
        if (cfg.enableVideoDownloader && imgs.length > 0 && !existingDl) {
            host.appendChild(createIconBtn("tw-mod-img-dl-btn", "Save original image", () => {
                let bestImg = imgs[0], bestScore = -1;
                for (const c of host.querySelectorAll('img[src*="pbs.twimg.com/media"]')) {
                    const r = c.getBoundingClientRect(), vis = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                    if (vis > bestScore) { bestScore = vis; bestImg = c; }
                }
                const origUrl = toOrigImageUrl(bestImg?.src || "");
                if (origUrl) window.TwitterBridge?.downloadMedia(origUrl, `twitter_img_${origUrl.match(/\/media\/([A-Za-z0-9_-]+)/)?.[1] || Date.now()}`);
            }));
        } else if (!cfg.enableVideoDownloader && existingDl) existingDl.remove();
    }

    function enforceVideoAutoplayPause(cfg, roots, fullScan) {
        if (!cfg.disableVideoAutoplay) return;
        for (const v of queryWithinRoots(roots, fullScan, "video")) {
            if (!v.dataset.twAutoplayGuarded) {
                v.dataset.twAutoplayGuarded = "true";
                v.autoplay = false;
                const player = v.closest('[data-testid="videoPlayer"], [data-testid="gifPlayer"]') || v;
                player.addEventListener("pointerdown", () => { v.dataset.twUserPlayed = "true"; }, { passive: true });
                v.addEventListener("play", () => { if (!v.dataset.twUserPlayed) v.pause(); });
            }
            if (!v.dataset.twUserPlayed && !v.paused) v.pause();
        }
    }

    function syncPullRefreshState() {
        const dialogOpen = Boolean(document.querySelector('[role="dialog"], [data-testid="swipeToDismiss"]'));
        if (dialogOpen !== lastDialogOpen) { lastDialogOpen = dialogOpen; window.TwitterBridge?.setPullToRefreshModalOpen(dialogOpen); }
        return dialogOpen;
    }

    function runDomPass() {
        const cfg = getCfg(), fullScan = fullScanPending || lastKnownPath !== location.pathname;
        const roots = fullScan ? null : Array.from(dirtyRoots);
        fullScanPending = false;
        dirtyRoots.clear();

        ensureStyleTag();
        syncDatasetAndTheme(cfg);
        updateBrandLogos(cfg.classicTwitterBranding !== false, roots, fullScan);
        rewriteUiTerminology(cfg.classicTwitterBranding !== false, roots, fullScan);
        bindHeaderLongPress();
        injectDrawerModSettingsEntry();
        handleHomeTabs(cfg);
        enhanceTweets(cfg, roots, fullScan);
        if (syncPullRefreshState()) upgradeLightboxImages(cfg);
        enforceVideoAutoplayPause(cfg, roots, fullScan);
        lastKnownPath = location.pathname;
    }

    let rafPending = false;
    function schedulePass() {
        if (rafPending) return;
        rafPending = true;
        requestAnimationFrame(() => { rafPending = false; runDomPass(); });
    }

    const themeObserver = new MutationObserver(() => { themeDirty = true; schedulePass(); });

    const observer = new MutationObserver((records) => {
        let relevant = false;
        for (const rec of records) {
            if (rec.removedNodes.length > 0) {
                if (lastDialogOpen) relevant = true;
                if (rec.target?.closest?.('[role="tablist"]')) tabsDirty = relevant = true;
            }
            for (const n of rec.addedNodes) {
                const el = n.nodeType === Node.ELEMENT_NODE ? n : (n.nodeType === Node.TEXT_NODE ? rec.target : null);
                if (!el || el.nodeType !== Node.ELEMENT_NODE || el.dataset?.twOwn === "1") continue;
                relevant = true;
                if (el.getAttribute?.("role") === "tab" || el.getAttribute?.("role") === "tablist" || el.closest?.('[role="tablist"]') || el.querySelector?.('[role="tablist"]')) tabsDirty = true;
                if (!fullScanPending) {
                    dirtyRoots.add(el);
                    if (dirtyRoots.size > 45) { fullScanPending = true; dirtyRoots.clear(); }
                }
            }
        }
        if (relevant) schedulePass();
    });
    observer.observe(document, { childList: true, subtree: true });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", () => { fullScanPending = themeDirty = true; runDomPass(); });
    } else runDomPass();
})();
