(() => {
    if (window.__TW_CHROME_INSTALLED__) return;
    window.__TW_CHROME_INSTALLED__ = true;

    let activeNavInsetTopDp = Math.max(0, Math.round(Number(window.__TWITTER_NAV_INSET_TOP__) || 0));
    let activeNavInsetDp = Math.max(0, Math.round(Number(window.__TWITTER_NAV_INSET_BOTTOM__) || 0));

    let activeAutoHideEl = null, activeMaskEl = null, activeTopOffsetEl = null;
    let activeResetTfEls = [], activeClearGlassEls = [], activeUnifiedHostEl = null;
    let activeGlassObsEl = null, activeHdrH = 0, glassResizeObs = null, resizeRaf = 0;

    const isChatPath = () => location.pathname.startsWith("/messages") || location.pathname.startsWith("/i/chat");

    function hasBackdropFilter(cs) {
        return Boolean(cs && ((cs.backdropFilter && cs.backdropFilter !== "none") || (cs.webkitBackdropFilter && cs.webkitBackdropFilter !== "none")));
    }

    function parseRgba(raw) {
        const m = raw && raw.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)$/i);
        return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : parseFloat(m[4])] : null;
    }

    function isSurfaceBg(raw) {
        const c = parseRgba(raw);
        if (!c || c[3] < 0.4) return false;
        const [r, g, b] = c;
        return (r <= 18 && g <= 22 && b <= 28) || (r >= 15 && r <= 32 && g >= 24 && g <= 44 && b >= 34 && b <= 58) || (r >= 240 && g >= 240 && b >= 240);
    }

    function canMountFixedInside(el) {
        for (let p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
            const cs = getComputedStyle(p);
            if ((cs.transform && cs.transform !== "none") || (cs.filter && cs.filter !== "none") ||
                (cs.perspective && cs.perspective !== "none") || hasBackdropFilter(cs) ||
                /paint|layout|strict|content/.test(cs.contain || "") ||
                /transform|filter|perspective|backdrop-filter/.test(cs.willChange || "")) {
                return false;
            }
        }
        return Boolean(el);
    }

    function syncSingleAttr(prev, next, attr) {
        if (prev && prev !== next) prev.removeAttribute(attr);
        if (next && next.getAttribute(attr) !== "true") next.setAttribute(attr, "true");
        return next || null;
    }

    function syncListAttr(prev, next, attr) {
        const nextSet = new Set(next);
        for (const el of prev) if (!nextSet.has(el)) el.removeAttribute(attr);
        for (const el of next) if (el.getAttribute(attr) !== "true") el.setAttribute(attr, "true");
        return next;
    }

    function syncActiveAutoHide(nextHideEl, nextMaskEl, nextResetEls) {
        if (activeAutoHideEl && activeAutoHideEl !== nextHideEl) {
            activeAutoHideEl.removeAttribute("data-tw-top-bar-autohide");
            activeAutoHideEl.removeAttribute("data-tw-top-bar-masked");
        }
        activeAutoHideEl = nextHideEl || null;
        activeMaskEl = syncSingleAttr(activeMaskEl, nextMaskEl, "data-tw-top-bar-mask");
        activeResetTfEls = syncListAttr(activeResetTfEls, nextResetEls, "data-tw-top-bar-reset-tf");
    }

    function applyTopHdrHeight(h) {
        const root = document.documentElement;
        if (!root) return;
        const nextH = h > 0 && h <= 220 ? Math.ceil(h) : 0;
        activeHdrH = nextH;
        const hPx = `${nextH}px`;
        if (root.style.getPropertyValue("--tw-top-hdr-h") !== hPx) root.style.setProperty("--tw-top-hdr-h", hPx);
    }

    function syncGlassHeightObserver(targetEl) {
        if (activeGlassObsEl === targetEl) return;
        glassResizeObs?.disconnect();
        activeGlassObsEl = targetEl || null;
        if (!targetEl || typeof ResizeObserver !== "function") return;
        if (!glassResizeObs) {
            glassResizeObs = new ResizeObserver((entries) => {
                if (!activeGlassObsEl || !activeGlassObsEl.isConnected) return;
                const h = entries[entries.length - 1]?.borderBoxSize?.[0]?.blockSize || activeGlassObsEl.getBoundingClientRect().height;
                if (h > 0 && h <= 220) applyTopHdrHeight(h);
            });
        }
        glassResizeObs.observe(targetEl);
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
            applyTopHdrHeight(unified.hdrH);
            if (bar.dataset.twGlassUnified !== "true") bar.dataset.twGlassUnified = "true";
            const autoFlag = unified.autoHide ? "true" : "false";
            if (bar.dataset.twGlassAutohide !== autoFlag) bar.dataset.twGlassAutohide = autoFlag;
        } else {
            if (bar.parentElement !== document.body) document.body.appendChild(bar);
            applyTopHdrHeight(0);
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
            activeTopOffsetEl = syncSingleAttr(activeTopOffsetEl, null, "data-tw-top-bar-offset");
            activeUnifiedHostEl = syncSingleAttr(activeUnifiedHostEl, null, "data-tw-top-bar-unified-host");
            syncActiveAutoHide(null, null, []);
            activeClearGlassEls = syncListAttr(activeClearGlassEls, [], "data-tw-top-bar-clear-glass");
            syncGlassHeightObserver(null);
            applyTopHdrHeight(0);
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
                chain.push(c);
                const isLayerFixed = cs.position === "fixed" && r.height === 0 &&
                    c.parentElement?.id === "layers" && r.top >= (strictTop ? -4 : -240) && r.top <= topInset + 16;
                const validBox = isLayerFixed || (r.height > 0 && r.height <= 240 &&
                    (!strictTop || (r.top >= -4 && r.top <= topInset + 12 && r.width >= minW)));
                if (!validBox) continue;
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

        let nextTopOffset = null, nextUnifiedHost = null, nextHideEl = null, nextMaskEl = null;
        const nextResetTfEls = [];
        const cw = document.documentElement?.clientWidth || vw;

        if (res) {
            const { posHost, glassEl, hostChain } = res;
            nextTopOffset = posHost;
            for (const c of hostChain) {
                if (c.getAttribute("data-tw-top-bar-clear-pad") !== "true" && Math.round(parseFloat(getComputedStyle(c).paddingTop) || 0) >= topInset - 2) {
                    c.setAttribute("data-tw-top-bar-clear-pad", "true");
                }
            }
            const hostHasTabs = Boolean(posHost.querySelector?.('[role="tablist"]'));
            const glassCoversAll = Boolean(glassEl && (!hostHasTabs || glassEl.querySelector?.('[role="tablist"]')));
            const glassRect = glassEl ? glassEl.getBoundingClientRect() : null;
            const hdrH = glassRect ? Math.ceil(glassRect.height) : 0;
            if (glassCoversAll && hdrH > 0 && hdrH <= 220 && (cw <= 0 || glassRect.width >= cw - 8) && canMountFixedInside(posHost.parentElement)) {
                if (!posHost.dataset.twOrigZ && posHost.getAttribute("data-tw-top-bar-unified-host") !== "true") {
                    const rawZ = parseInt(getComputedStyle(posHost).zIndex, 10);
                    posHost.dataset.twOrigZ = String(Number.isFinite(rawZ) && rawZ >= 1 ? rawZ : 1);
                }
                const zIndex = parseInt(posHost.dataset.twOrigZ, 10) || 1;
                nextUnifiedHost = posHost;
                unified = { posHost, hdrH, zIndex, autoHide: allowAutoHide };
            }
            if (allowAutoHide) {
                let slideEl = glassCoversAll ? glassEl : (posHost.getBoundingClientRect().height > 0 ? posHost : (glassEl || topNav));
                if (slideEl) {
                    const slideRect = slideEl.getBoundingClientRect(), slideH = Math.ceil(slideRect.height);
                    const pEl = slideEl.parentElement, pRect = pEl && posHost.contains(pEl) ? pEl.getBoundingClientRect() : null;
                    const isHiddenNow = document.documentElement?.dataset?.twBottomHidden === "true";
                    const topAligned = (a, b) => isHiddenNow || Math.abs(a.top - b.top) <= 3;
                    if (pRect && pRect.height >= slideH - 2 && pRect.height <= 240 && topAligned(pRect, slideRect)) {
                        nextMaskEl = pEl;
                    } else if (slideEl.children.length === 1) {
                        const ch = slideEl.firstElementChild, chRect = ch.getBoundingClientRect();
                        if (chRect.height >= slideH - 2 && chRect.height <= 240 && topAligned(chRect, slideRect)) {
                            nextMaskEl = slideEl;
                            slideEl = ch;
                        }
                    }
                    nextHideEl = slideEl;
                    if (nextHideEl.getAttribute("data-tw-top-bar-autohide") !== "true") nextHideEl.setAttribute("data-tw-top-bar-autohide", "true");
                    const maskedFlag = nextMaskEl ? "true" : "false";
                    if (nextHideEl.getAttribute("data-tw-top-bar-masked") !== maskedFlag) nextHideEl.setAttribute("data-tw-top-bar-masked", maskedFlag);
                    for (const c of hostChain) if (c !== nextHideEl) nextResetTfEls.push(c);
                }
            } else if (getComputedStyle(posHost).position === "fixed") {
                nextResetTfEls.push(posHost);
            }
        }

        activeTopOffsetEl = syncSingleAttr(activeTopOffsetEl, nextTopOffset, "data-tw-top-bar-offset");
        activeUnifiedHostEl = syncSingleAttr(activeUnifiedHostEl, nextUnifiedHost, "data-tw-top-bar-unified-host");
        syncActiveAutoHide(nextHideEl, nextMaskEl, nextResetTfEls);
        ensureStatusBarGlass(res?.glassEl || null, unified);
        syncGlassHeightObserver(unified ? res?.glassEl : null);

        const nextClearGlass = [];
        if (unified && res?.glassEl) {
            const minGlassW = cw > 0 ? cw - 12 : 200;
            nextClearGlass.push(res.glassEl);
            for (const ch of res.glassEl.querySelectorAll("div")) {
                if (ch.matches?.('button, [role="button"], [role="tab"], input, [role="textbox"]') || ch.closest?.('[role="tab"]')) continue;
                const cr = ch.getBoundingClientRect();
                if (cr.width < minGlassW) continue;
                const ccs = getComputedStyle(ch);
                if (ch.getAttribute("data-tw-top-bar-clear-glass") === "true" || hasBackdropFilter(ccs) || (cr.height >= 36 && isSurfaceBg(ccs.backgroundColor))) {
                    nextClearGlass.push(ch);
                }
            }
        }
        activeClearGlassEls = syncListAttr(activeClearGlassEls, nextClearGlass, "data-tw-top-bar-clear-glass");

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
    let lastScrollY = getScrollY(), scrollAccum = 0, lastScrollPath = location.pathname, lastScrollKey = null;
    let scrollerLastY = new WeakMap(), lastUserInputAt = 0, suppressHideUntil = 0;

    function setBottomChromeHidden(hidden) {
        const root = document.documentElement, next = hidden ? "true" : "false";
        if (root && root.dataset.twBottomHidden !== next) root.dataset.twBottomHidden = next;
    }

    function syncScrollBaseline() { lastScrollY = getScrollY(); scrollAccum = 0; lastScrollKey = null; scrollerLastY = new WeakMap(); }
    function resetBottomScrollTracker() { syncScrollBaseline(); setBottomChromeHidden(false); }

    function readScrollTargetY(target) {
        if (!target || target === document || target === window || target === document.documentElement || target === document.body) {
            return { key: document, y: getScrollY() };
        }
        if (target.nodeType !== 1 || target.closest?.('[data-testid="Drawer"], [role="dialog"], [data-testid="sheetDialog"], [role="menu"]')) return null;
        const vh = window.innerHeight || 600;
        if (target.clientHeight < vh * 0.45 || target.scrollHeight <= target.clientHeight + 48) return null;
        return { key: target, y: Math.max(0, Math.round(target.scrollTop || 0)) };
    }

    function handleScrollEvent(e) {
        if (document.documentElement?.dataset?.twCapturing === "true") return;
        if (lastScrollPath !== location.pathname) { lastScrollPath = location.pathname; resetBottomScrollTracker(); return; }
        const info = readScrollTargetY(e?.target);
        if (!info) return;
        let prevY;
        if (info.key === document) { prevY = lastScrollY; lastScrollY = info.y; }
        else if (!scrollerLastY.has(info.key)) { scrollerLastY.set(info.key, info.y); return; }
        else { prevY = scrollerLastY.get(info.key); scrollerLastY.set(info.key, info.y); }
        if (lastScrollKey !== info.key) { lastScrollKey = info.key; scrollAccum = 0; }
        if (info.y <= 16) { scrollAccum = 0; setBottomChromeHidden(false); return; }
        const dy = info.y - prevY;
        if (Math.abs(dy) < 1) return;
        const now = Date.now();
        if (Math.abs(dy) > 140 && now - lastUserInputAt > 650) { scrollAccum = 0; return; }
        if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) scrollAccum = 0;
        scrollAccum += dy;
        const hideFloor = Math.max(64, Math.round(activeHdrH * 0.75));
        if (scrollAccum >= 32 && info.y > hideFloor && now >= suppressHideUntil) { setBottomChromeHidden(true); scrollAccum = 0; }
        else if (scrollAccum <= -20) { setBottomChromeHidden(false); scrollAccum = 0; }
    }

    const markUserInput = () => { lastUserInputAt = Date.now(); };
    for (const ev of ["touchstart", "touchmove", "touchend", "wheel"]) window.addEventListener(ev, markUserInput, { passive: true });
    window.addEventListener("pointerdown", (e) => {
        if (e.isTrusted && e.target?.closest?.('[role="tablist"] [role="tab"]')) {
            suppressHideUntil = Date.now() + 450;
            syncScrollBaseline();
            setBottomChromeHidden(false);
        }
    }, { passive: true, capture: true });
    window.addEventListener("scroll", handleScrollEvent, { passive: true, capture: true });
    window.addEventListener("popstate", () => { lastScrollPath = location.pathname; resetBottomScrollTracker(); }, { passive: true });
    window.addEventListener("resize", () => {
        if (resizeRaf) return;
        resizeRaf = requestAnimationFrame(() => { resizeRaf = 0; syncNavInsets(); });
    }, { passive: true });

    function syncNavInsets() {
        const root = document.documentElement;
        if (!root) return;
        if (lastScrollPath !== location.pathname) { lastScrollPath = location.pathname; resetBottomScrollTracker(); }
        const topInset = activeNavInsetTopDp, inset = activeNavInsetDp;
        const topPx = `${topInset}px`, targetPx = `${inset}px`, topFlag = topInset > 0 ? "true" : "false", flag = inset > 0 ? "true" : "false";
        if (root.style.getPropertyValue("--tw-nav-inset-top") !== topPx) root.style.setProperty("--tw-nav-inset-top", topPx);
        if (root.dataset.twTopInset !== topFlag) root.dataset.twTopInset = topFlag;
        if (root.style.getPropertyValue("--tw-nav-inset-bottom") !== targetPx) root.style.setProperty("--tw-nav-inset-bottom", targetPx);
        if (root.dataset.twNavInset !== flag) root.dataset.twNavInset = flag;

        const vw = window.innerWidth || 0, vh = window.innerHeight || 0;
        syncTopInsets(topInset, vw, vh);
        if (inset <= 0) return;

        const bottomBar = document.querySelector('[data-testid="BottomBar"]');
        let bottomBarHeight = 0;
        if (bottomBar) {
            if (bottomBar.getAttribute("data-tw-bottom-bar-surface") !== "true") {
                const rectH = Math.round(bottomBar.getBoundingClientRect().height);
                const origPad = Math.round(parseFloat(getComputedStyle(bottomBar).paddingBottom) || 0);
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
            syncBottomOffsetHost(fabBase, vh, bottomBarHeight > 0 ? Math.max(inset + 16, Math.round(bottomBarHeight) + 12) : (16 + inset));
        }

        for (const el of document.querySelectorAll('[data-testid="inline_reply_offscreen"], [data-testid="dmComposerTextInput"], [data-testid="toolBar"]')) {
            const host = findFixedHost(el, 5, 6, 220);
            if (host && host.getAttribute("data-tw-bottom-bar-surface") !== "true" && host.getAttribute("data-tw-fixed-bottom-pad") !== "true") {
                host.setAttribute("data-tw-fixed-bottom-pad", "true");
            }
        }

        const toast = document.querySelector('[data-testid="toast"]');
        if (toast && vh > 0) {
            syncBottomOffsetHost(findFixedHost(toast, 3, 180, 140), vh, Math.max(inset + 76, bottomBarHeight > 0 ? Math.round(bottomBarHeight) + 16 : 0));
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
    window.__TW_CHROME__ = { syncNavInsets, syncScrollBaseline, getNavInsetTopDp: () => activeNavInsetTopDp };
})();
