(() => {
    if (window.__TW_DRAWER_INSTALLED__) return;
    window.__TW_DRAWER_INSTALLED__ = true;

    let activeDrawer = null;
    let activeGhostEl = null;

    function clearDrawerExitGhost() {
        if (activeGhostEl) {
            activeGhostEl.remove();
            activeGhostEl = null;
        }
    }

    function getPathFromRoot(root, node) {
        if (!root || !node) return null;
        const path = [];
        for (let cur = node; cur && cur !== root; cur = cur.parentElement) {
            if (!cur.parentElement) return null;
            path.push(Array.prototype.indexOf.call(cur.parentElement.children, cur));
        }
        return path.reverse();
    }

    function resolvePath(root, path) {
        let cur = root;
        for (const idx of path || []) {
            if (!cur || idx < 0 || idx >= cur.children.length) return null;
            cur = cur.children[idx];
        }
        return cur;
    }

    function collectAncestorScrims(panel, portalLayer) {
        const res = [];
        for (let p = panel.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
            const bg = getComputedStyle(p).backgroundColor || "";
            const m = bg.match(/^rgba\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*([\d.]+)\s*\)$/i);
            if (m && parseFloat(m[1]) > 0.05 && parseFloat(m[1]) < 0.96) {
                res.push({ el: p, bg });
            }
            if (p === portalLayer) break;
        }
        return res;
    }

    function findDrawerPanel(drawerRoot, settingsLink) {
        const vw = window.innerWidth || 400;
        const vh = window.innerHeight || 600;
        let panel = null;
        for (let el = settingsLink; el && el !== document.body; el = el.parentElement) {
            const r = el.getBoundingClientRect();
            if (r.width >= 180 && r.width < vw * 0.96 && r.height >= vh * 0.72 && r.left <= 16) {
                panel = el;
            }
            if (el === drawerRoot) break;
        }
        return panel;
    }

    function trackActiveDrawer(drawerRoot, settingsLink) {
        const panel = findDrawerPanel(drawerRoot, settingsLink);
        if (!panel) return;
        clearDrawerExitGhost();
        if (activeDrawer && activeDrawer.panel === panel) {
            if (activeDrawer.ancestorScrims.length === 0) {
                activeDrawer.ancestorScrims = collectAncestorScrims(panel, activeDrawer.portalLayer);
            }
            return;
        }
        const vw = window.innerWidth || 400;
        const vh = window.innerHeight || 600;
        const portalLayer = drawerRoot.closest("#layers > *") || drawerRoot;
        const scrims = [];
        for (const el of portalLayer.querySelectorAll("div")) {
            if (el === panel || panel.contains(el) || el.contains(panel)) continue;
            const r = el.getBoundingClientRect();
            if (r.width >= vw * 0.85 && r.height >= vh * 0.85) scrims.push(el);
        }
        const state = {
            portalLayer,
            panel,
            panelWidth: Math.round(panel.getBoundingClientRect().width) || 280,
            scrims,
            ancestorScrims: collectAncestorScrims(panel, portalLayer),
            scroller: null,
            scrollTop: 0
        };
        const onScroll = (e) => {
            const t = e.target;
            if (t?.nodeType === 1 && panel.contains(t) && t.scrollHeight > t.clientHeight + 8) {
                state.scroller = t;
                state.scrollTop = t.scrollTop || 0;
            }
        };
        portalLayer.addEventListener("scroll", onScroll, { passive: true, capture: true });
        state.cleanup = () => portalLayer.removeEventListener("scroll", onScroll, { capture: true });
        activeDrawer?.cleanup?.();
        activeDrawer = state;
    }

    function isAlreadySlidOffscreen(tf, panelWidth) {
        const pct = tf.match(/translate(?:3d|X)\(\s*(-?[\d.]+)%/i);
        if (pct && parseFloat(pct[1]) <= -85) return true;
        const px = tf.match(/translate(?:3d|X)\(\s*(-?[\d.]+)px/i);
        return Boolean(px && panelWidth > 0 && parseFloat(px[1]) <= -panelWidth * 0.85);
    }

    function onDomRemoval() {
        if (!activeDrawer || activeDrawer.panel.isConnected) return;
        const d = activeDrawer;
        activeDrawer = null;
        d.cleanup?.();
        if (!document.body || window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches) return;
        const startTf = (d.panel.style.transform && d.panel.style.transform !== "none")
            ? d.panel.style.transform
            : "translate3d(0, 0, 0)";
        if (isAlreadySlidOffscreen(startTf, d.panelWidth)) return;

        let detachedRoot = d.panel;
        while (detachedRoot.parentElement) detachedRoot = detachedRoot.parentElement;
        const panelPath = getPathFromRoot(detachedRoot, d.panel);
        if (!panelPath) return;
        const scrimPaths = d.scrims.map((s) => getPathFromRoot(detachedRoot, s)).filter(Boolean);
        const ancPaths = d.ancestorScrims
            .map((a) => ({ path: getPathFromRoot(detachedRoot, a.el), bg: a.bg }))
            .filter((a) => a.path);
        const scrollerPath = d.scroller ? getPathFromRoot(detachedRoot, d.scroller) : null;

        clearDrawerExitGhost();
        const host = document.createElement("div");
        host.id = "tw-drawer-exit-ghost";
        host.dataset.twOwn = "1";
        host.setAttribute("aria-hidden", "true");
        const clone = detachedRoot.cloneNode(true);
        const ghostPanel = resolvePath(clone, panelPath);
        if (!ghostPanel) return;

        const ghostScrims = scrimPaths.map((p) => resolvePath(clone, p)).filter(Boolean);
        for (const a of ancPaths) {
            const gAnc = resolvePath(clone, a.path);
            if (!gAnc) continue;
            gAnc.style.setProperty("background-color", "transparent", "important");
            const fakeScrim = document.createElement("div");
            fakeScrim.className = "tw-drawer-ghost-scrim";
            fakeScrim.style.backgroundColor = a.bg;
            host.appendChild(fakeScrim);
            ghostScrims.push(fakeScrim);
        }
        const ghostScroller = scrollerPath ? resolvePath(clone, scrollerPath) : null;
        for (const el of [clone, ...clone.querySelectorAll("[id], [role='dialog'], [data-testid='Drawer']")]) {
            el.removeAttribute("id");
            if (el.getAttribute("role") === "dialog") el.removeAttribute("role");
            if (el.getAttribute("data-testid") === "Drawer") el.removeAttribute("data-testid");
        }
        host.appendChild(clone);
        document.body.appendChild(host);
        activeGhostEl = host;
        if (ghostScroller && d.scrollTop > 0) ghostScroller.scrollTop = d.scrollTop;

        const anim = typeof ghostPanel.animate === "function"
            ? ghostPanel.animate(
                [{ transform: startTf }, { transform: "translate3d(-100%, 0, 0)" }],
                { duration: 210, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" }
            )
            : null;
        for (const sc of ghostScrims) {
            sc.animate?.([{ opacity: 1 }, { opacity: 0 }], { duration: 200, easing: "ease-out", fill: "forwards" });
        }
        const finish = () => {
            if (activeGhostEl === host) clearDrawerExitGhost();
            else host.remove();
        };
        if (anim) {
            anim.onfinish = finish;
            anim.oncancel = finish;
            setTimeout(finish, 320);
        } else {
            finish();
        }
    }

    window.__TW_DRAWER__ = { trackActiveDrawer, onDomRemoval };
})();
