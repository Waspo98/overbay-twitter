(() => {
    if (window.__TW_INTERCEPTOR_INSTALLED__) return;
    window.__TW_INTERCEPTOR_INSTALLED__ = true;

    const MAX_VIDEOS = 600;
    const MAX_LINKS = 1200;
    const MAX_USERS = 1200;

    const videoCache = new Map();
    const linkCache = new Map();
    const userCache = new Map();

    function lruSet(map, key, value, max) {
        if (!key || value === undefined || value === null) return;
        if (map.has(key)) map.delete(key);
        map.set(key, value);
        if (map.size > max) {
            const oldest = map.keys().next().value;
            if (oldest !== undefined) map.delete(oldest);
        }
    }

    window.__TW_CACHE__ = {
        getVideoUrl: (tweetId) => videoCache.get(String(tweetId)) || null,
        getExpandedUrl: (tcoUrl) => linkCache.get(String(tcoUrl)) || null,
        isPaidBlueOnly: (handle) => {
            if (!handle) return false;
            const info = userCache.get(String(handle).replace(/^@/, "").toLowerCase());
            return Boolean(info && info.blue && !info.affiliate);
        }
    };

    function getCfg() {
        return window.__TWITTER_CONFIG__ || {
            blockAds: true, removeGrok: true, stripTimelineCarousels: true,
            demoteBlueCheckReplies: false, disableAutoRefreshOnResume: true,
            hideRetweets: false, hideQuoteTweets: false, unwrapTcoLinks: true, mutedPatterns: []
        };
    }

    let cachedMuteKey = "";
    let compiledMutes = [];

    function getCompiledMutes(cfg) {
        const list = Array.isArray(cfg.mutedPatterns) ? cfg.mutedPatterns : [];
        const key = JSON.stringify(list);
        if (key === cachedMuteKey) return compiledMutes;
        cachedMuteKey = key;
        compiledMutes = list.map((raw) => String(raw || "").trim()).filter(Boolean).map((raw) => {
            if (raw.startsWith("/") && raw.lastIndexOf("/") > 0) {
                const idx = raw.lastIndexOf("/");
                const flags = raw.slice(idx + 1).replace(/g/g, "") || "i";
                try { return { regex: new RegExp(raw.slice(1, idx), flags) }; } catch (_) {}
            }
            return { substr: raw.toLowerCase() };
        });
        return compiledMutes;
    }

    function cleanUrl(rawUrl) {
        try {
            const u = new URL(rawUrl);
            ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "mc_cid", "mc_eid", "igshid"]
                .forEach((p) => u.searchParams.delete(p));
            return u.toString();
        } catch (_) { return rawUrl; }
    }

    function unwrapTweet(raw) {
        if (!raw || typeof raw !== "object") return null;
        if (raw.__typename === "TweetWithVisibilityResults" && raw.tweet) return raw.tweet;
        if (raw.tweet && raw.tweet.legacy) return raw.tweet;
        return raw.legacy || raw.rest_id ? raw : null;
    }

    function extractUser(tweet) {
        const res = tweet?.core?.user_results?.result;
        if (!res || typeof res !== "object") return null;
        return res.__typename === "User" || res.legacy || res.rest_id ? res : null;
    }

    function indexTweetTree(raw) {
        const tweet = unwrapTweet(raw);
        if (!tweet) return;

        const tweetId = tweet.rest_id || tweet.legacy?.id_str;
        const user = extractUser(tweet);
        if (user) {
            const screenName = (user.legacy?.screen_name || user.core?.screen_name || "").toLowerCase();
            if (screenName) {
                lruSet(userCache, screenName, {
                    blue: Boolean(user.is_blue_verified),
                    affiliate: Boolean(user.affiliates_highlighted_label?.label),
                    verifiedType: user.legacy?.verified_type || ""
                }, MAX_USERS);
            }
        }

        const urls = tweet.legacy?.entities?.urls;
        if (Array.isArray(urls)) {
            for (const u of urls) {
                if (u?.url && u?.expanded_url) {
                    lruSet(linkCache, u.url, cleanUrl(u.expanded_url), MAX_LINKS);
                }
            }
        }

        const media = tweet.legacy?.extended_entities?.media || tweet.legacy?.entities?.media;
        if (Array.isArray(media) && tweetId) {
            for (const m of media) {
                if (m?.url && m?.expanded_url) {
                    lruSet(linkCache, m.url, cleanUrl(m.expanded_url), MAX_LINKS);
                }
                const variants = m?.video_info?.variants;
                if (Array.isArray(variants) && variants.length > 0 && !videoCache.has(String(tweetId))) {
                    const mp4s = variants
                        .filter((v) => v && v.content_type === "video/mp4" && v.url)
                        .sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
                    if (mp4s.length > 0) {
                        lruSet(videoCache, String(tweetId), mp4s[0].url, MAX_VIDEOS);
                    }
                }
            }
        }

        if (tweet.legacy?.retweeted_status_result?.result) indexTweetTree(tweet.legacy.retweeted_status_result.result);
        if (tweet.quoted_status_result?.result) indexTweetTree(tweet.quoted_status_result.result);
    }

    function collectSearchableText(raw, out = []) {
        const tweet = unwrapTweet(raw);
        if (!tweet) return out;
        if (tweet.legacy?.full_text) out.push(tweet.legacy.full_text);
        const noteText = tweet.note_tweet?.note_tweet_results?.result?.text;
        if (noteText) out.push(noteText);
        const user = extractUser(tweet);
        const handle = user?.legacy?.screen_name || user?.core?.screen_name;
        if (handle) out.push("@" + handle);
        const urls = tweet.legacy?.entities?.urls;
        if (Array.isArray(urls)) {
            for (const u of urls) {
                if (u?.expanded_url) out.push(u.expanded_url);
            }
        }
        if (tweet.legacy?.retweeted_status_result?.result) {
            collectSearchableText(tweet.legacy.retweeted_status_result.result, out);
        }
        if (tweet.quoted_status_result?.result) {
            collectSearchableText(tweet.quoted_status_result.result, out);
        }
        return out;
    }

    function matchesMuted(raw, mutes) {
        if (!mutes || mutes.length === 0) return false;
        const haystack = collectSearchableText(raw).join("\n");
        if (!haystack) return false;
        const lower = haystack.toLowerCase();
        for (const m of mutes) {
            if (m.regex && m.regex.test(haystack)) return true;
            if (m.substr && lower.includes(m.substr)) return true;
        }
        return false;
    }

    function hasPromotedMeta(node, depth = 0) {
        if (!node || typeof node !== "object" || depth > 5) return false;
        if (node.promotedMetadata || node.adMetadata || node.ad_metadata) return true;
        for (const key of ["itemContent", "content", "item", "clientEventInfo"]) {
            if (node[key] && hasPromotedMeta(node[key], depth + 1)) return true;
        }
        return false;
    }

    function extractTweetFromEntryOrItem(node) {
        const itemContent = node?.content?.itemContent || node?.item?.itemContent || node?.itemContent;
        const raw = itemContent?.tweet_results?.result;
        return raw ? unwrapTweet(raw) : null;
    }

    function isCarouselEntry(entryId, node) {
        const id = String(entryId || "").toLowerCase();
        const prefixes = [
            "who-to-follow-", "who-to-subscribe-", "communities-to-join-",
            "topics-to-follow-", "creator-subscriptions-", "inline-prompt-",
            "relevance-prompt-", "list-to-follow-", "connect-", "suggested-"
        ];
        if (prefixes.some((p) => id.startsWith(p))) return true;
        const comp = String(node?.content?.clientEventInfo?.component || node?.item?.clientEventInfo?.component || "").toLowerCase();
        return comp.includes("who_to_follow") || comp.includes("communities") || comp.includes("suggest_topics") || comp.includes("creator_sub");
    }

    function isGrokEntry(entryId, node) {
        const id = String(entryId || "").toLowerCase();
        if (id.startsWith("grok-") || id.includes("-grok-")) return true;
        const itemType = String(node?.content?.itemContent?.itemType || node?.item?.itemContent?.itemType || "").toLowerCase();
        const comp = String(node?.content?.clientEventInfo?.component || "").toLowerCase();
        return itemType.includes("grok") || comp.includes("grok");
    }

    function findFocalInfo(instructions, url) {
        if (!url.includes("TweetDetail") || !Array.isArray(instructions)) return null;
        const m = url.match(/focalTweetId(?:%22|")[:%22"]+([0-9]+)/i);
        const focalId = m ? m[1] : null;
        for (const inst of instructions) {
            for (const entry of inst?.entries || []) {
                const t = extractTweetFromEntryOrItem(entry);
                if (t && (!focalId || String(t.rest_id) === focalId)) {
                    const user = extractUser(t);
                    return { focalId: String(t.rest_id || ""), opUserId: String(user?.rest_id || "") };
                }
            }
        }
        return null;
    }

    function shouldDropItem(node, entryId, cfg, mutes, focalInfo) {
        const idLower = String(entryId || "").toLowerCase();
        if (idLower.startsWith("cursor-")) return false;

        if (cfg.blockAds) {
            if (idLower.startsWith("promoted-") || idLower.startsWith("promotedtweet-") || idLower.startsWith("promotedtrend-")) {
                return true;
            }
            if (hasPromotedMeta(node)) return true;
        }
        if (cfg.stripTimelineCarousels && isCarouselEntry(idLower, node)) return true;
        if (cfg.removeGrok && isGrokEntry(idLower, node)) return true;

        const tweet = extractTweetFromEntryOrItem(node);
        if (!tweet) return false;

        indexTweetTree(tweet);

        if (cfg.hideRetweets && tweet.legacy?.retweeted_status_result) return true;
        if (cfg.hideQuoteTweets && (tweet.legacy?.is_quote_status || tweet.quoted_status_result)) return true;
        if (matchesMuted(tweet, mutes)) return true;

        if (cfg.demoteBlueCheckReplies && focalInfo && String(tweet.rest_id) !== focalInfo.focalId) {
            const user = extractUser(tweet);
            const uid = String(user?.rest_id || "");
            const isOp = focalInfo.opUserId && uid === focalInfo.opUserId;
            const following = Boolean(user?.legacy?.following);
            const isPaidBlue = Boolean(user?.is_blue_verified && !user?.affiliates_highlighted_label?.label);
            if (isPaidBlue && !isOp && !following) return true;
        }
        return false;
    }

    function filterInstructionsList(instructions, url, cfg, mutes) {
        const focalInfo = findFocalInfo(instructions, url);
        for (const inst of instructions) {
            if (!inst || typeof inst !== "object") continue;

            if (Array.isArray(inst.entries)) {
                inst.entries = inst.entries.filter((entry) => {
                    if (!entry) return false;
                    if (shouldDropItem(entry, entry.entryId, cfg, mutes, focalInfo)) return false;

                    const modItems = entry.content?.items;
                    if (Array.isArray(modItems)) {
                        entry.content.items = modItems.filter((item) => !shouldDropItem(item, item?.entryId, cfg, mutes, focalInfo));
                        if (entry.content.items.length === 0) return false;
                    }
                    return true;
                });
            }

            if (Array.isArray(inst.moduleItems)) {
                inst.moduleItems = inst.moduleItems.filter((item) => !shouldDropItem(item, item?.entryId, cfg, mutes, focalInfo));
            }

            if (inst.entry && shouldDropItem(inst.entry, inst.entry.entryId, cfg, mutes, focalInfo)) {
                delete inst.entry;
            }
        }
    }

    function walkAndProcess(node, url, cfg, mutes, depth = 0) {
        if (!node || typeof node !== "object" || depth > 14) return;
        if (Array.isArray(node)) {
            for (const item of node) walkAndProcess(item, url, cfg, mutes, depth + 1);
            return;
        }
        if (node.legacy && (node.rest_id || node.core)) {
            indexTweetTree(node);
        }
        if (Array.isArray(node.instructions)) {
            filterInstructionsList(node.instructions, url, cfg, mutes);
        }
        for (const key of Object.keys(node)) {
            const val = node[key];
            if (val && typeof val === "object") {
                walkAndProcess(val, url, cfg, mutes, depth + 1);
            }
        }
    }

    function isTargetApiUrl(url) {
        return typeof url === "string" && /\/i\/api\/(graphql|2)\//i.test(url);
    }

    function processPayloadObject(url, obj) {
        if (!obj || typeof obj !== "object") return obj;
        const cfg = getCfg();
        const mutes = getCompiledMutes(cfg);
        walkAndProcess(obj, url || "", cfg, mutes, 0);
        return obj;
    }

    function processPayloadText(url, text) {
        if (!text || typeof text !== "string") return text;
        const trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return text;
        const parsed = JSON.parse(trimmed);
        processPayloadObject(url, parsed);
        return JSON.stringify(parsed);
    }

    // Hook window.fetch
    const origFetch = window.fetch;
    if (typeof origFetch === "function") {
        window.fetch = async function (...args) {
            const response = await origFetch.apply(this, args);
            try {
                const reqUrl = typeof args[0] === "string" ? args[0] : (args[0]?.url || response.url || "");
                if (!isTargetApiUrl(reqUrl) || !response.ok) return response;
                const ct = response.headers.get("content-type") || "";
                if (!ct.includes("json") && !reqUrl.includes("/graphql/")) return response;

                const cloned = response.clone();
                const rawText = await cloned.text();
                const modified = processPayloadText(reqUrl, rawText);
                return new Response(modified, {
                    status: response.status,
                    statusText: response.statusText,
                    headers: response.headers
                });
            } catch (_) {
                return response;
            }
        };
    }

    // Hook XMLHttpRequest.prototype getters safely before any listener fires
    const xhrProto = XMLHttpRequest.prototype;
    const origOpen = xhrProto.open;
    const origSend = xhrProto.send;
    const descResponseText = Object.getOwnPropertyDescriptor(xhrProto, "responseText");
    const descResponse = Object.getOwnPropertyDescriptor(xhrProto, "response");

    xhrProto.open = function (method, url) {
        this.__twUrl = typeof url === "string" ? url : String(url || "");
        this.__twCachedKey = null;
        this.__twCachedText = undefined;
        this.__twCachedObj = undefined;
        return origOpen.apply(this, arguments);
    };

    xhrProto.send = function () {
        this.__twCachedKey = null;
        this.__twCachedText = undefined;
        this.__twCachedObj = undefined;
        return origSend.apply(this, arguments);
    };

    function getFilteredXhrText(xhr, raw) {
        if (xhr.__twCachedKey === xhr.__twUrl && xhr.__twCachedText !== undefined) {
            return xhr.__twCachedText;
        }
        try {
            const out = processPayloadText(xhr.__twUrl, raw);
            xhr.__twCachedKey = xhr.__twUrl;
            xhr.__twCachedText = out;
            return out;
        } catch (_) {
            return raw;
        }
    }

    function shouldFilterXhr(xhr) {
        return xhr.readyState === 4 && isTargetApiUrl(xhr.__twUrl) && xhr.status >= 200 && xhr.status < 300;
    }

    if (descResponseText?.get) {
        Object.defineProperty(xhrProto, "responseText", {
            configurable: true,
            enumerable: descResponseText.enumerable,
            get: function () {
                const raw = descResponseText.get.call(this);
                return shouldFilterXhr(this) ? getFilteredXhrText(this, raw) : raw;
            }
        });
    }

    if (descResponse?.get) {
        Object.defineProperty(xhrProto, "response", {
            configurable: true,
            enumerable: descResponse.enumerable,
            get: function () {
                const raw = descResponse.get.call(this);
                if (!shouldFilterXhr(this)) return raw;
                const rType = this.responseType;
                if (rType === "" || rType === "text") return getFilteredXhrText(this, raw);
                if (rType === "json" && raw && typeof raw === "object") {
                    if (this.__twCachedKey === this.__twUrl && this.__twCachedObj !== undefined) {
                        return this.__twCachedObj;
                    }
                    try {
                        const cloned = typeof structuredClone === "function"
                            ? structuredClone(raw)
                            : JSON.parse(JSON.stringify(raw));
                        this.__twCachedKey = this.__twUrl;
                        this.__twCachedObj = processPayloadObject(this.__twUrl, cloned);
                        return this.__twCachedObj;
                    } catch (_) {
                        return raw;
                    }
                }
                return raw;
            }
        });
    }

    // Prevent timeline scroll jump on app resume
    function wrapEventTarget(target) {
        if (!target || typeof target.addEventListener !== "function") return;
        const origAdd = target.addEventListener;
        target.addEventListener = function (type, listener, options) {
            if ((type === "visibilitychange" || type === "focus") && typeof listener === "function") {
                const wrapped = function (ev) {
                    if (getCfg().disableAutoRefreshOnResume) {
                        if (type === "focus") return;
                        if (type === "visibilitychange" && document.visibilityState === "visible") return;
                    }
                    return listener.apply(this, arguments);
                };
                return origAdd.call(this, type, wrapped, options);
            }
            return origAdd.apply(this, arguments);
        };
    }

    wrapEventTarget(document);
    wrapEventTarget(window);
})();
