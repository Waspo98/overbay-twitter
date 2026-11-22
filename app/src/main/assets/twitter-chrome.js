(() => {
    if (window.__TW_CHROME_INSTALLED__) return;
    window.__TW_CHROME_INSTALLED__ = true;

    let activeNavInsetTopDp = Math.max(0, Math.round(Number(window.__TWITTER_NAV_INSET_TOP__) || 0));
    let activeNavInsetDp = Math.max(0, Math.round(Number(window.__TWITTER_NAV_INSET_BOTTOM__) || 0));

    let activeAutoHideEl = null;
    let activeResetTfEls = [];
    let activeClearGlassEls = [];
    let activeUnifiedHostEl = null;

    const isChatPath = () => location.pathname.startsWith("/messages") || location.pathname.startsWith("/i/chat");

    function hasBackdropFilter(cs) {
        return Boolean(cs && ((cs.backdropFilter && cs.backdropFilter !== "none") || (cs.webkitBackdropFilter && cs.webkitBackdropFilter !== "none")));
    }

    function parseRgba(raw) {
        const m = raw && raw.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)$/i);
        return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : parseFloat(m[4])] : null;
    }

    function canMountFixedInside(el) {
        for (let p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
            const cs = getComputedStyle(p);
            if ((cs.transform && cs.transform !== "none") || (cs.filter && cs.filter !== "none") ||
                (cs.perspective && cs.perspective !== "none") || /paint|layout|strict|content/.test(cs.contain || "")) {
                return false;
            }
        }
        return Boolean(el);
    }

    function syncActiveAutoHide(nextHideEl, nextResetEls) {
        if (activeAutoHideEl && activeAutoHideEl !== nextHideEl) {
            activeAutoHideEl.removeAttribute("data-tw-top-bar-autohide");
            activeAutoHideEl.removeAttribute("data-tw-top-bar-clip");
        }
        const nextSet = new Set(nextResetEls);
        for (const el of activeResetTfEls) {
            if (!nextSet.has(el)) el.removeAttribute("data-tw-top-bar-reset-tf");
        }
        activeAutoHideEl = nextHideEl || null;
        activeResetTfEls = nextResetEls;
    }

    function syncActiveClearGlass(nextEls) {
        const nextSet = new Set(nextEls);
        for (const el of activeClearGlassEls) {
            if (!nextSet.has(el)) el.removeAttribute("data-tw-top-bar-clear-glass");
        }
        for (const el of nextEls) {
            if (el.getAttribute("data-tw-top-bar-clear-glass") !== "true") el.setAttribute("data-tw-top-bar-clear-glass", "true");
        }
        activeClearGlassEls = nextEls;
    }

    function syncActiveUnifiedHost(nextHost) {
        if (activeUnifiedHostEl && activeUnifiedHostEl !== nextHost) {
            activeUnifiedHostEl.removeAttribute("data-tw-top-bar-unified-host");
        }
        if (nextHost && nextHost.getAttribute("data-tw-top-bar-unified-host") !== "true") {
            nextHost.setAttribute("data-tw-top-bar-unified-host", "true");
        }
        activeUnifiedHostEl = nextHost || null;
    }

    function ensureStatusBarGlass(glassEl, unified) {
        const root = document.documentElement;
        if (!root || !document.body) return;
        let bar = document.getElementById("tw-status-bar-glass");
        if (!bar) {
            bar = document.createElement("div");
            bar.id = "tw-status-bar-glass";
            bar.dataset.twOwn = "1";
            bar.setAttribute("aria-hidden", "true");
        }
        const host = unified?.posHost;
        if (host?.parentElement) {
            if (bar.parentElement !== host.parentElement || bar.nextSibling !== host) host.parentElement.insertBefore(bar, host);
            const z = String(unified.zIndex);
            if (root.style.getPropertyValue("--tw-glass-z") !== z) root.style.setProperty("--tw-glass-z", z);
            const hPx = `${unified.hdrH}px`;
            if (root.style.getPropertyValue("--tw-top-hdr-h") !== hPx) root.style.setProperty("--tw-top-hdr-h", hPx);
            if (bar.dataset.twGlassUnified !== "true") bar.dataset.twGlassUnified = "true";
            const autoFlag = unified.autoHide ? "true" : "false";
            if (bar.dataset.twGlassAutohide !== autoFlag) bar.dataset.twGlassAutohide = autoFlag;
        } else {
            if (bar.parentElement !== document.body) document.body.appendChild(bar);
            if (root.style.getPropertyValue("--tw-top-hdr-h") !== "0px") root.style.setProperty("--tw-top-hdr-h", "0px");
            if (bar.dataset.twGlassUnified !== "false") bar.dataset.twGlassUnified = "false";
            if (bar.dataset.twGlassAutohide !== "false") bar.dataset.twGlassAutohide = "false";
        }

        const override = root.dataset.twSurfaceOverride || "";
        const bodyRgb = parseRgba(getComputedStyle(document.body).backgroundColor);
        const hasOpaqueBody = Boolean(bodyRgb && bodyRgb[3] >= 0.1);
        const isLight = override
            ? false
            : (hasOpaqueBody ? (bodyRgb[0] + bodyRgb[1] + bodyRgb[2]) > 380 : !window.__TW_THEME__?.isEffectiveDarkScheme?.());
        const isDim = override === "dim" || (
            !override && hasOpaqueBody &&
            bodyRgb[0] >= 15 && bodyRgb[0] <= 30 && bodyRgb[1] >= 25 && bodyRgb[1] <= 42 && bodyRgb[2] >= 35 && bodyRgb[2] <= 55
        );
        let bg = isDim ? "rgba(21, 32, 43, 0.78)" : (isLight ? "rgba(255, 255, 255, 0.85)" : "rgba(0, 0, 0, 0.65)");
        let blur = "blur(12px)";

        if (!override && glassEl) {
            if (!glassEl.dataset.twOrigGlassBg && glassEl.getAttribute("data-tw-top-bar-clear-glass") !== "true") {
                const gcs = getComputedStyle(glassEl);
                if (hasBackdropFilter(gcs)) {
                    glassEl.dataset.twOrigGlassBg = gcs.backgroundColor || "";
                    glassEl.dataset.twOrigGlassBlur = (gcs.backdropFilter && gcs.backdropFilter !== "none" ? gcs.backdropFilter : gcs.webkitBackdropFilter) || "";
                }
            }
            const rawBg = glassEl.dataset.twOrigGlassBg || "";
            const rawBlur = glassEl.dataset.twOrigGlassBlur || "";
            const rgba = parseRgba(rawBg);
            if (rgba && rgba[3] >= 0.2 && rgba[3] <= 0.94) {
                const glassIsLight = (rgba[0] + rgba[1] + rgba[2]) > 380;
                const glassIsDim = !glassIsLight && (rgba[2] - rgba[0]) >= 12;
                if (glassIsLight === isLight && glassIsDim === isDim) {
                    bg = rawBg;
                    if (rawBlur && rawBlur !== "none") blur = rawBlur;
                }
            }
        }
        if (root.style.getPropertyValue("--tw-status-glass-bg") !== bg) root.style.setProperty("--tw-status-glass-bg", bg);
        if (root.style.getPropertyValue("--tw-status-glass-blur") !== blur) root.style.setProperty("--tw-status-glass-blur", blur);
    }

    function applyTopOverlayPad(el, topInset) {
        if (!el || el.getAttribute("data-tw-top-overlay-pad") === "true") return;
        const origPad = Math.round(parseFloat(getComputedStyle(el).paddingTop) || 0);
        if (origPad >= topInset - 2) return;
        el.style.setProperty("--tw-top-orig-pad", `${origPad}px`);
        el.setAttribute("data-tw-top-overlay-pad", "true");
    }

    function syncTopInsets(topInset, vw, vh) {
        if (topInset <= 0) {
            syncActiveAutoHide(null, []);
            syncActiveClearGlass([]);
            syncActiveUnifiedHost(null);
            return;
        }
        const minW = vw > 0 ? Math.min(vw * 0.65, 320) : 0;
        const isGlassNode = (el, cs) => el.getAttribute("data-tw-top-bar-clear-glass") === "true" || hasBackdropFilter(cs);
        const inspectHeader = (seed, strictTop) => {
            if (!seed) return null;
            let posHost = null, posHostIdx = -1, glassEl = null;
            const chain = [];
            const inner = !strictTop && !isGlassNode(seed, getComputedStyle(seed)) && seed.firstElementChild &&
                isGlassNode(seed.firstElementChild, getComputedStyle(seed.firstElementChild))
                ? seed.firstElementChild
                : null;
            for (let c = inner || seed, i = 0;
                c && i < 8 && c !== document.body && c.id !== "react-root" && c.id !== "layers" &&
                c.tagName !== "MAIN" && c.getAttribute("data-testid") !== "primaryColumn";
                c = c.parentElement, i++) {
                if (c.querySelector?.('article[data-testid="tweet"]')) break;
                const cs = getComputedStyle(c), r = c.getBoundingClientRect();
                const isLayerFixed = cs.position === "fixed" && r.height === 0 &&
                    c.parentElement?.id === "layers" && r.top >= -4 && r.top <= topInset + 8;
                if (!isLayerFixed) {
                    if (r.height <= 0 || r.height > 240) continue;
                    if (strictTop && (r.top < -4 || r.top > topInset + 12 || r.width < minW)) continue;
                }
                chain.push(c);
                if (!isLayerFixed && isGlassNode(c, cs)) glassEl = c;
                if (cs.position === "sticky" || cs.position === "fixed") {
                    posHost = c;
                    posHostIdx = chain.length - 1;
                    if (strictTop) break;
                }
            }
            return posHost ? { posHost, glassEl, hostChain: chain.slice(0, posHostIdx + 1) } : null;
        };

        let res = null, allowAutoHide = false, unified = null;
        const topNav = document.querySelector('[data-testid="TopNavBar"]');
        if (topNav) {
            res = inspectHeader(topNav, false);
            allowAutoHide = !isChatPath();
        }
        if (!res) {
            const q = 'main[role="main"] [data-testid="app-bar-back"], main[role="main"] [data-testid="SearchBox_Search_Input"], main[role="main"] h2[role="heading"], main[role="main"] [role="tablist"]';
            for (const s of document.querySelectorAll(q)) {
                if (s.closest?.('[data-testid="sidebarColumn"]')) continue;
                const sr = s.getBoundingClientRect();
                if (sr.top >= -4 && sr.top <= topInset + 130 && (res = inspectHeader(s, true))) break;
            }
        }

        let nextUnifiedHost = null;
        let nextHideEl = null;
        const nextResetTfEls = [];

        if (res) {
            const { posHost, glassEl, hostChain } = res;
            if (posHost.getAttribute("data-tw-top-bar-offset") !== "true") posHost.setAttribute("data-tw-top-bar-offset", "true");
            for (const c of hostChain) {
                if (c.getAttribute("data-tw-top-bar-clear-pad") !== "true" && Math.round(parseFloat(getComputedStyle(c).paddingTop) || 0) >= topInset - 2) {
                    c.setAttribute("data-tw-top-bar-clear-pad", "true");
                }
            }
            const hostHasTabs = Boolean(posHost.querySelector?.('[role="tablist"]'));
            const glassCoversAll = Boolean(glassEl && (!hostHasTabs || glassEl.querySelector?.('[role="tablist"]')));
            const glassRect = glassEl ? glassEl.getBoundingClientRect() : null;
            const hdrH = glassRect ? Number(glassRect.height.toFixed(1)) : 0;
            const cw = document.documentElement?.clientWidth || vw;
            if (glassCoversAll && hdrH > 0 && hdrH <= 220 && (cw <= 0 || glassRect.width >= cw - 8) && canMountFixedInside(posHost.parentElement)) {
                const rawZ = parseInt(getComputedStyle(posHost).zIndex, 10);
                const zIndex = Number.isFinite(rawZ) && rawZ >= 1 ? rawZ : 1;
                if (!Number.isFinite(rawZ) || rawZ < 1) nextUnifiedHost = posHost;
                unified = { posHost, hdrH, zIndex, autoHide: allowAutoHide };
            }
            if (allowAutoHide) {
                nextHideEl = glassCoversAll ? glassEl : (posHost.getBoundingClientRect().height > 0 ? posHost : (glassEl || topNav));
                if (nextHideEl) {
                    const useClip = Boolean(unified) || hasBackdropFilter(getComputedStyle(nextHideEl));
                    if (nextHideEl.getAttribute("data-tw-top-bar-autohide") !== "true") nextHideEl.setAttribute("data-tw-top-bar-autohide", "true");
                    const clipFlag = useClip ? "true" : "false";
                    if (nextHideEl.getAttribute("data-tw-top-bar-clip") !== clipFlag) nextHideEl.setAttribute("data-tw-top-bar-clip", clipFlag);
                    for (const c of hostChain) {
                        if (c !== nextHideEl) {
                            nextResetTfEls.push(c);
                            if (c.getAttribute("data-tw-top-bar-reset-tf") !== "true") c.setAttribute("data-tw-top-bar-reset-tf", "true");
                        }
                    }
                }
            }
        }

        syncActiveUnifiedHost(nextUnifiedHost);
        syncActiveAutoHide(nextHideEl, nextResetTfEls);
        ensureStatusBarGlass(res?.glassEl || null, unified);

        const nextClearGlass = [];
        if (unified && res?.glassEl) {
            nextClearGlass.push(res.glassEl);
            for (const ch of res.glassEl.querySelectorAll("div")) {
                if (ch.getBoundingClientRect().width >= (vw > 0 ? vw - 12 : 200) &&
                    (ch.getAttribute("data-tw-top-bar-clear-glass") === "true" || hasBackdropFilter(getComputedStyle(ch)))) {
                    nextClearGlass.push(ch);
                }
            }
        }
        syncActiveClearGlass(nextClearGlass);

        for (const ov of document.querySelectorAll('[data-testid="Drawer"], [role="dialog"]:not([data-testid="sheetDialog"])')) {
            if (ov.matches?.('[data-testid="swipeToDismiss"]') || ov.querySelector?.('[data-testid="swipeToDismiss"]')) continue;
            const r = ov.getBoundingClientRect();
            if (r.top >= -4 && r.top <= 4 && vh > 0 && r.height >= vh * 0.72) applyTopOverlayPad(ov.querySelector('[data-testid="TopNavBar"]') || ov, topInset);
        }
        const sideNav = document.querySelector('header[role="banner"] nav[aria-label="Primary"]')?.closest('header[role="banner"] > div');
        if (sideNav && sideNav.getBoundingClientRect().top <= 4) applyTopOverlayPad(sideNav, topInset);
    }

    function findFixedHost(el, maxHops, maxBottomGap = 8, maxHeightPx = 160) {
        let cur = el, fixedHost = null;
        const vh = window.innerHeight || 0;
        for (let i = 0; i < maxHops && cur && cur !== document.body && cur !== document.documentElement; i++) {
            if (cur.tagName === "MAIN" || cur.id === "react-root") break;
            if (getComputedStyle(cur).position === "fixed") {
                const r = cur.getBoundingClientRect();
                if (r.height > 0 && r.height <= maxHeightPx && (vh <= 0 || Math.abs(vh - r.bottom) <= maxBottomGap)) fixedHost = cur;
            }
            cur = cur.parentElement;
        }
        return fixedHost;
    }

    function syncBottomOffsetHost(host, vh, threshold) {
        if (!host) return;
        if (!host.dataset.twOrigBottomGap && document.documentElement?.dataset?.twBottomHidden !== "true") {
            host.dataset.twOrigBottomGap = String(Math.max(0, Math.round(vh - host.getBoundingClientRect().bottom)));
        }
        if (!host.dataset.twOrigBottomGap) return;
        const origGap = Number(host.dataset.twOrigBottomGap) || 0;
        if (origGap < threshold) {
            if (host.getAttribute("data-tw-fab-offset") !== "true") host.setAttribute("data-tw-fab-offset", "true");
        } else if (host.hasAttribute("data-tw-fab-offset")) {
            host.removeAttribute("data-tw-fab-offset");
        }
    }

    const getScrollY = () => Math.max(0, Math.round(window.scrollY || document.documentElement?.scrollTop || document.body?.scrollTop || 0));
    let lastScrollY = getScrollY(), scrollAccum = 0, lastScrollPath = location.pathname, scrollerLastY = new WeakMap();

    function setBottomChromeHidden(hidden) {
        const root = document.documentElement, next = hidden ? "true" : "false";
        if (root && root.dataset.twBottomHidden !== next) root.dataset.twBottomHidden = next;
    }

    function syncScrollBaseline() { lastScrollY = getScrollY(); scrollAccum = 0; scrollerLastY = new WeakMap(); }
    function resetBottomScrollTracker() { syncScrollBaseline(); setBottomChromeHidden(false); }

    function readScrollTargetY(target) {
        if (!target || target === document || target === window || target === document.documentElement || target === document.body) {
            return { key: document, y: getScrollY() };
        }
        if (target.nodeType !== 1) return null;
        if (target.closest?.('[data-testid="Drawer"], [role="dialog"], [data-testid="sheetDialog"], [role="menu"]')) return null;
        const vh = window.innerHeight || 600;
        if (target.clientHeight < vh * 0.45 || target.scrollHeight <= target.clientHeight + 48) return null;
        return { key: target, y: Math.max(0, Math.round(target.scrollTop || 0)) };
    }

    function handleScrollEvent(e) {
        if (lastScrollPath !== location.pathname) {
            lastScrollPath = location.pathname;
            resetBottomScrollTracker();
            return;
        }
        const info = readScrollTargetY(e?.target);
        if (!info) return;
        let prevY;
        if (info.key === document) {
            prevY = lastScrollY;
            lastScrollY = info.y;
        } else if (!scrollerLastY.has(info.key)) {
            scrollerLastY.set(info.key, info.y);
            return;
        } else {
            prevY = scrollerLastY.get(info.key);
            scrollerLastY.set(info.key, info.y);
        }
        if (info.y <= 16) {
            scrollAccum = 0;
            setBottomChromeHidden(false);
            return;
        }
        const dy = info.y - prevY;
        if (Math.abs(dy) < 1) return;
        if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) scrollAccum = 0;
        scrollAccum += dy;
        if (scrollAccum >= 24 && info.y > 48) { setBottomChromeHidden(true); scrollAccum = 0; }
        else if (scrollAccum <= -16) { setBottomChromeHidden(false); scrollAccum = 0; }
    }

    window.addEventListener("scroll", handleScrollEvent, { passive: true, capture: true });
    window.addEventListener("popstate", () => { lastScrollPath = location.pathname; resetBottomScrollTracker(); }, { passive: true });

    function syncNavInsets() {
        const root = document.documentElement;
        if (!root) return;
        if (lastScrollPath !== location.pathname) {
            lastScrollPath = location.pathname;
            resetBottomScrollTracker();
        }
        const topInset = activeNavInsetTopDp, inset = activeNavInsetDp;
        const topPx = `${topInset}px`, targetPx = `${inset}px`;
        if (root.style.getPropertyValue("--tw-nav-inset-top") !== topPx) root.style.setProperty("--tw-nav-inset-top", topPx);
        const topFlag = topInset > 0 ? "true" : "false";
        if (root.dataset.twTopInset !== topFlag) root.dataset.twTopInset = topFlag;
        if (root.style.getPropertyValue("--tw-nav-inset-bottom") !== targetPx) root.style.setProperty("--tw-nav-inset-bottom", targetPx);
        const flag = inset > 0 ? "true" : "false";
        if (root.dataset.twNavInset !== flag) root.dataset.twNavInset = flag;

        const vw = window.innerWidth || 0, vh = window.innerHeight || 0;
        syncTopInsets(topInset, vw, vh);
        if (inset <= 0) return;

        const bottomBar = document.querySelector('[data-testid="BottomBar"]');
        let bottomBarHeight = 0;
        if (bottomBar) {
            if (bottomBar.getAttribute("data-tw-bottom-bar-surface") !== "true") {
                const cs = getComputedStyle(bottomBar);
                const rectH = Math.round(bottomBar.getBoundingClientRect().height);
                const origPad = Math.round(parseFloat(cs.paddingBottom) || 0);
                if (rectH > 0 && origPad < inset - 2) {
                    bottomBar.style.setProperty("--tw-bottom-bar-base-h", `${rectH}px`);
                    bottomBar.style.setProperty("--tw-bottom-bar-orig-pad", `${origPad}px`);
                    bottomBar.setAttribute("data-tw-bottom-bar-surface", "true");
                }
            }
            bottomBarHeight = bottomBar.getBoundingClientRect().height || 0;
        }

        const fabBase = document.querySelector('[data-testid="FloatingActionButtonBase"]')
            || findFixedHost(document.querySelector('[data-testid="FloatingActionButtons_Tweet_Button"]'), 4, 180, 120);
        if (fabBase && vh > 0) {
            const fabThreshold = bottomBarHeight > 0 ? Math.max(inset + 16, Math.round(bottomBarHeight) + 12) : (16 + inset);
            syncBottomOffsetHost(fabBase, vh, fabThreshold);
        }

        for (const el of document.querySelectorAll('[data-testid="inline_reply_offscreen"], [data-testid="dmComposerTextInput"], [data-testid="toolBar"]')) {
            const host = findFixedHost(el, 5, 6, 220);
            if (host && host.getAttribute("data-tw-bottom-bar-surface") !== "true" && host.getAttribute("data-tw-fixed-bottom-pad") !== "true") {
                host.setAttribute("data-tw-fixed-bottom-pad", "true");
            }
        }

        const toast = document.querySelector('[data-testid="toast"]');
        if (toast && vh > 0) {
            const toastThreshold = Math.max(inset + 76, bottomBarHeight > 0 ? Math.round(bottomBarHeight) + 16 : 0);
            syncBottomOffsetHost(findFixedHost(toast, 3, 180, 140), vh, toastThreshold);
        }
    }

    window.__twSetNavInsets = function (topDp, bottomDp) {
        activeNavInsetTopDp = Math.max(0, Math.round(Number(topDp) || 0));
        activeNavInsetDp = Math.max(0, Math.round(Number(bottomDp) || 0));
        window.__TWITTER_NAV_INSET_TOP__ = activeNavInsetTopDp;
        window.__TWITTER_NAV_INSET_BOTTOM__ = activeNavInsetDp;
        syncNavInsets();
    };

    syncNavInsets();
    window.__TW_CHROME__ = {
        syncNavInsets,
        syncScrollBaseline,
        getNavInsetTopDp: () => activeNavInsetTopDp
    };
})();
