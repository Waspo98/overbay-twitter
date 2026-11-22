(() => {
    if (window.__TW_FEATURES_INSTALLED__) return;
    window.__TW_FEATURES_INSTALLED__ = true;

    const DOWNLOAD_PATH = "M12 16l-5-5 1.41-1.41L11 12.17V4h2v8.17l2.59-2.58L17 11l-5 5zm-7 2h14v2H5v-2z";
    const CAMERA_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 6.5Q2.75 6.5 2.75 8.75v8.5Q2.75 19.5 5 19.5h14q2.25 0 2.25-2.25v-8.5Q21.25 6.5 19 6.5h-2.8L15 4.5H9L7.8 6.5Z"/><circle cx="12" cy="13" r="3.4"/></svg>';
    const SUGGESTED_FEEDS = new Set([
        "nfl", "nba", "nhl", "mlb", "baseball", "college football", "cfb", "soccer",
        "trending", "news", "sports", "entertainment", "gaming", "technology", "politics",
        "business", "finance", "music", "cricket", "golf", "tennis", "f1", "nascar",
        "ufc", "boxing", "wwe", "olympics"
    ]);
    const EXTERNAL_SKIP_RE = /^https?:\/\/([^/]+\.)?(x|twitter)\.com\//i;
    const HANDLE_FALLBACK_RE = /@([A-Za-z0-9_]{1,15})/;
    const NON_PROFILE_PATH_RE = /^\/(home|explore|search|notifications|messages|compose|settings|i)(?:\/|$)/i;
    const PROFILE_LINK_SELECTOR = '[data-testid="UserDescription"] a[href^="http"], [data-testid="UserProfileHeader_Items"] a[href^="http"], [data-testid="UserUrl"] a[href^="http"]';

    let hasAppliedInitialDefault = false;
    let userPickedTabOnSession = false;
    let homeFirstSeenAt = 0;
    let lastDefaultHomeFeed = null;
    let lastReportedFeedsSig = "";

    const getCfg = () => window.__TWITTER_CONFIG__ || {};
    const bindLongPress = (...args) => window.__TW_BIND_LONG_PRESS__?.(...args);

    function queryWithinRoots(roots, fullScan, selector) {
        if (fullScan) return document.querySelectorAll(selector);
        const out = [];
        const seen = new Set();
        for (const r of roots) {
            if (!r.isConnected) continue;
            if (r.matches?.(selector) && !seen.has(r)) {
                seen.add(r);
                out.push(r);
            }
            const nested = r.querySelectorAll?.(selector);
            if (nested?.length) {
                for (const n of nested) {
                    if (!seen.has(n)) {
                        seen.add(n);
                        out.push(n);
                    }
                }
            }
        }
        return out;
    }

    function createIconBtn(cls, label, onClick, svgHtml = `<svg viewBox="0 0 24 24"><path d="${DOWNLOAD_PATH}"></path></svg>`) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = cls;
        btn.dataset.twOwn = "1";
        btn.setAttribute("aria-label", label);
        btn.innerHTML = svgHtml;
        btn.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            btn.blur?.();
            onClick();
        });
        return btn;
    }

    function classifyHomeFeed(tab, key) {
        if (key === "for you" || key === "following") return "CORE";
        const href = (tab.getAttribute("href") || tab.querySelector("a")?.getAttribute("href") || "").toLowerCase();
        const cached = window.__TW_CACHE__?.getFeedKind?.(key);
        if (cached === "list" || href.includes("/lists/")) return "LIST";
        return (cached === "suggested" || SUGGESTED_FEEDS.has(key)) ? "SUGGESTED" : "LIST";
    }

    function markAddFeedButton(tablist) {
        const sib = tablist.nextElementSibling;
        const markSib = Boolean(
            sib && !sib.hasAttribute("data-tw-add-feed-btn") && !sib.querySelector('[role="tab"]') &&
            sib.querySelector("svg") && sib.getBoundingClientRect().width <= 84
        );
        for (const el of tablist.querySelectorAll('a, button, [role="button"]')) {
            if (!el.closest('[role="tab"]') && !el.querySelector('[role="tab"]')) {
                const target = el.closest('[role="tablist"] > *') || el;
                if (!target.hasAttribute("data-tw-add-feed-btn")) {
                    target.setAttribute("data-tw-add-feed-btn", "true");
                    target.style.order = "9999";
                }
            }
        }
        if (markSib) sib.setAttribute("data-tw-add-feed-btn", "true");
    }

    function handleHomeTabs(cfg, tabsDirty) {
        if (!location.pathname.startsWith("/home")) return false;
        if (homeFirstSeenAt === 0) homeFirstSeenAt = Date.now();
        const waitingForCustomDefault = !hasAppliedInitialDefault && !userPickedTabOnSession && (Date.now() - homeFirstSeenAt < 4000);
        if (!tabsDirty && !waitingForCustomDefault) return false;

        const tablist = document.querySelector('[data-testid="primaryColumn"] [role="tablist"]') || document.querySelector('[role="tablist"]');
        const tabs = tablist ? tablist.querySelectorAll('[role="tab"]') : [];
        if (!tablist || tabs.length === 0) return false;

        if (!tablist.dataset.twTabTapBound) {
            tablist.dataset.twTabTapBound = "1";
            tablist.addEventListener("pointerdown", (e) => {
                if (e.isTrusted && e.target?.closest?.('[role="tab"]')) {
                    userPickedTabOnSession = true;
                    hasAppliedInitialDefault = true;
                }
            }, { passive: true });
        }

        const hidden = new Set((cfg.hiddenHomeFeeds || []).map((s) => String(s).trim().toLowerCase()));
        const pinned = new Set((cfg.pinnedHomeFeeds || []).map((s) => String(s).trim().toLowerCase()));
        const order = new Map((cfg.homeFeedOrder || []).map((s, i) => [String(s).trim().toLowerCase(), i]));
        const entries = [];
        for (let i = 0; i < tabs.length; i++) {
            const tab = tabs[i];
            const name = (tab.textContent || "").replace(/\s+/g, " ").trim();
            if (!name) continue;
            const key = name.toLowerCase();
            const kind = classifyHomeFeed(tab, key);
            const target = tab.parentElement?.getAttribute("role") === "presentation" ? tab.parentElement : tab;
            entries.push({
                tab, target, name, key, kind, domIdx: i,
                visible: !hidden.has(key) && !(kind === "SUGGESTED" && cfg.stripSuggestedHomeFeeds !== false && !pinned.has(key))
            });
        }
        if (entries.length === 0) return false;
        if (entries.every((e) => !e.visible)) entries[0].visible = true;

        const sig = JSON.stringify(entries.map((e) => ({ name: e.name, kind: e.kind })));
        if (sig !== lastReportedFeedsSig) {
            lastReportedFeedsSig = sig;
            window.TwitterBridge?.reportHomeFeeds?.(sig);
        }

        markAddFeedButton(tablist);
        const sorted = [...entries].sort((a, b) => (order.get(a.key) ?? 1000 + a.domIdx) - (order.get(b.key) ?? 1000 + b.domIdx));
        sorted.forEach((e, idx) => {
            const ord = String(idx);
            if (e.target.style.order !== ord) e.target.style.order = ord;
            if (e.visible) e.target.removeAttribute("data-tw-feed-hidden");
            else if (e.target.getAttribute("data-tw-feed-hidden") !== "true") e.target.setAttribute("data-tw-feed-hidden", "true");
        });

        const visible = sorted.filter((e) => e.visible);
        const activeVisible = visible.some((e) => e.tab.getAttribute("aria-selected") === "true");
        const restoreTab = window.__TWITTER_RESTORE_STATE__?.activeHomeTab || null;
        if (visible.length > 0 && ((!hasAppliedInitialDefault && !userPickedTabOnSession) || !activeVisible)) {
            const wantKey = String(restoreTab || cfg.defaultHomeFeed || "For you").trim().toLowerCase();
            const exact = visible.find((e) => e.key === wantKey);
            const pick = exact || (!activeVisible ? visible[0] : null);
            const needsClick = Boolean(pick && pick.tab.getAttribute("aria-selected") !== "true");
            if (needsClick) pick.tab.click();
            if (exact && restoreTab && !needsClick && window.__TWITTER_RESTORE_STATE__) {
                window.__TWITTER_RESTORE_STATE__.activeHomeTab = null;
            }
            if (exact || wantKey === "for you" || wantKey === "following" || (Date.now() - homeFirstSeenAt >= 4000)) {
                hasAppliedInitialDefault = true;
            }
        }
        return true;
    }

    function extractHandleFromContainer(container) {
        if (!container) return null;
        const href = container.querySelector('a[href^="/"]')?.getAttribute("href");
        if (href) {
            const seg = href.split("/")[1];
            if (seg && seg !== "i" && seg !== "home" && seg !== "search") return seg;
        }
        const match = (container.textContent || "").match(HANDLE_FALLBACK_RE);
        return match ? match[1] : null;
    }

    function checkVerifiedBadges(scopeEl, fallbackBox) {
        for (const badge of scopeEl.querySelectorAll('[data-testid="icon-verified"]:not([data-tw-badge-checked])')) {
            const nameBox = badge.closest('[data-testid="User-Name"], [data-testid="UserName"], [data-testid="UserCell"]') || fallbackBox;
            const handle = extractHandleFromContainer(nameBox);
            const paid = handle ? window.__TW_CACHE__?.isPaidBlueOnly(handle) : null;
            if (paid !== null && paid !== undefined) {
                badge.dataset.twBadgeChecked = "true";
                if (paid) badge.setAttribute("data-tw-paid-blue", "true");
            }
        }
    }

    function processAnchor(a, unwrapTco) {
        if (unwrapTco && a.href.startsWith("https://t.co/") && !a.dataset.twUnwrapped) {
            const expanded = window.__TW_CACHE__?.getExpandedUrl(a.href);
            if (expanded) {
                a.href = expanded;
                a.dataset.twUnwrapped = "true";
            }
        }
        if (!a.dataset.twLinkBound && !a.href.startsWith("https://t.co/") && !EXTERNAL_SKIP_RE.test(a.href)) {
            a.dataset.twLinkBound = "true";
            bindLongPress(a, 520, () => window.TwitterBridge?.showLinkActions(a.href), true);
        }
    }

    function resolveVideoTweetId(article, hasVideo) {
        const quoteStatus = hasVideo.closest('[role="link"]')?.querySelector('a[href*="/status/"]')?.getAttribute("href")?.match(/\/status\/([0-9]+)/);
        if (quoteStatus) return quoteStatus[1];
        const links = article.querySelectorAll('a[href*="/status/"]');
        for (const l of links) {
            const m = l.getAttribute("href")?.match(/\/status\/([0-9]+)$/);
            if (m) return m[1];
        }
        return links[0]?.getAttribute("href")?.match(/\/status\/([0-9]+)/)?.[1] || location.pathname.match(/\/status\/([0-9]+)/)?.[1] || null;
    }

    function collectCandidateTweets(roots, fullScan) {
        if (fullScan) return document.querySelectorAll('article[data-testid="tweet"]');
        const set = new Set();
        for (const r of roots) {
            if (!r.isConnected) continue;
            const host = r.closest?.('article[data-testid="tweet"]');
            if (host) {
                if (!host.dataset.twEnhanced || r.matches?.('a, video, [role="group"], [data-testid="videoPlayer"], [data-testid="gifPlayer"], [data-testid="icon-verified"]') || r.querySelector?.('a[href^="http"], video, [role="group"], [data-testid="videoPlayer"], [data-testid="gifPlayer"], [data-testid="icon-verified"]')) {
                    set.add(host);
                }
                continue;
            }
            if (r.matches?.('article[data-testid="tweet"]')) { set.add(r); continue; }
            for (const a of (r.querySelectorAll?.('article[data-testid="tweet"]') || [])) set.add(a);
        }
        return set;
    }

    function enhanceTweetsAndProfiles(cfg, roots, fullScan) {
        for (const article of collectCandidateTweets(roots, fullScan)) {
            if (cfg.verifiedBadgeMode === "HIDE_PAID_BLUE") {
                checkVerifiedBadges(article, article.querySelector('[data-testid="User-Name"]'));
            }
            const actionGroup = window.__TW_SCREENSHOT__?.findOuterActionGroup?.(article) || article.querySelector('[role="group"]');
            const hasVideo = article.querySelector('video, [data-testid="videoPlayer"], [data-testid="gifPlayer"]');
            const existingDlBtn = article.querySelector(".tw-mod-dl-btn");
            const existingShotBtn = article.querySelector(".tw-mod-shot-btn");
            if (cfg.enableTweetScreenshot !== false && actionGroup && !existingShotBtn) {
                const shotBtn = createIconBtn("tw-mod-shot-btn", "Screenshot tweet", () => {
                    window.__TW_SCREENSHOT__?.request?.(article);
                }, CAMERA_SVG);
                if (existingDlBtn && existingDlBtn.parentElement === actionGroup) {
                    actionGroup.insertBefore(shotBtn, existingDlBtn);
                } else {
                    actionGroup.appendChild(shotBtn);
                }
            } else if (cfg.enableTweetScreenshot === false && existingShotBtn) {
                existingShotBtn.remove();
            }

            if (cfg.enableVideoDownloader && hasVideo && !existingDlBtn) {
                const tweetId = resolveVideoTweetId(article, hasVideo);
                if (tweetId && actionGroup) {
                    actionGroup.appendChild(createIconBtn("tw-mod-dl-btn", "Download video", () => {
                        const cached = window.__TW_CACHE__?.getVideoUrl(tweetId);
                        const videoSrc = article.querySelector("video")?.getAttribute("src");
                        const url = cached || (videoSrc && videoSrc.startsWith("https://") && !videoSrc.startsWith("blob:") ? videoSrc : null);
                        if (url) window.TwitterBridge?.downloadMedia(url, `twitter_${tweetId}.mp4`);
                        else window.TwitterBridge?.showToast("Video stream not cached yet—tap play once first");
                    }));
                }
            } else if (!cfg.enableVideoDownloader && existingDlBtn) {
                existingDlBtn.remove();
            }

            for (const a of article.querySelectorAll('a[href^="http"]:not([data-tw-link-bound])')) {
                processAnchor(a, cfg.unwrapTcoLinks !== false);
            }
            article.dataset.twEnhanced = "1";
        }

        if (cfg.verifiedBadgeMode === "HIDE_PAID_BLUE") {
            const badgeRoots = fullScan ? null : roots.filter((r) => r.getAttribute?.("data-testid") !== "tweet" && !r.closest?.('article[data-testid="tweet"]'));
            if (fullScan || badgeRoots.length > 0) {
                for (const box of queryWithinRoots(badgeRoots, fullScan, '[data-testid="UserName"], [data-testid="UserCell"]')) {
                    checkVerifiedBadges(box, box);
                }
            }
        }
        if (!NON_PROFILE_PATH_RE.test(location.pathname)) {
            const profileRoots = fullScan ? null : roots.filter((r) => r.getAttribute?.("data-testid") !== "cellInnerDiv" && !r.closest?.('article[data-testid="tweet"]'));
            if (fullScan || profileRoots.length > 0) {
                for (const a of queryWithinRoots(profileRoots, fullScan, PROFILE_LINK_SELECTOR)) {
                    processAnchor(a, cfg.unwrapTcoLinks !== false);
                }
            }
        }
    }

    const toOrigImageUrl = (src) => (!src || src.includes("name=orig"))
        ? src
        : (src.includes("name=") ? src.replace(/name=[^&]+/, "name=orig") : src + (src.includes("?") ? "&name=orig" : "?name=orig"));

    function upgradeLightboxImages(cfg) {
        const host = document.querySelector('[data-testid="swipeToDismiss"]')
            || (location.pathname.includes("/photo/") ? document.querySelector('[role="dialog"]') : null);
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
                    const r = c.getBoundingClientRect();
                    const vis = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                    if (vis > bestScore) { bestScore = vis; bestImg = c; }
                }
                const origUrl = toOrigImageUrl(bestImg?.src || "");
                if (origUrl) {
                    const mediaId = origUrl.match(/\/media\/([A-Za-z0-9_-]+)/)?.[1] || Date.now();
                    window.TwitterBridge?.downloadMedia(origUrl, `twitter_img_${mediaId}`);
                }
            }));
        } else if (!cfg.enableVideoDownloader && existingDl) {
            existingDl.remove();
        }
    }

    function enforceVideoAutoplayPause(cfg, roots, fullScan) {
        if (!cfg.disableVideoAutoplay) return;
        for (const v of queryWithinRoots(roots, fullScan, "video")) {
            if (!v.dataset.twAutoplayGuarded) {
                v.dataset.twAutoplayGuarded = "true";
                v.autoplay = false;
                const player = v.closest('[data-testid="videoPlayer"], [data-testid="gifPlayer"]') || v;
                player.addEventListener("pointerdown", () => { v.dataset.twUserPlayed = "true"; }, { passive: true });
                v.addEventListener("play", () => { if (getCfg().disableVideoAutoplay && !v.dataset.twUserPlayed) v.pause(); });
            }
            if (!v.dataset.twUserPlayed && !v.paused) v.pause();
        }
    }

    window.__TW_FEATURES__ = {
        queryWithinRoots,
        onConfigChanged(cfg) {
            const nextDefault = String(cfg?.defaultHomeFeed || "For you").trim().toLowerCase();
            if (lastDefaultHomeFeed !== null && nextDefault !== lastDefaultHomeFeed) {
                hasAppliedInitialDefault = false;
                userPickedTabOnSession = false;
                homeFirstSeenAt = Date.now();
            }
            lastDefaultHomeFeed = nextDefault;
        },
        handleHomeTabs,
        enhanceTweetsAndProfiles,
        upgradeLightboxImages,
        enforceVideoAutoplayPause
    };
})();
