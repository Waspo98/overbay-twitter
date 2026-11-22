(() => {
    if (window.__TW_SCREENSHOT_INSTALLED__) return;
    window.__TW_SCREENSHOT_INSTALLED__ = true;

    const HANDLE_RE = /@([A-Za-z0-9_]{1,15})/;
    const STATUS_ID_RE = /\/status\/([0-9]+)/;
    const CAPTURE_HIDE_SELECTOR = [
        "#tw-status-bar-glass",
        '[data-tw-top-bar-offset="true"]',
        '[data-tw-top-bar-autohide="true"]',
        '[data-testid="TopNavBar"]',
        '[data-testid="BottomBar"]',
        '[data-testid="FloatingActionButtonBase"]',
        '[data-testid="toast"]'
    ].join(", ");

    let activeArticle = null;
    let activeMeta = null;
    let hiddenChromeEls = [];
    let savedState = null;
    let captureStartedAt = 0;

    function rgbToHex(rgb) {
        const m = rgb && rgb.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/i);
        if (!m || (m[4] !== undefined && parseFloat(m[4]) < 0.9)) return null;
        const h = (n) => Math.min(255, Math.max(0, parseInt(n, 10))).toString(16).padStart(2, "0");
        return `#${h(m[1])}${h(m[2])}${h(m[3])}`.toUpperCase();
    }

    function findOuterActionGroup(article) {
        if (!article) return null;
        const groups = article.querySelectorAll('[role="group"]');
        let fallback = null;
        for (let i = groups.length - 1; i >= 0; i--) {
            const g = groups[i];
            if (g.closest('article[data-testid="tweet"]') !== article) continue;
            if (g.querySelector('[data-testid="reply"], [data-testid="retweet"], [data-testid="unretweet"], [data-testid="like"], [data-testid="unlike"]')) {
                return g;
            }
            if (!fallback) fallback = g;
        }
        return fallback;
    }

    function isInsideNestedQuote(el, article) {
        const quoteCard = el?.closest?.('[role="link"]');
        return Boolean(quoteCard && article && article.contains(quoteCard));
    }

    function findOuterTime(article) {
        if (!article) return null;
        const outerName = article.querySelector('[data-testid="User-Name"]');
        if (outerName) {
            const headerTime = outerName.querySelector("time");
            if (headerTime) return { timeEl: headerTime, inHeader: true };
        }
        for (const t of article.querySelectorAll("time")) {
            if (t.closest('article[data-testid="tweet"]') !== article) continue;
            const nameHost = t.closest('[data-testid="User-Name"]');
            if (nameHost && nameHost !== outerName) continue;
            if (isInsideNestedQuote(t, article) && !t.closest('a[href*="/status/"]')) continue;
            return { timeEl: t, inHeader: false };
        }
        return null;
    }

    function resolveTweetMeta(article) {
        let tweetId = "";
        const timeInfo = findOuterTime(article);
        const timeHref = timeInfo?.timeEl?.closest("a")?.getAttribute("href") || "";
        tweetId = timeHref.match(STATUS_ID_RE)?.[1] || "";
        if (!tweetId) {
            for (const a of article.querySelectorAll('a[href*="/status/"]')) {
                if (isInsideNestedQuote(a, article)) continue;
                const m = a.getAttribute("href")?.match(STATUS_ID_RE);
                if (m) { tweetId = m[1]; break; }
            }
        }
        if (!tweetId) {
            tweetId = location.pathname.match(STATUS_ID_RE)?.[1] || String(Date.now());
        }

        let authorHandle = "";
        const nameBox = article.querySelector('[data-testid="User-Name"]');
        if (nameBox) {
            const handleMatch = (nameBox.textContent || "").match(HANDLE_RE);
            if (handleMatch) authorHandle = handleMatch[1];
            else {
                const href = nameBox.querySelector('a[href^="/"]')?.getAttribute("href") || "";
                const seg = href.split("/")[1] || "";
                if (seg && seg !== "i" && seg !== "home") authorHandle = seg;
            }
        }

        const surfaceColorHex = rgbToHex(getComputedStyle(article).backgroundColor)
            || rgbToHex(getComputedStyle(document.body).backgroundColor)
            || "#000000";

        return { tweetId, authorHandle, surfaceColorHex };
    }

    function markBlockingChrome(article) {
        const marked = [];
        const addIfBlocking = (el) => {
            if (!el || el === article || el.contains(article) || article.contains(el)) return;
            if (el.dataset.twCaptureHide === "true") return;
            el.dataset.twCaptureHide = "true";
            marked.push(el);
        };
        for (const el of document.querySelectorAll(CAPTURE_HIDE_SELECTOR)) addIfBlocking(el);

        const primaryCol = document.querySelector('[data-testid="primaryColumn"]');
        const scanRoots = primaryCol ? [primaryCol] : [];
        const layers = document.getElementById("layers");
        if (layers) scanRoots.push(layers);
        for (const root of scanRoots) {
            for (const el of root.querySelectorAll("div, header, nav")) {
                if (el.contains(article) || article.contains(el)) continue;
                const pos = getComputedStyle(el).position;
                if (pos !== "fixed" && pos !== "sticky") continue;
                const r = el.getBoundingClientRect();
                if (r.height > 0 && r.height <= 260 && r.width > 0) addIfBlocking(el);
            }
        }
        return marked;
    }

    function measureActive() {
        if (!activeArticle || !activeArticle.isConnected) return null;
        const rect = activeArticle.getBoundingClientRect();
        if (rect.width < 40 || rect.height < 24) return null;
        const scrollY = Math.max(0, window.scrollY || document.documentElement?.scrollTop || document.body?.scrollTop || 0);
        const scrollHeight = Math.max(document.documentElement?.scrollHeight || 0, document.body?.scrollHeight || 0);
        const viewportWidth = window.innerWidth || document.documentElement?.clientWidth || 0;
        const viewportHeight = window.innerHeight || document.documentElement?.clientHeight || 0;
        if (viewportWidth <= 0 || viewportHeight <= 0) return null;

        let fullHeightCss = rect.height;
        let cleanHeightCss = rect.height;
        const actionGroup = findOuterActionGroup(activeArticle);
        const timeInfo = findOuterTime(activeArticle);

        if (actionGroup) {
            const agRect = actionGroup.getBoundingClientRect();
            const actionTopOffset = agRect.top - rect.top;
            const actionBottomOffset = agRect.bottom - rect.top;
            if (actionBottomOffset > 40) {
                fullHeightCss = Math.min(rect.height, actionBottomOffset + 4);
            }
            if (timeInfo && !timeInfo.inHeader) {
                const timeRow = timeInfo.timeEl.closest("a")?.parentElement || timeInfo.timeEl.parentElement || timeInfo.timeEl;
                const timeBottom = timeRow.getBoundingClientRect().bottom;
                const timeOffset = timeBottom - rect.top;
                if (timeOffset > 30 && timeOffset <= actionTopOffset + 4) {
                    cleanHeightCss = Math.min(fullHeightCss, timeOffset + 10);
                } else if (actionTopOffset > 30) {
                    cleanHeightCss = Math.min(fullHeightCss, actionTopOffset - 2);
                }
                let maxStatsBottom = 0;
                for (const link of activeArticle.querySelectorAll('a[href*="/retweets"], a[href*="/quotes"], a[href*="/likes"]')) {
                    if (link.closest('article[data-testid="tweet"]') !== activeArticle) continue;
                    const lr = link.getBoundingClientRect();
                    if (lr.height > 0 && lr.top >= timeBottom - 6 && lr.bottom <= agRect.top + 6) {
                        const rowBottom = (link.parentElement?.getBoundingClientRect()?.bottom || lr.bottom);
                        if (rowBottom > maxStatsBottom) maxStatsBottom = rowBottom;
                    }
                }
                if (maxStatsBottom > timeBottom + 8) {
                    fullHeightCss = Math.min(rect.height, Math.max(cleanHeightCss, (maxStatsBottom - rect.top) + 10));
                }
            } else if (actionTopOffset > 30) {
                cleanHeightCss = Math.min(fullHeightCss, actionTopOffset - 2);
            }
        }

        const meta = activeMeta || resolveTweetMeta(activeArticle);
        return {
            rectLeft: rect.left,
            rectTop: rect.top,
            rectWidth: rect.width,
            rectHeight: rect.height,
            docTop: rect.top + scrollY,
            scrollY,
            scrollHeight,
            viewportWidth,
            viewportHeight,
            cleanHeightCss: Math.max(24, Math.min(fullHeightCss, cleanHeightCss)),
            fullHeightCss: Math.max(24, fullHeightCss),
            surfaceColorHex: meta.surfaceColorHex,
            tweetId: meta.tweetId,
            authorHandle: meta.authorHandle
        };
    }

    function request(article) {
        if (!article || !article.isConnected) return;
        const now = Date.now();
        if (savedState && now - captureStartedAt < 4000) return;
        if (savedState) end();
        captureStartedAt = now;
        activeArticle = article;
        activeMeta = resolveTweetMeta(article);
        window.TwitterBridge?.requestTweetScreenshot?.(JSON.stringify(activeMeta));
    }

    function begin() {
        if (!activeArticle || !activeArticle.isConnected) return null;
        const root = document.documentElement;
        if (!savedState) {
            savedState = {
                scrollY: Math.max(0, window.scrollY || root?.scrollTop || document.body?.scrollTop || 0),
                scrollBehavior: root?.style?.scrollBehavior || "",
                bottomHidden: root?.dataset?.twBottomHidden
            };
        }
        if (root) {
            root.style.scrollBehavior = "auto";
            root.dataset.twCapturing = "true";
        }
        activeArticle.dataset.twCaptureTarget = "true";
        hiddenChromeEls = markBlockingChrome(activeArticle);
        return measureActive();
    }

    function scrollToAndMeasure(targetScrollY) {
        if (!activeArticle || !activeArticle.isConnected) return null;
        const y = Math.max(0, Math.round(Number(targetScrollY) || 0));
        window.scrollTo(0, y);
        return measureActive();
    }

    function end() {
        const root = document.documentElement;
        const prev = savedState;
        savedState = null;
        captureStartedAt = 0;

        for (const el of hiddenChromeEls) {
            if (el?.dataset) delete el.dataset.twCaptureHide;
        }
        hiddenChromeEls = [];

        if (activeArticle?.dataset) delete activeArticle.dataset.twCaptureTarget;
        activeArticle = null;
        activeMeta = null;

        if (prev) {
            const curY = Math.max(0, window.scrollY || root?.scrollTop || document.body?.scrollTop || 0);
            if (Math.abs(curY - prev.scrollY) > 1) {
                window.scrollTo(0, prev.scrollY);
            }
            if (root) {
                root.style.scrollBehavior = prev.scrollBehavior;
                if (prev.bottomHidden === undefined) delete root.dataset.twBottomHidden;
                else root.dataset.twBottomHidden = prev.bottomHidden;
            }
        }
        if (root) delete root.dataset.twCapturing;
        window.__TW_CHROME__?.syncScrollBaseline?.();
        return true;
    }

    window.__TW_SCREENSHOT__ = {
        findOuterActionGroup,
        request,
        begin,
        measure: measureActive,
        scrollToAndMeasure,
        end
    };
})();
