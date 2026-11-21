(() => {
    if (window.__TW_THEME_INSTALLED__) return;
    window.__TW_THEME_INSTALLED__ = true;

    const getCfg = () => window.__TWITTER_CONFIG__ || {};
    const COLOR_SCHEME_RE = /\(\s*prefers-color-scheme\s*:\s*(dark|light)\s*\)/i;
    const origMatchMedia = typeof window.matchMedia === "function" ? window.matchMedia.bind(window) : null;
    const schemeNotifiers = new Set();

    function isEffectiveDarkScheme() {
        const mode = String(getCfg().themeMode || "AUTO").toUpperCase();
        if (mode === "LIGHT") return false;
        if (mode === "DIM" || mode === "LIGHTS_OUT") return true;
        return typeof window.__TWITTER_SYSTEM_DARK__ === "boolean"
            ? window.__TWITTER_SYSTEM_DARK__
            : Boolean(origMatchMedia?.("(prefers-color-scheme: dark)")?.matches);
    }

    if (origMatchMedia) {
        window.matchMedia = function (query) {
            const m = COLOR_SCHEME_RE.exec(String(query || ""));
            if (!m) return origMatchMedia(query);
            const wantDark = m[1].toLowerCase() === "dark";
            const base = origMatchMedia(query);
            const calc = () => (wantDark ? isEffectiveDarkScheme() : !isEffectiveDarkScheme());
            let lastVal = calc(), onchangeFn = null;
            const subs = new Set();
            const syncSub = () => (subs.size > 0 || onchangeFn ? schemeNotifiers.add(notify) : schemeNotifiers.delete(notify));
            const notify = () => {
                const next = calc();
                if (next === lastVal) return;
                lastVal = next;
                const ev = typeof MediaQueryListEvent === "function"
                    ? new MediaQueryListEvent("change", { matches: next, media: base.media })
                    : { matches: next, media: base.media, type: "change" };
                if (typeof onchangeFn === "function") try { onchangeFn.call(proxy, ev); } catch (_) {}
                for (const fn of [...subs]) {
                    try { if (typeof fn === "function") fn.call(proxy, ev); else fn?.handleEvent?.(ev); } catch (_) {}
                }
            };
            const proxy = {
                get media() { return base.media; },
                get matches() { return calc(); },
                get onchange() { return onchangeFn; },
                set onchange(fn) { onchangeFn = typeof fn === "function" ? fn : null; syncSub(); },
                addEventListener(type, fn) {
                    if (type === "change" && (typeof fn === "function" || typeof fn?.handleEvent === "function")) {
                        subs.add(fn); syncSub();
                    }
                },
                removeEventListener(type, fn) { subs.delete(fn); syncSub(); },
                addListener(fn) {
                    if (typeof fn === "function" || typeof fn?.handleEvent === "function") {
                        subs.add(fn); syncSub();
                    }
                },
                removeListener(fn) { subs.delete(fn); syncSub(); },
                dispatchEvent(ev) {
                    if (ev?.type === "change") {
                        for (const fn of [...subs]) {
                            if (typeof fn === "function") fn.call(proxy, ev);
                            else fn?.handleEvent?.(ev);
                        }
                    }
                    return true;
                }
            };
            return proxy;
        };
        window.__twNotifyColorSchemeChange = () => {
            for (const notify of [...schemeNotifiers]) notify();
        };
    }

    let lastSurfaceSheetSig = "";

    function classifyColorToken(raw, mode) {
        if (!raw) return null;
        const m = raw.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)$/i);
        if (!m) return null;
        const r = +m[1], g = +m[2], b = +m[3], a = m[4] === undefined ? 1 : parseFloat(m[4]);
        if (a === 0) return null;
        if (mode === "dim") {
            if (r === 0 && g === 0 && b === 0) return a >= 0.99 ? "base" : (a >= 0.45 && a <= 0.85 ? "glass" : null);
            if (a < 0.99) return null;
            if ((r === 22 && g === 24 && b === 28) || (r === 24 && g === 24 && b === 24) || (r === 29 && g === 31 && b === 35)) return "elev";
            if ((r === 32 && g === 35 && b === 39) || (r === 39 && g === 44 && b === 48)) return "hover";
            if ((r === 47 && g === 51 && b === 54) || (r === 51 && g === 54 && b === 57)) return "border";
        } else if (mode === "black") {
            if (r === 21 && g === 32 && b === 43) return a >= 0.99 ? "base" : (a >= 0.45 && a <= 0.88 ? "glass" : null);
            if (a < 0.99) return null;
            if ((r === 30 && g === 39 && b === 50) || (r === 25 && g === 39 && b === 52)) return "elev";
            if (r === 39 && g === 51 && b === 64) return "hover";
            if (r === 56 && g === 68 && b === 77) return "border";
        }
        return null;
    }

    function syncSurfaceStyleSheet(overrideMode) {
        if (overrideMode !== "dim" && overrideMode !== "black") return;
        const mount = document.head || document.documentElement;
        if (!mount) return;
        const sheets = document.styleSheets;
        let rnLen = 0;
        for (const s of sheets) {
            if (s.ownerNode?.dataset?.twOwn) continue;
            try { rnLen += s.cssRules.length; } catch (_) { rnLen += 1; }
        }
        const sig = `${overrideMode}:${sheets.length}:${rnLen}`;
        if (sig === lastSurfaceSheetSig) return;
        lastSurfaceSheetSig = sig;
        const prefix = `html[data-tw-surface-override="${overrideMode}"]`;
        const buckets = { base: [], glass: [], elev: [], hover: [], border: [] };
        const pushSel = (bucket, selText) => {
            for (const part of selText.split(",")) {
                const s = part.trim();
                if (!s || s.includes("tw-mod-")) continue;
                buckets[bucket].push(/^(html|:root)\b/i.test(s) ? prefix : `${prefix} ${s}`);
            }
        };
        for (const sheet of sheets) {
            if (sheet.ownerNode?.dataset?.twOwn) continue;
            let rules;
            try { rules = sheet.cssRules; } catch (_) { continue; }
            for (const rule of rules) {
                if (rule.type !== 1 || !rule.selectorText || !rule.style) continue;
                const bgTok = classifyColorToken(rule.style.backgroundColor, overrideMode);
                if (bgTok && bgTok !== "border") pushSel(bgTok, rule.selectorText);
                for (const prop of ["borderColor", "borderTopColor", "borderBottomColor", "borderLeftColor", "borderRightColor"]) {
                    if (classifyColorToken(rule.style[prop], overrideMode) === "border") {
                        pushSel("border", rule.selectorText);
                        break;
                    }
                }
            }
        }
        const pal = overrideMode === "dim"
            ? { base: "#15202B", glass: "rgba(21, 32, 43, 0.78)", elev: "#1E2732", hover: "#273340", border: "#38444D" }
            : { base: "#000000", glass: "rgba(0, 0, 0, 0.65)", elev: "#16181C", hover: "#202327", border: "#2F3336" };
        const css = [
            buckets.base.length ? `${buckets.base.join(",")} { background-color: ${pal.base} !important; }` : "",
            buckets.glass.length ? `${buckets.glass.join(",")} { background-color: ${pal.glass} !important; }` : "",
            buckets.elev.length ? `${buckets.elev.join(",")} { background-color: ${pal.elev} !important; }` : "",
            buckets.hover.length ? `${buckets.hover.join(",")} { background-color: ${pal.hover} !important; }` : "",
            buckets.border.length ? `${buckets.border.join(",")} { border-color: ${pal.border} !important; }` : ""
        ].filter(Boolean).join("\n");
        let styleEl = document.getElementById("tw-mod-surface-style");
        if (!styleEl) {
            styleEl = document.createElement("style");
            styleEl.id = "tw-mod-surface-style";
            styleEl.dataset.twOwn = "1";
            mount.appendChild(styleEl);
        }
        if (styleEl.textContent !== css) styleEl.textContent = css;
    }

    const THEME_MODES = [{ id: "AUTO", label: "Auto" }, { id: "LIGHT", label: "Light" }, { id: "DIM", label: "Dim" }, { id: "LIGHTS_OUT", label: "Lights Out" }];
    const DARK_VARIANTS = [{ id: "DIM", label: "Dim" }, { id: "LIGHTS_OUT", label: "Lights Out" }];

    function updateThemeBarSelection(wrap, activeMode, activeVariant) {
        const mode = String(activeMode || "AUTO").toUpperCase();
        const variant = String(activeVariant || "LIGHTS_OUT").toUpperCase();
        wrap.dataset.autoActive = mode === "AUTO" ? "true" : "false";
        for (const btn of wrap.querySelectorAll("[data-tw-theme-mode]")) btn.setAttribute("aria-pressed", btn.dataset.twThemeMode === mode ? "true" : "false");
        for (const btn of wrap.querySelectorAll("[data-tw-dark-variant]")) btn.setAttribute("aria-pressed", btn.dataset.twDarkVariant === variant ? "true" : "false");
    }

    function syncWrapInk(wrap, link) {
        const span = link?.querySelector("span");
        if (!span) return;
        const cs = getComputedStyle(span);
        if (cs.color) wrap.style.color = cs.color;
        if (cs.fontFamily) wrap.style.fontFamily = cs.fontFamily;
    }

    function findDrawerTopColumn(drawerRoot, settingsLink) {
        const vw = window.innerWidth || 400;
        let el = settingsLink, panel = null;
        while (el && el !== drawerRoot) {
            const r = el.getBoundingClientRect();
            if (r.width >= 180 && r.width < vw * 0.96 && r.height > 150) panel = el;
            el = el.parentElement;
        }
        if (!panel) {
            let child = settingsLink;
            while (child && child.parentElement && child.parentElement !== drawerRoot) child = child.parentElement;
            panel = child?.parentElement === drawerRoot ? child : drawerRoot;
        }
        let col = panel;
        while (col && col.children.length === 1 && col.firstElementChild?.tagName === "DIV") col = col.firstElementChild;
        return col;
    }

    function syncDrawerThemeBars(cfg) {
        const mode = String(cfg?.themeMode || "AUTO").toUpperCase(), variant = String(cfg?.autoDarkVariant || "LIGHTS_OUT").toUpperCase();
        for (const wrap of document.querySelectorAll('[data-tw-theme-bar="true"]')) {
            updateThemeBarSelection(wrap, mode, variant);
            syncWrapInk(wrap, wrap.closest('[data-testid="Drawer"], [role="dialog"]')?.querySelector('a[href="/settings"], a[href="/settings/account"]'));
        }
        for (const link of document.querySelectorAll('a[href="/settings"], a[href="/settings/account"]')) {
            if (link.closest('[data-testid="primaryColumn"]')) continue;
            const drawerRoot = link.closest('[data-testid="Drawer"], [role="dialog"]');
            if (!drawerRoot || drawerRoot.querySelector('[data-tw-theme-bar="true"]')) continue;
            const col = findDrawerTopColumn(drawerRoot, link);
            if (!col) continue;
            const wrap = document.createElement("div");
            wrap.className = "tw-mod-theme-wrap";
            wrap.dataset.twOwn = "1";
            wrap.setAttribute("data-tw-theme-bar", "true");
            syncWrapInk(wrap, link);
            const bar = document.createElement("div");
            bar.className = "tw-mod-theme-bar";
            bar.setAttribute("role", "group");
            bar.setAttribute("aria-label", "Appearance theme");
            for (const opt of THEME_MODES) {
                const btn = document.createElement("button");
                btn.type = "button";
                btn.className = "tw-mod-theme-btn";
                btn.dataset.twOwn = "1";
                btn.dataset.twThemeMode = opt.id;
                btn.innerHTML = `<span class="tw-mod-theme-dot" data-mode="${opt.id}"></span><span>${opt.label}</span>`;
                btn.addEventListener("click", (e) => {
                    e.preventDefault(); e.stopPropagation();
                    getCfg().themeMode = opt.id;
                    updateThemeBarSelection(wrap, opt.id, getCfg().autoDarkVariant);
                    window.TwitterBridge?.setThemeMode?.(opt.id);
                });
                bar.appendChild(btn);
            }
            const sub = document.createElement("div");
            sub.className = "tw-mod-dark-subrow";
            sub.innerHTML = '<span class="tw-mod-dark-label">Dark style</span>';
            const pills = document.createElement("div");
            pills.className = "tw-mod-dark-pills";
            for (const opt of DARK_VARIANTS) {
                const btn = document.createElement("button");
                btn.type = "button";
                btn.className = "tw-mod-dark-chip";
                btn.dataset.twOwn = "1";
                btn.dataset.twDarkVariant = opt.id;
                btn.innerHTML = `<span class="tw-mod-theme-dot" data-mode="${opt.id}"></span><span>${opt.label}</span>`;
                btn.addEventListener("click", (e) => {
                    e.preventDefault(); e.stopPropagation();
                    getCfg().autoDarkVariant = opt.id;
                    updateThemeBarSelection(wrap, getCfg().themeMode, opt.id);
                    window.TwitterBridge?.setAutoDarkVariant?.(opt.id);
                });
                pills.appendChild(btn);
            }
            sub.appendChild(pills);
            wrap.append(bar, sub);
            updateThemeBarSelection(wrap, mode, variant);
            const firstChild = col.firstElementChild;
            const isTitleBar = firstChild?.querySelector('[aria-label="Close"], [data-testid="app-bar-close"]') && !firstChild.querySelector('a[href]');
            if (isTitleBar) firstChild.insertAdjacentElement("afterend", wrap);
            else col.prepend(wrap);
        }
    }

    window.__TW_THEME__ = { syncSurfaceStyleSheet, syncDrawerThemeBars };
})();
