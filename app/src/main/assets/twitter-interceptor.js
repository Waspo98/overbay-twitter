(() => {
    if (window.__TW_INTERCEPTOR_INSTALLED__) return;
    window.__TW_INTERCEPTOR_INSTALLED__ = true;

    const MAX_VIDEOS = 600, MAX_LINKS = 1200, MAX_USERS = 1200, MAX_FOCAL = 100;
    const videoCache = new Map(), videoListCache = new Map(), linkCache = new Map();
    const userCache = new Map(), feedKindCache = new Map(), focalCache = new Map();

    const TRACKING_PARAMS = ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "igsh", "_hsenc", "_hsmi", "vero_id", "mkt_tok"];
    const SKIP_WALK_KEYS = new Set(["legacy", "entities", "extended_entities", "video_info", "sizes", "features", "professional", "affiliates_highlighted_label", "clientEventInfo", "feedbackInfo", "promotedMetadata", "unmention_data", "edit_control", "views", "quick_promote_eligibility"]);
    const CAROUSEL_PREFIXES = ["who-to-follow-", "who-to-subscribe-", "communities-to-join-", "topics-to-follow-", "creator-subscriptions-", "inline-prompt-", "relevance-prompt-", "list-to-follow-", "connect-", "suggested-"];
    const NON_TIMELINE_GQL_RE = /\/i\/api\/graphql\/[^/?]+\/(Favorite|Unfavorite|CreateBookmark|DeleteBookmark|CreateRetweet|DeleteRetweet|DataSaver|BadgeCount|PutClientEducationFlag|SubscribeTo|UnsubscribeFrom|ClientEvent|AccountBalance|ViewerSettings)/i;
    const SKIP_API2_RE = /\/i\/api\/2\/(badge_count|live_pipeline|notifications\/view|dm\/|client_event|onboarding|keyregistry|fleets|hashflags)/i;

    const sessionStats = { ads: 0, clutter: 0 };
    window.__TW_STATS__ = sessionStats;

    function lruSet(map, key, value, max) {
        if (!key || value === undefined || value === null) return;
        if (map.has(key)) map.delete(key);
        map.set(key, value);
        if (map.size > max) { const oldest = map.keys().next().value; if (oldest !== undefined) map.delete(oldest); }
    }

    window.__TW_CACHE__ = {
        getVideoUrl: (tweetId, idx = 0) => {
            const list = videoListCache.get(String(tweetId));
            return (list && list.length > 0) ? (list[idx] || list[0]) : (videoCache.get(String(tweetId)) || null);
        },
        getVideoUrls: (tweetId) => videoListCache.get(String(tweetId)) || (videoCache.has(String(tweetId)) ? [videoCache.get(String(tweetId))] : []),
        getExpandedUrl: (tcoUrl) => linkCache.get(String(tcoUrl)) || null,
        getFeedKind: (name) => feedKindCache.get(String(name || "").trim().toLowerCase()) || null,
        isPaidBlueOnly: (handle) => {
            if (!handle) return false;
            const info = userCache.get(String(handle).replace(/^@/, "").toLowerCase());
            return info ? Boolean(info.blue && !info.affiliate && !info.verifiedType) : null;
        }
    };

    const getCfg = () => window.__TWITTER_CONFIG__ || {
        blockAds: true, removeGrok: true, stripTimelineCarousels: true, demoteBlueCheckReplies: false,
        disableAutoRefreshOnResume: true, hideRetweets: false, hideQuoteTweets: false, unwrapTcoLinks: true, mutedPatterns: []
    };

    let cachedMuteKey = "", compiledMutes = [];
    function getCompiledMutes(cfg) {
        const list = Array.isArray(cfg.mutedPatterns) ? cfg.mutedPatterns : [];
        if (list.length === 0) return [];
        const key = list.join("\u0000");
        if (key === cachedMuteKey) return compiledMutes;
        cachedMuteKey = key;
        compiledMutes = list.map((raw) => String(raw || "").trim()).filter(Boolean).map((raw) => {
            if (raw.startsWith("/") && raw.lastIndexOf("/") > 0) {
                const idx = raw.lastIndexOf("/"), flags = raw.slice(idx + 1).replace(/[gy]/g, "") || "i";
                try { return { regex: new RegExp(raw.slice(1, idx), flags) }; } catch (_) { return null; }
            }
            return { substr: raw.toLowerCase() };
        }).filter(Boolean);
        return compiledMutes;
    }

    function cleanUrl(rawUrl) {
        if (typeof rawUrl !== "string" || !rawUrl.includes("?")) return rawUrl;
        try {
            const u = new URL(rawUrl);
            let changed = false;
            for (const p of TRACKING_PARAMS) if (u.searchParams.has(p)) { u.searchParams.delete(p); changed = true; }
            return changed ? u.toString() : rawUrl;
        } catch (_) { return rawUrl; }
    }

    function unwrapTweet(raw) {
        if (!raw || typeof raw !== "object") return null;
        if (raw.__typename === "TweetWithVisibilityResults" && raw.tweet) return raw.tweet;
        if (raw.tweet && raw.tweet.legacy) return raw.tweet;
        return raw.legacy || raw.rest_id ? raw : null;
    }

    const extractUser = (t) => {
        const res = t?.core?.user_results?.result;
        return (res && typeof res === "object" && (res.__typename === "User" || res.legacy || res.rest_id)) ? res : null;
    };
    const getVerifiedType = (u) => u?.legacy?.verified_type || u?.verification?.verified_type || "";
    const isFollowingUser = (u) => Boolean(u?.legacy?.following || u?.relationship_perspectives?.following);
    const getTweetUrls = (t) => {
        const l = t.legacy?.entities?.urls || [], n = t.note_tweet?.note_tweet_results?.result?.entity_set?.urls || [];
        return n.length ? [...l, ...n] : l;
    };

    function indexUserNode(user) {
        if (!user || typeof user !== "object") return;
        const screenName = (user.legacy?.screen_name || user.core?.screen_name || "").toLowerCase();
        if (screenName) {
            lruSet(userCache, screenName, {
                blue: Boolean(user.is_blue_verified),
                affiliate: Boolean(user.affiliates_highlighted_label?.label),
                verifiedType: getVerifiedType(user)
            }, MAX_USERS);
        }
        for (const list of [user.legacy?.entities?.description?.urls, user.legacy?.entities?.url?.urls]) {
            if (Array.isArray(list)) for (const u of list) if (u?.url && u?.expanded_url) lruSet(linkCache, u.url, cleanUrl(u.expanded_url), MAX_LINKS);
        }
    }

    function bestMp4FromVariants(variants) {
        if (!Array.isArray(variants) || variants.length === 0) return null;
        let bestUrl = null, bestBitrate = -1;
        for (const v of variants) {
            if (v && v.content_type === "video/mp4" && v.url) {
                const rate = typeof v.bitrate === "number" ? v.bitrate : 0;
                if (rate > bestBitrate) { bestBitrate = rate; bestUrl = v.url; }
            }
        }
        return bestUrl;
    }

    function extractCardVideoUrl(tweet) {
        const bindings = tweet.card?.legacy?.binding_values;
        if (!Array.isArray(bindings)) return null;
        for (const b of bindings) {
            const raw = b?.value?.string_value;
            if (b?.key === "unified_card" && typeof raw === "string" && raw.includes("video_info")) {
                try {
                    const entities = JSON.parse(raw)?.media_entities;
                    if (entities && typeof entities === "object") {
                        for (const k of Object.keys(entities)) {
                            const url = bestMp4FromVariants(entities[k]?.video_info?.variants);
                            if (url) return url;
                        }
                    }
                } catch (_) {}
            }
        }
        return null;
    }

    function indexTweetTree(raw) {
        const tweet = unwrapTweet(raw);
        if (!tweet) return null;
        const tweetId = tweet.rest_id || tweet.legacy?.id_str, user = extractUser(tweet);
        if (user) indexUserNode(user);
        for (const u of getTweetUrls(tweet)) if (u?.url && u?.expanded_url) lruSet(linkCache, u.url, cleanUrl(u.expanded_url), MAX_LINKS);

        const foundVideos = [];
        const media = tweet.legacy?.extended_entities?.media || tweet.legacy?.entities?.media;
        if (Array.isArray(media)) {
            for (const m of media) {
                if (m?.url && m?.expanded_url) lruSet(linkCache, m.url, cleanUrl(m.expanded_url), MAX_LINKS);
                const mp4 = bestMp4FromVariants(m?.video_info?.variants);
                if (mp4) foundVideos.push(mp4);
            }
        }
        if (foundVideos.length === 0) { const cardUrl = extractCardVideoUrl(tweet); if (cardUrl) foundVideos.push(cardUrl); }
        const rtVideo = tweet.legacy?.retweeted_status_result?.result ? indexTweetTree(tweet.legacy.retweeted_status_result.result) : null;
        const qtVideo = tweet.quoted_status_result?.result ? indexTweetTree(tweet.quoted_status_result.result) : null;
        const finalVideo = foundVideos[0] || rtVideo || qtVideo;
        if (tweetId && finalVideo) {
            const idKey = String(tweetId);
            if (!videoCache.has(idKey)) lruSet(videoCache, idKey, finalVideo, MAX_VIDEOS);
            if (foundVideos.length > 0) lruSet(videoListCache, idKey, foundVideos, MAX_VIDEOS);
        }
        return finalVideo;
    }

    function collectSearchableText(raw, out = []) {
        const tweet = unwrapTweet(raw);
        if (!tweet) return out;
        if (tweet.legacy?.full_text) out.push(tweet.legacy.full_text);
        const noteText = tweet.note_tweet?.note_tweet_results?.result?.text;
        if (noteText) out.push(noteText);
        const user = extractUser(tweet), handle = user?.legacy?.screen_name || user?.core?.screen_name;
        if (handle) out.push("@" + handle);
        for (const u of getTweetUrls(tweet)) if (u?.expanded_url) out.push(u.expanded_url);
        if (tweet.legacy?.retweeted_status_result?.result) collectSearchableText(tweet.legacy.retweeted_status_result.result, out);
        if (tweet.quoted_status_result?.result) collectSearchableText(tweet.quoted_status_result.result, out);
        return out;
    }

    function matchesMuted(raw, mutes) {
        if (!mutes || mutes.length === 0) return false;
        const haystack = collectSearchableText(raw).join("\n");
        if (!haystack) return false;
        const lower = haystack.toLowerCase();
        return mutes.some((m) => (m.regex && m.regex.test(haystack)) || (m.substr && lower.includes(m.substr)));
    }

    function hasPromotedMeta(node, depth = 0) {
        if (!node || typeof node !== "object" || depth > 5) return false;
        if (node.promotedMetadata || node.adMetadata || node.ad_metadata || node.tweet_results?.result?.promotedMetadata) return true;
        return ["itemContent", "content", "item", "clientEventInfo"].some((k) => node[k] && hasPromotedMeta(node[k], depth + 1));
    }

    const extractTweetFromEntryOrItem = (node) => unwrapTweet((node?.content?.itemContent || node?.item?.itemContent || node?.itemContent)?.tweet_results?.result);

    function isCarouselEntry(idLower, node) {
        if (CAROUSEL_PREFIXES.some((p) => idLower.startsWith(p))) return true;
        const comp = String(node?.content?.clientEventInfo?.component || node?.item?.clientEventInfo?.component || "").toLowerCase();
        return comp.includes("who_to_follow") || comp.includes("communities") || comp.includes("suggest_topics") || comp.includes("creator_sub");
    }

    function isGrokEntry(idLower, node) {
        if (idLower.startsWith("grok-") || idLower.includes("-grok-")) return true;
        const itemType = String(node?.content?.itemContent?.itemType || node?.item?.itemContent?.itemType || "").toLowerCase();
        return itemType.includes("grok") || String(node?.content?.clientEventInfo?.component || "").toLowerCase().includes("grok");
    }

    function findFocalInfo(instructions, url) {
        if (!url.includes("TweetDetail") || !Array.isArray(instructions)) return null;
        let decoded = url;
        try { decoded = decodeURIComponent(url); } catch (_) {}
        const focalId = decoded.match(/["']?focalTweetId["']?\s*:\s*["']?([0-9]+)/i)?.[1] || null;
        for (const inst of instructions) {
            for (const entry of inst?.entries || []) {
                const t = extractTweetFromEntryOrItem(entry);
                if (!t) continue;
                const tid = String(t.rest_id || "");
                const matches = focalId ? (tid === focalId || entry.entryId === `tweet-${focalId}`) : (entry?.content?.itemContent?.tweetDisplayType === "Tweet");
                if (matches && tid) {
                    const info = { focalId: tid, opUserId: String(extractUser(t)?.rest_id || "") };
                    lruSet(focalCache, tid, info, MAX_FOCAL);
                    return info;
                }
            }
        }
        return focalId ? (focalCache.get(focalId) || null) : null;
    }

    function classifyDropReason(node, entryId, cfg, mutes, focalInfo, isBookmarks) {
        const idLower = String(entryId || "").toLowerCase();
        if (idLower.startsWith("cursor-")) return null;
        if (cfg.blockAds && (idLower.startsWith("promoted-") || idLower.startsWith("promotedtweet-") || idLower.startsWith("promotedtrend-") || hasPromotedMeta(node))) return "ad";
        if (cfg.stripTimelineCarousels && isCarouselEntry(idLower, node)) return "clutter";
        if (cfg.removeGrok && isGrokEntry(idLower, node)) return "clutter";

        const tweet = extractTweetFromEntryOrItem(node);
        if (!tweet) return null;
        if (focalInfo && String(tweet.rest_id) === focalInfo.focalId) { indexTweetTree(tweet); return null; }
        if (!isBookmarks && cfg.hideRetweets && tweet.legacy?.retweeted_status_result) return "clutter";
        if (!isBookmarks && cfg.hideQuoteTweets && (tweet.legacy?.is_quote_status || tweet.quoted_status_result)) return "clutter";
        if (matchesMuted(tweet, mutes)) return "clutter";

        if (cfg.demoteBlueCheckReplies && focalInfo) {
            const user = extractUser(tweet);
            const isOp = focalInfo.opUserId && String(user?.rest_id || "") === focalInfo.opUserId;
            const isPaidBlue = Boolean(user?.is_blue_verified && !user?.affiliates_highlighted_label?.label && !getVerifiedType(user));
            if (isPaidBlue && !isOp && !isFollowingUser(user)) return "clutter";
        }
        indexTweetTree(tweet);
        return null;
    }

    const isCursorEntry = (item) => String(item?.entryId || "").toLowerCase().startsWith("cursor-");
    function recordDrop(reason, state) {
        if (!reason) return false;
        if (reason === "ad") state.adsDelta++; else state.clutterDelta++;
        return true;
    }

    function filterInstructionsList(instructions, url, cfg, mutes, state) {
        let mutated = false;
        const focalInfo = findFocalInfo(instructions, url), isBookmarks = url.includes("Bookmarks");
        for (const inst of instructions) {
            if (!inst || typeof inst !== "object") continue;
            if (Array.isArray(inst.entries)) {
                const beforeLen = inst.entries.length;
                inst.entries = inst.entries.filter((entry) => {
                    if (!entry || recordDrop(classifyDropReason(entry, entry.entryId, cfg, mutes, focalInfo, isBookmarks), state)) return false;
                    const modItems = entry.content?.items;
                    if (Array.isArray(modItems)) {
                        const modBefore = modItems.length, hadNonCursor = modItems.some((i) => !isCursorEntry(i));
                        entry.content.items = modItems.filter((item) => !recordDrop(classifyDropReason(item, item?.entryId, cfg, mutes, focalInfo, isBookmarks), state));
                        if (entry.content.items.length !== modBefore) mutated = true;
                        if (entry.content.items.length === 0 || (hadNonCursor && !entry.content.items.some((i) => !isCursorEntry(i)))) return false;
                    }
                    return true;
                });
                if (inst.entries.length !== beforeLen) mutated = true;
            }
            if (Array.isArray(inst.moduleItems)) {
                const beforeMod = inst.moduleItems.length;
                inst.moduleItems = inst.moduleItems.filter((item) => !recordDrop(classifyDropReason(item, item?.entryId, cfg, mutes, focalInfo, isBookmarks), state));
                if (inst.moduleItems.length !== beforeMod) mutated = true;
            }
            if (inst.entry && recordDrop(classifyDropReason(inst.entry, inst.entry.entryId, cfg, mutes, focalInfo, isBookmarks), state)) {
                delete inst.entry;
                mutated = true;
            }
        }
        return mutated;
    }

    function indexPinnedTimelines(list) {
        for (const item of list) {
            if (!item || typeof item !== "object") continue;
            const key = String(item.list?.name || item.name || item.timeline?.name || item.scribe_config?.name || "").trim().toLowerCase();
            if (key && key !== "for you" && key !== "following") {
                lruSet(feedKindCache, key, (Boolean(item.list) || /List/i.test(String(item.__typename || ""))) ? "list" : "suggested", 80);
            }
        }
    }

    function walkAndProcess(node, url, cfg, mutes, state, depth = 0) {
        if (!node || typeof node !== "object" || depth > 22) return;
        if (Array.isArray(node)) { for (const item of node) walkAndProcess(item, url, cfg, mutes, state, depth + 1); return; }
        const hasTimeline = Boolean(node.instructions || node.timeline || node.timeline_v2 || node.tweets_timeline || node.pinned_timelines);
        if (!hasTimeline && (node.__typename === "Tweet" || (node.legacy && node.core?.user_results))) { indexTweetTree(node); return; }
        if (node.__typename === "User" || (node.legacy?.screen_name && !node.legacy?.full_text && node.rest_id)) { indexUserNode(node); if (!hasTimeline) return; }
        if (Array.isArray(node.pinned_timelines)) indexPinnedTimelines(node.pinned_timelines);
        if (Array.isArray(node.instructions) && filterInstructionsList(node.instructions, url, cfg, mutes, state)) state.mutated = true;
        for (const key of Object.keys(node)) {
            if (key === "instructions" || key === "pinned_timelines" || SKIP_WALK_KEYS.has(key)) continue;
            const val = node[key];
            if (val && typeof val === "object") walkAndProcess(val, url, cfg, mutes, state, depth + 1);
        }
    }

    const isTargetApiUrl = (url) => typeof url === "string" && /\/i\/api\/(graphql|2)\//i.test(url) && !NON_TIMELINE_GQL_RE.test(url) && !SKIP_API2_RE.test(url);

    function processPayloadObject(url, obj) {
        if (!obj || typeof obj !== "object") return { obj, mutated: false };
        const cfg = getCfg(), state = { mutated: false, adsDelta: 0, clutterDelta: 0 };
        walkAndProcess(obj, url || "", cfg, getCompiledMutes(cfg), state, 0);
        if (state.adsDelta > 0 || state.clutterDelta > 0) {
            sessionStats.ads += state.adsDelta;
            sessionStats.clutter += state.clutterDelta;
            window.TwitterBridge?.reportStatsDelta?.(state.adsDelta, state.clutterDelta);
        }
        return { obj, mutated: state.mutated };
    }

    function processPayloadText(url, text) {
        if (!text || typeof text !== "string") return { text, mutated: false };
        const c = text.charCodeAt(0), trimmed = (c === 123 || c === 91) ? text : text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return { text, mutated: false };
        const parsed = JSON.parse(trimmed);
        const { mutated } = processPayloadObject(url, parsed);
        return { text: mutated ? JSON.stringify(parsed) : text, mutated };
    }

    const origFetch = window.fetch;
    if (typeof origFetch === "function") {
        window.fetch = async function (...args) {
            const response = await origFetch.apply(this, args);
            let rawText = null;
            try {
                const reqUrl = typeof args[0] === "string" ? args[0] : (args[0]?.url || response.url || "");
                if (!isTargetApiUrl(reqUrl) || !response.ok || response.status === 204 || response.status === 205) return response;
                const ct = response.headers.get("content-type") || "";
                if (!ct.includes("json") && !reqUrl.includes("/graphql/")) return response;
                rawText = await response.text();
                const { text: outBody } = processPayloadText(reqUrl, rawText);
                const headers = new Headers(response.headers);
                headers.delete("content-length");
                headers.delete("content-encoding");
                const out = new Response(outBody, { status: response.status, statusText: response.statusText, headers });
                if (response.url || reqUrl) try { Object.defineProperty(out, "url", { value: response.url || reqUrl }); } catch (_) {}
                return out;
            } catch (_) {
                const isNullBody = response.status === 204 || response.status === 205 || response.status === 304;
                return rawText !== null
                    ? new Response(isNullBody ? null : rawText, { status: response.status, statusText: response.statusText, headers: response.headers })
                    : response;
            }
        };
    }

    const xhrProto = XMLHttpRequest.prototype;
    const origOpen = xhrProto.open, origSend = xhrProto.send;
    const descResponseText = Object.getOwnPropertyDescriptor(xhrProto, "responseText");
    const descResponse = Object.getOwnPropertyDescriptor(xhrProto, "response");
    const resetXhrCache = (xhr) => { xhr.__twCachedKey = null; xhr.__twCachedText = xhr.__twCachedObj = undefined; };
    xhrProto.open = function (method, url) { this.__twUrl = typeof url === "string" ? url : String(url || ""); resetXhrCache(this); return origOpen.apply(this, arguments); };
    xhrProto.send = function () { resetXhrCache(this); return origSend.apply(this, arguments); };

    const shouldFilterXhr = (xhr) => xhr.readyState === 4 && isTargetApiUrl(xhr.__twUrl) && xhr.status >= 200 && xhr.status < 300;
    function getFilteredXhrText(xhr, raw) {
        if (xhr.__twCachedKey === xhr.__twUrl && xhr.__twCachedText !== undefined) return xhr.__twCachedText;
        try { const { text } = processPayloadText(xhr.__twUrl, raw); xhr.__twCachedKey = xhr.__twUrl; return (xhr.__twCachedText = text); }
        catch (_) { return raw; }
    }

    if (descResponseText?.get) {
        Object.defineProperty(xhrProto, "responseText", {
            configurable: true, enumerable: descResponseText.enumerable,
            get: function () { const raw = descResponseText.get.call(this); return shouldFilterXhr(this) ? getFilteredXhrText(this, raw) : raw; }
        });
    }
    if (descResponse?.get) {
        Object.defineProperty(xhrProto, "response", {
            configurable: true, enumerable: descResponse.enumerable,
            get: function () {
                const raw = descResponse.get.call(this);
                if (!shouldFilterXhr(this)) return raw;
                const rType = this.responseType;
                if (rType === "" || rType === "text") return getFilteredXhrText(this, raw);
                if (rType === "json" && raw && typeof raw === "object") {
                    if (this.__twCachedKey === this.__twUrl && this.__twCachedObj !== undefined) return this.__twCachedObj;
                    try { const { obj } = processPayloadObject(this.__twUrl, raw); this.__twCachedKey = this.__twUrl; return (this.__twCachedObj = obj); }
                    catch (_) { return raw; }
                }
                return raw;
            }
        });
    }

    const wrappedListeners = new WeakMap();
    function wrapEventTarget(target) {
        if (!target || typeof target.addEventListener !== "function") return;
        const origAdd = target.addEventListener, origRemove = target.removeEventListener;
        const isCap = (opts) => Boolean(typeof opts === "boolean" ? opts : opts?.capture);
        target.addEventListener = function (type, listener, options) {
            if ((type !== "visibilitychange" && type !== "focus") || typeof listener !== "function") return origAdd.apply(this, arguments);
            const cap = isCap(options), entries = wrappedListeners.get(listener) || [];
            wrappedListeners.set(listener, entries);
            let match = entries.find((e) => e.target === this && e.type === type && e.cap === cap);
            if (!match) {
                const wrapped = function (ev) {
                    if (getCfg().disableAutoRefreshOnResume) {
                        if (type === "focus" && (!ev || ev.target === window || ev.target === document)) return;
                        if (type === "visibilitychange") return;
                    }
                    return listener.apply(this, arguments);
                };
                match = { target: this, type, cap, wrapped };
                entries.push(match);
            }
            return origAdd.call(this, type, match.wrapped, options);
        };
        target.removeEventListener = function (type, listener, options) {
            const entries = typeof listener === "function" ? wrappedListeners.get(listener) : null, cap = isCap(options);
            const idx = entries ? entries.findIndex((e) => e.target === this && e.type === type && e.cap === cap) : -1;
            if (idx >= 0) return origRemove.call(this, type, entries.splice(idx, 1)[0].wrapped, options);
            return origRemove.apply(this, arguments);
        };
    }

    wrapEventTarget(document);
    wrapEventTarget(window);
})();
