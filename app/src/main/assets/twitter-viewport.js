(() => {
    if (window.__TW_VIEWPORT_INSTALLED__) return;
    window.__TW_VIEWPORT_INSTALLED__ = true;

    const INPUT_EVENTS = ["pointerdown", "touchstart", "wheel"];
    const DEFAULT_VIEWPORT_META = "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0,viewport-fit=cover";
    let activeNavInsetDp = Math.max(0, Math.round(Number(window.__TWITTER_NAV_INSET_BOTTOM__) || 0));

    function findFixedHost(el, maxHops, maxBottomGap = 8, maxHeightPx = 160) {
        let cur = el, fixedHost = null;
        const vh = window.innerHeight || 0;
        for (let i = 0; i < maxHops && cur && cur !== document.body && cur !== document.documentElement; i++) {
            if (cur.tagName === "MAIN" || cur.id === "react-root") break;
            if (getComputedStyle(cur).position === "fixed") {
                const r = cur.getBoundingClientRect();
                if (r.height > 0 && r.height <= maxHeightPx && (vh <= 0 || Math.abs(vh - r.bottom) <= maxBottomGap)) {
                    fixedHost = cur;
                }
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
    let lastScrollY = getScrollY();
    let scrollAccum = 0;
    let lastScrollPath = location.pathname;
    let scrollerLastY = new WeakMap();

    function setBottomChromeHidden(hidden) {
        const root = document.documentElement;
        if (!root) return;
        const next = hidden ? "true" : "false";
        if (root.dataset.twBottomHidden !== next) {
            root.dataset.twBottomHidden = next;
        }
    }

    function syncScrollBaseline() {
        lastScrollY = getScrollY();
        scrollAccum = 0;
        scrollerLastY = new WeakMap();
    }

    function resetBottomScrollTracker() {
        syncScrollBaseline();
        setBottomChromeHidden(false);
    }

    function readScrollTargetY(target) {
        if (!target || target === document || target === window || target === document.documentElement || target === document.body) {
            return { key: document, y: getScrollY() };
        }
        if (target.nodeType !== 1) return null;
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
        const prevY = info.key === document ? lastScrollY : (scrollerLastY.get(info.key) ?? 0);
        if (info.key === document) lastScrollY = info.y;
        else scrollerLastY.set(info.key, info.y);

        if (info.y <= 16) {
            scrollAccum = 0;
            setBottomChromeHidden(false);
            return;
        }
        const dy = info.y - prevY;
        if (Math.abs(dy) < 1) return;
        if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) {
            scrollAccum = 0;
        }
        scrollAccum += dy;
        if (scrollAccum >= 24 && info.y > 48) {
            setBottomChromeHidden(true);
            scrollAccum = 0;
        } else if (scrollAccum <= -16) {
            setBottomChromeHidden(false);
            scrollAccum = 0;
        }
    }

    window.addEventListener("scroll", handleScrollEvent, { passive: true, capture: true });

    window.addEventListener("popstate", () => {
        lastScrollPath = location.pathname;
        resetBottomScrollTracker();
    }, { passive: true });

    function syncNavInsets() {
        const root = document.documentElement;
        if (!root) return;
        if (lastScrollPath !== location.pathname) {
            lastScrollPath = location.pathname;
            resetBottomScrollTracker();
        }
        const inset = activeNavInsetDp;
        const targetPx = `${inset}px`;
        if (root.style.getPropertyValue("--tw-nav-inset-bottom") !== targetPx) {
            root.style.setProperty("--tw-nav-inset-bottom", targetPx);
        }
        const flag = inset > 0 ? "true" : "false";
        if (root.dataset.twNavInset !== flag) root.dataset.twNavInset = flag;
        if (inset <= 0) return;

        const vh = window.innerHeight || 0;
        const bottomBar = document.querySelector('[data-testid="BottomBar"]');
        let bottomBarHeight = 0;
        if (bottomBar) {
            // Only add fallback bottom padding on older WebViews where env(safe-area-inset-bottom) is 0.
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

    window.__twSetNavInsetBottom = function (dp) {
        activeNavInsetDp = Math.max(0, Math.round(Number(dp) || 0));
        window.__TWITTER_NAV_INSET_BOTTOM__ = activeNavInsetDp;
        syncNavInsets();
    };

    function getActiveHomeTabName() {
        if (!location.pathname.startsWith("/home")) return null;
        const tab = document.querySelector(
            '[data-testid="primaryColumn"] [role="tablist"] [role="tab"][aria-selected="true"], ' +
            '[role="tablist"] [role="tab"][aria-selected="true"]'
        );
        return (tab?.textContent || "").replace(/\s+/g, " ").trim() || null;
    }

    function getArticleTweetId(art) {
        let fallback = null;
        for (const l of art.querySelectorAll('a[href*="/status/"]')) {
            const href = l.getAttribute("href") || "";
            const m = href.match(/\/status\/([0-9]+)(?:\/|\?|$)/);
            if (!m) continue;
            const quoteCard = l.closest('[role="link"]');
            if (!quoteCard || !art.contains(quoteCard)) return m[1];
            if (!fallback && href.endsWith(`/status/${m[1]}`)) fallback = m[1];
        }
        return fallback;
    }

    function findVisibleAnchorTweet() {
        const vh = window.innerHeight || 800;
        for (const art of document.querySelectorAll('article[data-testid="tweet"]')) {
            const r = art.getBoundingClientRect();
            const id = r.height > 0 && r.bottom > 76 && r.top < vh ? getArticleTweetId(art) : null;
            if (id) return { id, top: Math.round(r.top) };
        }
        return null;
    }

    function scrollToTweetAnchor(anchor) {
        const id = anchor?.id && /^[0-9]+$/.test(String(anchor.id)) ? String(anchor.id) : null;
        if (!id) return false;
        for (const art of document.querySelectorAll('article[data-testid="tweet"]')) {
            if (getArticleTweetId(art) !== id) continue;
            const r = art.getBoundingClientRect();
            if (r.height <= 0) continue;
            const delta = r.top - (typeof anchor.top === "number" ? anchor.top : 88);
            if (Math.abs(delta) > 8) {
                window.scrollBy(0, delta);
                syncScrollBaseline();
            }
            return true;
        }
        return false;
    }

    window.__twCaptureLocation = function () {
        const anchor = findVisibleAnchorTweet();
        return {
            url: location.href,
            scrollY: Math.round(window.scrollY || 0),
            anchorTweetId: anchor?.id || null,
            anchorTop: anchor?.top ?? null,
            activeHomeTab: getActiveHomeTabName()
        };
    };

    let reflowRaf = 0;
    let reflowTimers = [];
    let reflowOrigMeta = null;
    let reflowCleanup = null;
    let reflowMoved = false;

    window.__twCancelReflow = function () {
        if (reflowRaf) {
            cancelAnimationFrame(reflowRaf);
            reflowRaf = 0;
        }
        for (const id of reflowTimers) clearTimeout(id);
        reflowTimers = [];
        reflowCleanup?.();
        reflowCleanup = null;
        if (reflowOrigMeta !== null) {
            document.querySelector('meta[name="viewport"]')?.setAttribute("content", reflowOrigMeta);
            reflowOrigMeta = null;
        }
    };

    window.__twReflowViewport = function (expectedWidthDp) {
        window.__twCancelReflow();
        const anchor = findVisibleAnchorTweet();
        const meta = document.querySelector('meta[name="viewport"]');
        const rawW = Math.round(Number(expectedWidthDp) || 0);
        const targetW = rawW > 0 ? Math.max(320, rawW) : 0;
        reflowMoved = false;
        const onMove = () => { reflowMoved = true; };
        for (const ev of INPUT_EVENTS) window.addEventListener(ev, onMove, { passive: true, once: true });
        reflowCleanup = () => { for (const ev of INPUT_EVENTS) window.removeEventListener(ev, onMove); };
        if (meta && targetW > 0) {
            reflowOrigMeta = meta.getAttribute("content") || DEFAULT_VIEWPORT_META;
            meta.setAttribute(
                "content",
                `width=${targetW},initial-scale=1,maximum-scale=1,user-scalable=0,viewport-fit=cover`
            );
        }
        reflowRaf = requestAnimationFrame(() => {
            reflowRaf = 0;
            if (meta && reflowOrigMeta !== null) {
                meta.setAttribute("content", reflowOrigMeta);
                reflowOrigMeta = null;
            }
            window.dispatchEvent(new Event("resize"));
            window.dispatchEvent(new Event("orientationchange"));
            const finish = (finalPass) => {
                if (!reflowMoved) scrollToTweetAnchor(anchor);
                window.__TW_RUN_DOM_PASS__?.();
                if (finalPass) {
                    reflowCleanup?.();
                    reflowCleanup = null;
                }
            };
            reflowTimers = [setTimeout(() => finish(false), 120), setTimeout(() => finish(true), 300)];
        });
    };

    window.__twCheckLayoutAligned = function (expectWide, expectedWidthDp, expectedDpr) {
        if (!document.querySelector('[data-testid="primaryColumn"], main[role="main"]')) return true;
        const iw = window.innerWidth || document.documentElement?.clientWidth || 0;
        const targetW = Number(expectedWidthDp) || 0;
        const widthClose = targetW <= 0 || Math.abs(iw - targetW) <= Math.max(32, targetW * 0.12);
        const targetDpr = Number(expectedDpr) || 0;
        const dpr = window.devicePixelRatio || 0;
        const dprClose = targetDpr <= 0 || dpr <= 0 || Math.abs(dpr - targetDpr) <= Math.max(0.12, targetDpr * 0.05);
        const hasBottomBar = Boolean(document.querySelector('[data-testid="BottomBar"]'));
        const hasSidePrimaryNav = Boolean(document.querySelector('header[role="banner"] nav[aria-label="Primary"]'));
        return expectWide
            ? (iw >= 490 && widthClose && dprClose && !hasBottomBar)
            : (iw > 0 && iw < 500 && widthClose && dprClose && !hasSidePrimaryNav);
    };

    let restoreCancelled = false;
    let restoreStartedAt = 0;
    let restoreInputBound = false;

    function tryApplyPendingScrollRestore() {
        const st = window.__TWITTER_RESTORE_STATE__;
        if (!st || restoreCancelled) {
            window.__TWITTER_RESTORE_STATE__ = null;
            return;
        }
        if (!restoreInputBound) {
            restoreInputBound = true;
            for (const ev of INPUT_EVENTS) {
                window.addEventListener(ev, () => { restoreCancelled = true; }, { passive: true, once: true });
            }
        }
        if (!restoreStartedAt) restoreStartedAt = Date.now();
        if (Date.now() - restoreStartedAt > 6500) {
            window.__TWITTER_RESTORE_STATE__ = null;
            return;
        }
        if (location.pathname.startsWith("/home") && st.activeHomeTab) {
            const activeName = getActiveHomeTabName();
            if (!activeName || activeName.toLowerCase() !== String(st.activeHomeTab).trim().toLowerCase()) return;
            st.activeHomeTab = null;
        }
        if (st.anchorTweetId && scrollToTweetAnchor({ id: st.anchorTweetId, top: st.anchorTop })) {
            st.anchorTweetId = null;
            st.scrollY = 0;
            window.__TWITTER_RESTORE_STATE__ = null;
            return;
        }
        if (!location.pathname.startsWith("/home") && st.scrollY > 40) {
            const maxScroll = (document.documentElement?.scrollHeight || 0) - (window.innerHeight || 0);
            if (maxScroll >= st.scrollY * 0.85) {
                window.scrollTo(0, st.scrollY);
                syncScrollBaseline();
                st.scrollY = 0;
                window.__TWITTER_RESTORE_STATE__ = null;
            }
        } else if (!st.anchorTweetId && !st.activeHomeTab) {
            window.__TWITTER_RESTORE_STATE__ = null;
        }
    }

    syncNavInsets();
    window.__TW_VIEWPORT__ = { tryApplyPendingScrollRestore, syncNavInsets };
})();
