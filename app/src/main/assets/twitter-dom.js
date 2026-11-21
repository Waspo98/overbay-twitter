(() => {
    if (window.__TW_DOM_INSTALLED__) return;
    window.__TW_DOM_INSTALLED__ = true;

    const BIRD_PATH = "M23.643 4.937c-.835.37-1.732.62-2.675.733.962-.576 1.7-1.49 2.048-2.578-.9.534-1.897.922-2.958 1.13-.85-.904-2.06-1.47-3.4-1.47-2.572 0-4.658 2.086-4.658 4.66 0 .364.042.718.12 1.06-3.873-.195-7.304-2.05-9.602-4.868-.4.69-.63 1.49-.63 2.342 0 1.616.823 3.043 2.072 3.878-.764-.025-1.482-.234-2.11-.583v.06c0 2.257 1.605 4.14 3.737 4.568-.392.106-.803.162-1.227.162-.3 0-.593-.028-.877-.082.593 1.85 2.313 3.198 4.352 3.234-1.595 1.25-3.604 1.995-5.786 1.995-.376 0-.747-.022-1.112-.065 2.062 1.323 4.51 2.093 7.14 2.093 8.57 0 13.255-7.098 13.255-13.254 0-.2-.005-.402-.014-.602.91-.658 1.7-1.477 2.323-2.41z";
    const BRAND_SLOT_SELECTOR = '[aria-label="X"] svg, [data-testid="TopNavBar"] a[href="/home"] svg, h1 a[href="/home"] svg';
    const TERM_SELECTOR = "button span,[role='button'] span,[role='menuitem'] span,[role='tab'] span,h1 span,h2 span,nav a span,[data-testid='tweetButtonInline'] span,[data-testid='tweetButton'] span,[data-placeholder]";
    const TERM_SKIP_ANCESTORS = '[data-testid="tweetText"], [data-testid="UserDescription"], [data-testid="UserName"], [data-testid="User-Name"], [contenteditable="true"]';
    const SHOW_POSTS_RE = /^Show\s+([\d,]+)\s+(post|posts)$/i;

    const origTextMap = new WeakMap();
    const TERM_MAP = new Map([
        ["Post", "Tweet"], ["Posts", "Tweets"], ["Post your reply", "Tweet your reply"],
        ["Repost", "Retweet"], ["Reposted", "Retweeted"], ["Reposts", "Retweets"],
        ["Quote", "Quote Tweet"], ["Quotes", "Quote Tweets"],
        ["What is happening?!", "What's happening?"],
        ["Embed post", "Embed Tweet"], ["Delete post", "Delete Tweet"],
        ["Report post", "Report Tweet"], ["Copy link to post", "Copy link to Tweet"],
        ["View post engagements", "View Tweet engagements"], ["View post activity", "View Tweet activity"]
    ]);

    let lastKnownPath = "", lastDialogOpen = null, lastDatasetSig = "", lastReportedThemeHex = "";
    let fullScanPending = true, themeDirty = true, tabsDirty = true, headerDirty = true, dialogDirty = true;
    let htmlObserved = false, bodyObserved = false;
    const dirtyRoots = new Set();

    const getCfg = () => window.__TWITTER_CONFIG__ || {};
    const queryWithinRoots = (roots, fullScan, selector) => window.__TW_FEATURES__.queryWithinRoots(roots, fullScan, selector);

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

    function syncDatasetAndTheme(cfg) {
        const root = document.documentElement;
        if (!root) return;
        if (!htmlObserved) { htmlObserved = true; themeObserver.observe(root, { attributes: true, attributeFilter: ["style", "class"] }); }
        if (document.body && !bodyObserved) { bodyObserved = true; themeObserver.observe(document.body, { attributes: true, attributeFilter: ["style", "class"] }); }
        const mode = String(cfg.themeMode || "AUTO").toUpperCase();
        const darkVariant = String(cfg.autoDarkVariant || "LIGHTS_OUT").toUpperCase();
        const wantDark = mode === "DIM" || mode === "LIGHTS_OUT" || (mode === "AUTO" && Boolean(window.__TWITTER_SYSTEM_DARK__));
        const surfaceOverride = !wantDark ? "" : ((mode === "DIM" || (mode === "AUTO" && darkVariant === "DIM")) ? "dim" : "black");
        const sig = [
            cfg.classicTwitterBranding !== false, cfg.removeGrok !== false, cfg.removePremiumUpsells !== false,
            Boolean(cfg.hideAddFeedButton), cfg.hideViewCounts !== false, Boolean(cfg.hideBookmarkCounts),
            cfg.cleanBottomNav !== false, cfg.verifiedBadgeMode || "SHOW_ALL", surfaceOverride
        ].join("|");

        if (sig !== lastDatasetSig) {
            lastDatasetSig = sig;
            Object.assign(root.dataset, {
                twClassicBrand: String(cfg.classicTwitterBranding !== false), twRemoveGrok: String(cfg.removeGrok !== false),
                twRemoveUpsells: String(cfg.removePremiumUpsells !== false), twHideAddFeed: String(Boolean(cfg.hideAddFeedButton)),
                twHideViews: String(cfg.hideViewCounts !== false), twHideBookmarks: String(Boolean(cfg.hideBookmarkCounts)),
                twCleanNav: String(cfg.cleanBottomNav !== false), twBadgeMode: String(cfg.verifiedBadgeMode || "SHOW_ALL"),
                twSurfaceOverride: surfaceOverride
            });
            themeDirty = true;
        }
        window.__TW_THEME__?.syncSurfaceStyleSheet?.(surfaceOverride);
        if (themeDirty && document.body) {
            const hex = rgbToHex(getComputedStyle(document.body).backgroundColor);
            if (hex) {
                themeDirty = false;
                if (hex !== lastReportedThemeHex) {
                    lastReportedThemeHex = hex;
                    headerDirty = true;
                    window.TwitterBridge?.onThemeColorChanged?.(hex);
                }
            }
        }
    }

    window.__applyTwitterConfig = function (cfg, isSystemDark) {
        if (typeof isSystemDark === "boolean") window.__TWITTER_SYSTEM_DARK__ = isSystemDark;
        window.__TW_FEATURES__?.onConfigChanged?.(cfg);
        window.__TWITTER_CONFIG__ = cfg;
        window.__twNotifyColorSchemeChange?.();
        fullScanPending = themeDirty = tabsDirty = headerDirty = dialogDirty = true;
        syncDatasetAndTheme(cfg);
        runDomPass();
    };

    const isXLogoPath = (d) => Boolean(d) && (d.startsWith("M18.244 2.25h3.308") || d.startsWith("M21.742 21.75l-7.563") || d.startsWith("M14.234 10.162"));

    function swapSvgToBird(svg, path, currentD) {
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
    }

    function updateBrandLogos(useClassic, roots, fullScan, checkHeader) {
        if (useClassic) {
            if (fullScan || checkHeader) {
                for (const svg of document.querySelectorAll(BRAND_SLOT_SELECTOR)) {
                    const path = svg.querySelector("path");
                    if (!path) continue;
                    const currentD = path.getAttribute("d") || "";
                    if (path.dataset.twSwapped === "true" && currentD === BIRD_PATH) continue;
                    if (isXLogoPath(currentD) || (svg.getAttribute("viewBox") === "0 0 24 24" && !svg.closest('[data-testid="BottomBar"], nav[aria-label="Primary"]'))) {
                        swapSvgToBird(svg, path, currentD);
                    }
                }
            }
            for (const svg of queryWithinRoots(roots, fullScan, "svg:not([data-tw-logo-checked])")) {
                svg.dataset.twLogoChecked = "1";
                const path = svg.querySelector("path");
                if (!path) continue;
                const currentD = path.getAttribute("d") || "";
                if (isXLogoPath(currentD)) swapSvgToBird(svg, path, currentD);
            }
        } else {
            for (const svg of document.querySelectorAll("svg[data-tw-bird='true']")) {
                const path = svg.querySelector("path");
                if (path && path.dataset.twSwapped === "true" && path.dataset.twOrigD) {
                    path.setAttribute("d", path.dataset.twOrigD);
                    path.dataset.twOrigFill ? path.setAttribute("fill", path.dataset.twOrigFill) : path.removeAttribute("fill");
                    if (svg.dataset.twOrigViewbox) svg.setAttribute("viewBox", svg.dataset.twOrigViewbox);
                    delete svg.dataset.twBird;
                    delete svg.dataset.twLogoChecked;
                    delete path.dataset.twSwapped;
                }
            }
        }
        const t = document.title;
        if (useClassic && (t === "X" || t.endsWith(" / X"))) document.title = t === "X" ? "Twitter" : t.replace(/ \/ X$/, " / Twitter");
        else if (!useClassic && (t === "Twitter" || t.endsWith(" / Twitter"))) document.title = t === "Twitter" ? "X" : t.replace(/ \/ Twitter$/, " / X");
    }

    function mapUiText(trimmed) {
        const direct = TERM_MAP.get(trimmed);
        if (direct) return direct;
        const m = SHOW_POSTS_RE.exec(trimmed);
        if (m) return `Show ${m[1]} ${m[2].toLowerCase() === "post" ? "Tweet" : "Tweets"}`;
        return null;
    }

    function rewriteUiTerminology(useClassic, roots, fullScan) {
        for (const el of queryWithinRoots(roots, fullScan, TERM_SELECTOR)) {
            if (el.hasAttribute("data-placeholder")) {
                const ph = el.getAttribute("data-placeholder");
                if (useClassic && TERM_MAP.has(ph)) {
                    if (!el.closest(TERM_SKIP_ANCESTORS)) {
                        if (!el.dataset.twOrigPh) el.dataset.twOrigPh = ph;
                        el.setAttribute("data-placeholder", TERM_MAP.get(ph));
                    }
                } else if (!useClassic && el.dataset.twOrigPh) {
                    el.setAttribute("data-placeholder", el.dataset.twOrigPh);
                }
            }
            if (el.childNodes.length === 1 && el.firstChild.nodeType === Node.TEXT_NODE) {
                const txt = el.firstChild.nodeValue;
                const trimmed = txt ? txt.trim() : "";
                if (useClassic) {
                    const mapped = trimmed ? mapUiText(trimmed) : null;
                    if (mapped && !el.closest(TERM_SKIP_ANCESTORS)) {
                        const nextVal = txt.replace(trimmed, mapped);
                        origTextMap.set(el, txt);
                        el.dataset.twReplacedTo = el.firstChild.nodeValue = nextVal;
                    } else if (el.dataset.twReplacedTo && txt !== el.dataset.twReplacedTo) {
                        delete el.dataset.twReplacedTo;
                        origTextMap.delete(el);
                    }
                } else if (el.dataset.twReplacedTo && txt === el.dataset.twReplacedTo && origTextMap.has(el)) {
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
        let timer = null, triggered = false, startX = 0, startY = 0;
        const clear = () => { if (timer) { clearTimeout(timer); timer = null; } };
        el.addEventListener("touchstart", (e) => {
            triggered = false;
            clear();
            const t = e.touches?.[0];
            if (t) { startX = t.clientX; startY = t.clientY; }
            timer = setTimeout(() => { triggered = true; onTrigger(); }, delayMs);
        }, { passive: true });
        el.addEventListener("touchmove", (e) => {
            if (!timer) return;
            const t = e.touches?.[0];
            if (!t || Math.hypot(t.clientX - startX, t.clientY - startY) > 12) clear();
        }, { passive: true });
        for (const ev of ["touchend", "touchcancel"]) el.addEventListener(ev, clear, { passive: true });
        el.addEventListener("click", (e) => {
            if (triggered) { e.preventDefault(); e.stopPropagation(); triggered = false; }
        }, true);
        if (handleContextMenu) {
            el.addEventListener("contextmenu", (e) => {
                e.preventDefault();
                e.stopPropagation();
                clear();
                if (!triggered) { triggered = true; onTrigger(); }
            });
        }
    }
    window.__TW_BIND_LONG_PRESS__ = bindLongPress;

    function bindHeaderLongPress() {
        const triggerSettings = () => {
            if (window.TwitterBridge?.openSettingsWithHaptic) window.TwitterBridge.openSettingsWithHaptic();
            else window.TwitterBridge?.openSettings?.();
        };
        for (const el of document.querySelectorAll('[data-testid="TopNavBar"] a[href="/home"], [data-testid="TopNavBar"] svg[data-tw-bird="true"], header a[href="/home"], [data-testid="BottomBar"] a[href="/home"]')) {
            const host = el.closest("a, div[role='button']") || el;
            if (!host.dataset.twPressBound) {
                host.dataset.twPressBound = "true";
                bindLongPress(host, 480, triggerSettings);
            }
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
            if (!navContainer) continue;
            const anchor = link.parentElement && link.parentElement !== navContainer ? link.parentElement : link;
            const existing = navContainer.querySelector('[data-tw-mod-settings-item="true"]');
            if (existing) { if (!existing.dataset.twMetricsSynced) syncDrawerItemStyle(existing, link, anchor); continue; }
            const item = document.createElement("button");
            item.type = "button";
            item.className = "tw-mod-drawer-item";
            item.dataset.twOwn = "1";
            item.setAttribute("data-tw-mod-settings-item", "true");
            item.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${BIRD_PATH}"></path></svg><span>Twitter Mod Settings</span>`;
            item.addEventListener("click", (e) => { e.preventDefault(); e.stopPropagation(); window.TwitterBridge?.openSettings?.(); });
            anchor.insertAdjacentElement("beforebegin", item);
            syncDrawerItemStyle(item, link, anchor);
        }
    }

    function syncPullRefreshState() {
        const dialogOpen = Boolean(document.querySelector('[role="dialog"], [data-testid="swipeToDismiss"]'));
        if (dialogOpen !== lastDialogOpen) {
            lastDialogOpen = dialogOpen;
            window.TwitterBridge?.setPullToRefreshModalOpen?.(dialogOpen);
        }
        return dialogOpen;
    }

    function runDomPass() {
        const cfg = getCfg();
        const pathChanged = lastKnownPath !== location.pathname;
        const fullScan = fullScanPending || pathChanged;
        const checkHeader = fullScan || headerDirty;
        const checkDialog = fullScan || dialogDirty || Boolean(lastDialogOpen);
        const checkTabs = fullScan || tabsDirty;
        const roots = fullScan ? null : Array.from(dirtyRoots);

        fullScanPending = headerDirty = dialogDirty = false;
        dirtyRoots.clear();

        ensureStyleTag();
        syncDatasetAndTheme(cfg);
        updateBrandLogos(cfg.classicTwitterBranding !== false, roots, fullScan, checkHeader);
        rewriteUiTerminology(cfg.classicTwitterBranding !== false, roots, fullScan);
        if (checkHeader) {
            bindHeaderLongPress();
            injectDrawerModSettingsEntry();
        }
        if (checkHeader || checkDialog) {
            window.__TW_THEME__?.syncDrawerThemeBars?.(cfg);
        }
        if (window.__TW_FEATURES__.handleHomeTabs(cfg, checkTabs)) tabsDirty = false;
        window.__TW_FEATURES__.enhanceTweetsAndProfiles(cfg, roots, fullScan);
        if (checkDialog && syncPullRefreshState()) {
            window.__TW_FEATURES__.upgradeLightboxImages(cfg);
        }
        window.__TW_FEATURES__.enforceVideoAutoplayPause(cfg, roots, fullScan);
        window.__TW_FEATURES__.tryApplyPendingScrollRestore?.();
        lastKnownPath = location.pathname;
    }

    window.__TW_RUN_DOM_PASS__ = () => {
        fullScanPending = headerDirty = tabsDirty = themeDirty = true;
        runDomPass();
    };

    let rafPending = false;
    function schedulePass() {
        if (rafPending) return;
        rafPending = true;
        requestAnimationFrame(() => { rafPending = false; runDomPass(); });
    }

    const themeObserver = new MutationObserver(() => { themeDirty = true; schedulePass(); });

    function hasTrackedAncestor(el) {
        let p = el.parentElement;
        while (p) {
            if (dirtyRoots.has(p)) return true;
            p = p.parentElement;
        }
        return false;
    }

    const observer = new MutationObserver((records) => {
        let relevant = false;
        for (const rec of records) {
            if (rec.removedNodes.length > 0) {
                if (lastDialogOpen) { dialogDirty = relevant = true; }
                if (rec.target?.closest?.('[role="tablist"]')) { tabsDirty = relevant = true; }
            }
            for (const n of rec.addedNodes) {
                const el = n.nodeType === Node.ELEMENT_NODE ? n : (n.nodeType === Node.TEXT_NODE ? rec.target : null);
                if (!el || el.nodeType !== Node.ELEMENT_NODE || el.dataset?.twOwn === "1") continue;
                if (el.closest?.('[data-testid="app-text-transition-container"]')) continue;
                relevant = true;
                const role = el.getAttribute?.("role");
                const testId = el.getAttribute?.("data-testid");
                if (role === "tab" || role === "tablist" || el.closest?.('[role="tablist"]') || el.querySelector?.('[role="tablist"]')) tabsDirty = true;
                if (role === "dialog" || testId === "swipeToDismiss" || el.querySelector?.('[role="dialog"], [data-testid="swipeToDismiss"]')) dialogDirty = true;
                if (role === "menu" || role === "banner" || el.tagName === "HEADER" || el.tagName === "NAV" || testId === "TopNavBar" || testId === "BottomBar" || testId === "Drawer" || el.querySelector?.('a[href^="/settings"], [data-testid="TopNavBar"], [data-testid="BottomBar"], header[role="banner"]')) {
                    headerDirty = true;
                }
                if (!fullScanPending && !hasTrackedAncestor(el)) {
                    dirtyRoots.add(el);
                    if (dirtyRoots.size > 60) { fullScanPending = true; dirtyRoots.clear(); }
                }
            }
        }
        if (relevant) schedulePass();
    });
    observer.observe(document, { childList: true, subtree: true });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", () => { fullScanPending = themeDirty = headerDirty = tabsDirty = dialogDirty = true; runDomPass(); });
    } else runDomPass();
})();
