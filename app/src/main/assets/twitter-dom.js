(() => {
    if (window.__TW_DOM_INSTALLED__) return;
    window.__TW_DOM_INSTALLED__ = true;

    const BIRD_PATH = "M23.643 4.937c-.835.37-1.732.62-2.675.733.962-.576 1.7-1.49 2.048-2.578-.9.534-1.897.922-2.958 1.13-.85-.904-2.06-1.47-3.4-1.47-2.572 0-4.658 2.086-4.658 4.66 0 .364.042.718.12 1.06-3.873-.195-7.304-2.05-9.602-4.868-.4.69-.63 1.49-.63 2.342 0 1.616.823 3.043 2.072 3.878-.764-.025-1.482-.234-2.11-.583v.06c0 2.257 1.605 4.14 3.737 4.568-.392.106-.803.162-1.227.162-.3 0-.593-.028-.877-.082.593 1.85 2.313 3.198 4.352 3.234-1.595 1.25-3.604 1.995-5.786 1.995-.376 0-.747-.022-1.112-.065 2.062 1.323 4.51 2.093 7.14 2.093 8.57 0 13.255-7.098 13.255-13.254 0-.2-.005-.402-.014-.602.91-.658 1.7-1.477 2.323-2.41z";
    const DOWNLOAD_PATH = "M12 16l-5-5 1.41-1.41L11 12.17V4h2v8.17l2.59-2.58L17 11l-5 5zm-7 2h14v2H5v-2z";

    const origTextMap = new WeakMap();
    const TERM_MAP = new Map([
        ["Post", "Tweet"],
        ["Posts", "Tweets"],
        ["Post your reply", "Tweet your reply"],
        ["Repost", "Retweet"],
        ["Reposted", "Retweeted"],
        ["Reposts", "Retweets"],
        ["Quote", "Quote Tweet"],
        ["Quotes", "Quote Tweets"],
        ["What is happening?!", "What's happening?"],
        ["X", "Twitter"]
    ]);

    let switchedOnThisHomeVisit = false;
    let lastKnownPath = "";

    function getCfg() {
        return window.__TWITTER_CONFIG__ || {};
    }

    function ensureStyleTag() {
        const cssText = window.__TWITTER_CLEAN_CSS__;
        const mount = document.head || document.documentElement;
        if (!cssText || !mount) return;
        let styleEl = document.getElementById("tw-mod-style");
        if (!styleEl) {
            styleEl = document.createElement("style");
            styleEl.id = "tw-mod-style";
            styleEl.textContent = cssText;
            mount.appendChild(styleEl);
        }
    }

    function syncDatasetFlags(cfg) {
        const root = document.documentElement;
        if (!root) return;
        root.dataset.twClassicBrand = String(cfg.classicTwitterBranding !== false);
        root.dataset.twRemoveGrok = String(cfg.removeGrok !== false);
        root.dataset.twRemoveUpsells = String(cfg.removePremiumUpsells !== false);
        root.dataset.twHideForyou = String(Boolean(cfg.hideForYouTab));
        root.dataset.twHideViews = String(cfg.hideViewCounts !== false);
        root.dataset.twHideBookmarks = String(Boolean(cfg.hideBookmarkCounts));
        root.dataset.twCleanNav = String(cfg.cleanBottomNav !== false);
        root.dataset.twBadgeMode = String(cfg.verifiedBadgeMode || "SHOW_ALL");
    }

    window.__applyTwitterConfig = function (cfg) {
        window.__TWITTER_CONFIG__ = cfg;
        syncDatasetFlags(cfg);
        runDomPass();
    };

    function isXLogoPath(d) {
        if (!d) return false;
        return d.startsWith("M18.244 2.25h3.308") || d.startsWith("M21.742 21.75l-7.563") || d.startsWith("M14.234 10.162");
    }

    function updateBrandLogos(useClassic) {
        const svgs = document.querySelectorAll("svg");
        for (const svg of svgs) {
            const path = svg.querySelector("path");
            if (!path) continue;
            const currentD = path.getAttribute("d") || "";
            if (useClassic && (isXLogoPath(currentD) || (svg.closest('[aria-label="X"], a[href="/home"]') && !path.dataset.twSwapped))) {
                if (!isXLogoPath(currentD) && currentD !== BIRD_PATH && svg.getAttribute("viewBox") !== "0 0 24 24") {
                    continue;
                }
                if (!path.dataset.twOrigD && currentD !== BIRD_PATH) {
                    path.dataset.twOrigD = currentD;
                    path.dataset.twOrigFill = path.getAttribute("fill") || "";
                    svg.dataset.twOrigViewbox = svg.getAttribute("viewBox") || "";
                }
                if (path.dataset.twOrigD) {
                    path.setAttribute("d", BIRD_PATH);
                    path.setAttribute("fill", "#1DA1F2");
                    svg.setAttribute("viewBox", "0 0 24 24");
                    svg.dataset.twBird = "true";
                    path.dataset.twSwapped = "true";
                }
            } else if (!useClassic && path.dataset.twSwapped === "true" && path.dataset.twOrigD) {
                path.setAttribute("d", path.dataset.twOrigD);
                if (path.dataset.twOrigFill) {
                    path.setAttribute("fill", path.dataset.twOrigFill);
                } else {
                    path.removeAttribute("fill");
                }
                if (svg.dataset.twOrigViewbox) {
                    svg.setAttribute("viewBox", svg.dataset.twOrigViewbox);
                }
                delete svg.dataset.twBird;
                delete path.dataset.twSwapped;
            }
        }

        if (useClassic) {
            if (document.title === "X" || document.title.endsWith(" / X")) {
                document.title = document.title === "X" ? "Twitter" : document.title.replace(/ \/ X$/, " / Twitter");
            }
        } else if (document.title === "Twitter" || document.title.endsWith(" / Twitter")) {
            document.title = document.title === "Twitter" ? "X" : document.title.replace(/ \/ Twitter$/, " / X");
        }
    }

    function rewriteUiTerminology(useClassic) {
        const selectors = [
            "button span", "[role='button'] span", "[role='tab'] span",
            "h1 span", "h2 span", "nav a span", "[data-testid='tweetButtonInline'] span",
            "[data-testid='tweetButton'] span", "[data-placeholder]"
        ].join(",");

        const nodes = document.querySelectorAll(selectors);
        for (const el of nodes) {
            if (el.closest('[data-testid="tweetText"], [data-testid="UserDescription"], [contenteditable="true"]')) {
                continue;
            }
            if (el.hasAttribute("data-placeholder")) {
                const ph = el.getAttribute("data-placeholder");
                if (useClassic && TERM_MAP.has(ph)) {
                    if (!el.dataset.twOrigPh) el.dataset.twOrigPh = ph;
                    el.setAttribute("data-placeholder", TERM_MAP.get(ph));
                } else if (!useClassic && el.dataset.twOrigPh) {
                    el.setAttribute("data-placeholder", el.dataset.twOrigPh);
                }
            }
            if (el.childNodes.length === 1 && el.firstChild.nodeType === Node.TEXT_NODE) {
                const txt = el.firstChild.nodeValue;
                const trimmed = txt ? txt.trim() : "";
                if (useClassic && TERM_MAP.has(trimmed)) {
                    if (!origTextMap.has(el)) origTextMap.set(el, txt);
                    el.firstChild.nodeValue = txt.replace(trimmed, TERM_MAP.get(trimmed));
                } else if (!useClassic && origTextMap.has(el)) {
                    el.firstChild.nodeValue = origTextMap.get(el);
                }
            }
        }
    }

    function bindHeaderLongPress() {
        const targets = document.querySelectorAll('[data-testid="TopNavBar"] a[href="/home"], [data-testid="TopNavBar"] svg[data-tw-bird="true"], header a[href="/home"]');
        for (const el of targets) {
            const host = el.closest("a, div[role='button']") || el;
            if (host.dataset.twPressBound) continue;
            host.dataset.twPressBound = "true";
            let timer = null;
            let triggered = false;
            const start = () => {
                triggered = false;
                timer = setTimeout(() => {
                    triggered = true;
                    window.TwitterBridge?.openSettings();
                }, 500);
            };
            const cancel = () => {
                if (timer) {
                    clearTimeout(timer);
                    timer = null;
                }
            };
            host.addEventListener("touchstart", start, { passive: true });
            host.addEventListener("touchend", cancel, { passive: true });
            host.addEventListener("touchmove", cancel, { passive: true });
            host.addEventListener("touchcancel", cancel, { passive: true });
            host.addEventListener("click", (e) => {
                if (triggered) {
                    e.preventDefault();
                    e.stopPropagation();
                    triggered = false;
                }
            }, true);
        }
    }

    function injectDrawerModSettingsEntry() {
        const settingsLinks = document.querySelectorAll('a[href="/settings"], a[href="/settings/account"]');
        for (const link of settingsLinks) {
            const navContainer = link.closest('nav, [role="menu"], [data-testid="Drawer"], [role="dialog"]');
            if (!navContainer || navContainer.querySelector('[data-tw-mod-settings-item="true"]')) continue;

            const item = document.createElement("button");
            item.type = "button";
            item.className = "tw-mod-drawer-item";
            item.setAttribute("data-tw-mod-settings-item", "true");
            item.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${BIRD_PATH}"></path></svg><span>Twitter Mod Settings</span>`;
            item.addEventListener("click", (e) => {
                e.preventDefault();
                e.stopPropagation();
                window.TwitterBridge?.openSettings();
            });
            link.parentElement?.insertAdjacentElement("beforebegin", item);
        }
    }

    function handleHomeTabs(cfg) {
        if (!location.pathname.startsWith("/home")) {
            switchedOnThisHomeVisit = false;
            return;
        }
        if (lastKnownPath !== location.pathname) {
            switchedOnThisHomeVisit = false;
        }
        const tabs = document.querySelectorAll('[role="tablist"] [role="tab"]');
        let forYouTab = null;
        let followingTab = null;
        for (const tab of tabs) {
            const label = (tab.textContent || "").trim().toLowerCase();
            if (label === "for you") {
                tab.setAttribute("data-tw-foryou-tab", "true");
                forYouTab = tab;
            } else if (label === "following") {
                followingTab = tab;
            }
        }
        if (cfg.forceFollowingTab && !switchedOnThisHomeVisit && forYouTab && followingTab) {
            if (forYouTab.getAttribute("aria-selected") === "true") {
                switchedOnThisHomeVisit = true;
                followingTab.click();
            } else if (followingTab.getAttribute("aria-selected") === "true") {
                switchedOnThisHomeVisit = true;
            }
        }
    }

    function enhanceTweets(cfg) {
        const tweets = document.querySelectorAll('article[data-testid="tweet"]');
        for (const article of tweets) {
            // Selective paid-blue badge tagging
            if (cfg.verifiedBadgeMode === "HIDE_PAID_BLUE") {
                const badge = article.querySelector('[data-testid="icon-verified"]');
                if (badge && !badge.dataset.twBadgeChecked) {
                    badge.dataset.twBadgeChecked = "true";
                    const authorLink = article.querySelector('[data-testid="User-Name"] a[href^="/"]');
                    const handle = authorLink?.getAttribute("href")?.split("/")[1];
                    if (handle && window.__TW_CACHE__?.isPaidBlueOnly(handle)) {
                        badge.setAttribute("data-tw-paid-blue", "true");
                    }
                }
            }

            // 1-Tap Video / GIF Download button
            const hasVideo = article.querySelector('video, [data-testid="videoPlayer"], [data-testid="gifPlayer"]');
            const existingBtn = article.querySelector(".tw-mod-dl-btn");
            if (cfg.enableVideoDownloader && hasVideo && !existingBtn) {
                const statusLink = article.querySelector('a[href*="/status/"]');
                const match = statusLink?.getAttribute("href")?.match(/\/status\/([0-9]+)/);
                const tweetId = match ? match[1] : null;
                const actionGroup = article.querySelector('[role="group"]');
                if (tweetId && actionGroup) {
                    const btn = document.createElement("button");
                    btn.type = "button";
                    btn.className = "tw-mod-dl-btn";
                    btn.setAttribute("aria-label", "Download video");
                    btn.innerHTML = `<svg viewBox="0 0 24 24"><path d="${DOWNLOAD_PATH}"></path></svg>`;
                    btn.addEventListener("click", (e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        const cached = window.__TW_CACHE__?.getVideoUrl(tweetId);
                        const videoSrc = article.querySelector("video")?.getAttribute("src");
                        const url = cached || (videoSrc && videoSrc.startsWith("https://") && !videoSrc.startsWith("blob:") ? videoSrc : null);
                        if (url) {
                            window.TwitterBridge?.downloadMedia(url, `twitter_${tweetId}.mp4`);
                        } else {
                            window.TwitterBridge?.showToast("Video stream not cached yet—scroll or tap tweet first");
                        }
                    });
                    actionGroup.appendChild(btn);
                }
            } else if (!cfg.enableVideoDownloader && existingBtn) {
                existingBtn.remove();
            }

            // Unwrap t.co links & bind long-press sheet
            const links = article.querySelectorAll('a[href^="http"]');
            for (const a of links) {
                if (cfg.unwrapTcoLinks && a.href.startsWith("https://t.co/") && !a.dataset.twUnwrapped) {
                    const expanded = window.__TW_CACHE__?.getExpandedUrl(a.href);
                    if (expanded) {
                        a.href = expanded;
                        a.dataset.twUnwrapped = "true";
                    }
                }
                const isExternal = !/^https?:\/\/([^/]+\.)?(x|twitter)\.com\//i.test(a.href) && !a.href.startsWith("https://t.co/");
                if (isExternal && !a.dataset.twLinkBound) {
                    a.dataset.twLinkBound = "true";
                    let lpTimer = null;
                    let lpTriggered = false;
                    a.addEventListener("touchstart", () => {
                        lpTriggered = false;
                        lpTimer = setTimeout(() => {
                            lpTriggered = true;
                            window.TwitterBridge?.showLinkActions(a.href);
                        }, 550);
                    }, { passive: true });
                    const clearLp = () => { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } };
                    a.addEventListener("touchend", clearLp, { passive: true });
                    a.addEventListener("touchmove", clearLp, { passive: true });
                    a.addEventListener("touchcancel", clearLp, { passive: true });
                    a.addEventListener("click", (e) => {
                        if (lpTriggered) {
                            e.preventDefault();
                            e.stopPropagation();
                            lpTriggered = false;
                        }
                    }, true);
                    a.addEventListener("contextmenu", (e) => {
                        e.preventDefault();
                        window.TwitterBridge?.showLinkActions(a.href);
                    });
                }
            }
        }
    }

    function upgradeLightboxImages(cfg) {
        if (!cfg.forceOrigQualityImages) return;
        const imgs = document.querySelectorAll('[aria-labelledby="modal-header"] img[src*="pbs.twimg.com/media"], [data-testid="swipeToDismiss"] img[src*="pbs.twimg.com/media"], [role="dialog"] img[src*="pbs.twimg.com/media"]');
        for (const img of imgs) {
            if (!img.src.includes("name=orig")) {
                img.src = img.src.includes("name=")
                    ? img.src.replace(/name=[^&]+/, "name=orig")
                    : img.src + (img.src.includes("?") ? "&name=orig" : "?name=orig");
            }
        }
    }

    function enforceVideoAutoplayPause(cfg) {
        if (!cfg.disableVideoAutoplay) return;
        const videos = document.querySelectorAll("video");
        for (const v of videos) {
            if (!v.dataset.twAutoplayGuarded) {
                v.dataset.twAutoplayGuarded = "true";
                v.autoplay = false;
                const player = v.closest('[data-testid="videoPlayer"], [data-testid="gifPlayer"]') || v;
                player.addEventListener("pointerdown", () => { v.dataset.twUserPlayed = "true"; }, { passive: true });
                v.addEventListener("play", () => {
                    if (!v.dataset.twUserPlayed) {
                        v.pause();
                    }
                });
            }
            if (!v.dataset.twUserPlayed && !v.paused) {
                v.pause();
            }
        }
    }

    function runDomPass() {
        const cfg = getCfg();
        ensureStyleTag();
        syncDatasetFlags(cfg);
        updateBrandLogos(cfg.classicTwitterBranding !== false);
        rewriteUiTerminology(cfg.classicTwitterBranding !== false);
        bindHeaderLongPress();
        injectDrawerModSettingsEntry();
        handleHomeTabs(cfg);
        enhanceTweets(cfg);
        upgradeLightboxImages(cfg);
        enforceVideoAutoplayPause(cfg);
        lastKnownPath = location.pathname;
    }

    let rafPending = false;
    function schedulePass() {
        if (rafPending) return;
        rafPending = true;
        requestAnimationFrame(() => {
            rafPending = false;
            runDomPass();
        });
    }

    const observer = new MutationObserver(schedulePass);
    observer.observe(document, { childList: true, subtree: true });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", runDomPass);
    } else {
        runDomPass();
    }
})();
