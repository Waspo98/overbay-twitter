(() => {
    if (window.__TW_VIEWPORT_INSTALLED__) return;
    window.__TW_VIEWPORT_INSTALLED__ = true;

    const INPUT_EVENTS = ["pointerdown", "touchstart", "wheel"];
    const DEFAULT_VIEWPORT_META = "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0,viewport-fit=cover";

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
        const topInset = window.__TW_CHROME__?.getNavInsetTopDp?.() || 0;
        const minBottom = 76 + topInset;
        for (const art of document.querySelectorAll('article[data-testid="tweet"]')) {
            const r = art.getBoundingClientRect();
            const id = r.height > 0 && r.bottom > minBottom && r.top < vh ? getArticleTweetId(art) : null;
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
                window.__TW_CHROME__?.syncScrollBaseline?.();
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
        const targetDpr = Number(expectedDpr) || 0;
        const dpr = window.devicePixelRatio || 0;
        const widthClose = targetW <= 0 || Math.abs(iw - targetW) <= Math.max(32, targetW * 0.12);
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
                window.__TW_CHROME__?.syncScrollBaseline?.();
                st.scrollY = 0;
                window.__TWITTER_RESTORE_STATE__ = null;
            }
        } else if (!st.anchorTweetId && !st.activeHomeTab) {
            window.__TWITTER_RESTORE_STATE__ = null;
        }
    }

    window.__TW_VIEWPORT__ = { tryApplyPendingScrollRestore };
})();
