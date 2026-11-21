// Portfolio app screen. All numbers come from the Android side (window.Native),
// which reads the published Sheet and does the maths; this file only formats and draws.
(function () {
  "use strict";

  const Native = window.Native || null;
  const $ = (id) => document.getElementById(id);

  const ICONS = {
    refresh: '<svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/></svg>',
    settings: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
    eye: '<svg viewBox="0 0 24 24"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>',
    eyeOff: '<svg viewBox="0 0 24 24"><path d="M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-2.2 3.1M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/><path d="M2 2l20 20"/></svg>',
    close: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
  };
  const PALETTE = ["#2EE6A6", "#5EE7F0", "#7AA2FF", "#C08CFF", "#FF9EC4", "#FFC46B", "#9BE15D", "#6BD6C8", "#B8C4C4"];

  let state = {};
  let sortBy = "value";
  let openTicker = null;
  let refreshing = false;
  let update = null;          // latest release info when newer than this version
  let lastUpdateCheck = 0;
  let manualCheck = false;

  // ---------- Formatting ----------
  const money = (v, signed) => {
    if (v === null || v === undefined || isNaN(v)) return "—";
    const a = Math.abs(v);
    const body = a.toLocaleString("en-AU", { minimumFractionDigits: a >= 1000 ? 0 : 2, maximumFractionDigits: a >= 1000 ? 0 : 2 });
    return (v < 0 ? "−" : signed ? "+" : "") + "$" + body;
  };
  const price = (v) => {
    if (v === null || v === undefined) return "—";
    const d = v >= 1 ? 2 : 4;
    return "$" + v.toLocaleString("en-AU", { minimumFractionDigits: d, maximumFractionDigits: d });
  };
  const pct = (p) => (p === null || p === undefined ? "—" : (p < 0 ? "−" : "+") + Math.abs(p * 100).toFixed(2) + "%");
  const units = (u) => u.toLocaleString("en-AU", { maximumFractionDigits: 6 });
  const tone = (v) => (v === null || v === undefined || Math.abs(v) < 1e-9 ? "flat" : v > 0 ? "pos" : "neg");
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const hidden = () => !!state.hideValues;
  const amt = (v, signed) => (hidden() ? "$•••" : money(v, signed));

  function timeAgo(ms) {
    if (!ms) return "Not loaded yet";
    const mins = Math.round((Date.now() - ms) / 60000);
    const time = new Date(ms).toLocaleTimeString("en-AU", { hour: "numeric", minute: "2-digit" });
    if (mins < 1) return "Updated just now";
    if (mins < 60) return `Updated ${mins} min ago`;
    const sameDay = new Date(ms).toDateString() === new Date().toDateString();
    return sameDay ? `Updated ${time}` : `Updated ${new Date(ms).toLocaleDateString("en-AU", { day: "numeric", month: "short" })}, ${time}`;
  }

  function haptic(kind) {
    try { Native && Native.haptic(kind || "tap"); } catch (e) { /* not on a phone */ }
  }

  // ---------- Drawing ----------
  function render() {
    $("updated").textContent = state.url ? timeAgo(state.fetchedMs) : "Set up your Sheet to start";
    $("btnHide").innerHTML = hidden() ? ICONS.eyeOff : ICONS.eye;

    const s = state.summary;
    const notice = $("notice");
    const problems = [];
    if (state.error) problems.push(state.error + (s ? " Showing the last update." : ""));
    if (s && s.missingPrices) problems.push(`${s.missingPrices} holding${s.missingPrices > 1 ? "s have" : " has"} no price yet (check the Price column).`);
    notice.hidden = problems.length === 0;
    notice.textContent = problems.join(" ");

    const content = $("content");
    if (!state.url) { content.innerHTML = emptyHtml(); bindEmpty(); return; }
    if (!s) {
      content.innerHTML = `<div class="card empty"><h2>${refreshing ? "Loading your Sheet…" : "No data yet"}</h2><p>${refreshing ? "This takes a few seconds." : "Pull down or tap refresh to load your holdings."}</p></div>`;
      return;
    }
    if (!s.holdings.length) {
      content.innerHTML = `<div class="card empty"><h2>No holdings yet</h2><p>Add a row to the Holdings tab of your Sheet, then refresh.</p></div>`;
      return;
    }

    const holdings = s.holdings.slice().sort((a, b) => {
      if (sortBy === "today") return (b.dayPct ?? -Infinity) - (a.dayPct ?? -Infinity);
      if (sortBy === "gain") return (b.gainPct ?? -Infinity) - (a.gainPct ?? -Infinity);
      return b.value - a.value;
    });
    const colour = {};
    s.holdings.forEach((h, i) => { colour[h.ticker] = PALETTE[Math.min(i, PALETTE.length - 1)]; });

    content.innerHTML = `
      <div class="card hero">
        <div class="label">Total value</div>
        <div class="hero-value num">${amt(s.value)}</div>
        <div class="hero-sub">${s.holdings.length} holding${s.holdings.length === 1 ? "" : "s"}${s.cost !== null && !hidden() ? " · " + money(s.cost) + " invested" : ""}</div>
      </div>

      <div class="stats">
        <div class="card stat">
          <div class="label">Today</div>
          <div class="stat-value num ${tone(s.dayChange)}">${s.dayChange === null ? "—" : hidden() ? pct(s.dayPct) : money(s.dayChange, true)}</div>
          <div class="stat-sub num">${s.dayChange === null ? '<span class="muted">No prev close</span>' : hidden() ? "&nbsp;" : `<span class="chip ${tone(s.dayPct)}">${pct(s.dayPct)}</span>`}</div>
        </div>
        <div class="card stat">
          <div class="label">Total gain</div>
          <div class="stat-value num ${tone(s.gain)}">${s.gain === null ? "—" : hidden() ? pct(s.gainPct) : money(s.gain, true)}</div>
          <div class="stat-sub num">${s.gain === null ? '<span class="muted">Add Avg cost</span>' : hidden() ? "&nbsp;" : `<span class="chip ${tone(s.gainPct)}">${pct(s.gainPct)}</span>`}</div>
        </div>
      </div>

      ${s.holdings.length > 1 ? `
      <div class="card alloc">
        <div class="label">Allocation</div>
        <div class="alloc-bar">${s.holdings.filter((h) => h.weight > 0).map((h) => `<span style="flex:${h.weight};background:${colour[h.ticker]}"></span>`).join("")}</div>
        <div class="legend">${s.holdings.filter((h) => h.weight > 0).map((h) => `<span><i style="background:${colour[h.ticker]}"></i>${esc(h.short)} ${(h.weight * 100).toFixed(1)}%</span>`).join("")}</div>
      </div>` : ""}

      <div class="list-head">
        <span class="label">Holdings</span>
        <div class="sort">
          ${[["value", "Value"], ["today", "Today"], ["gain", "Gain"]].map(([k, l]) => `<button data-sort="${k}" class="${sortBy === k ? "on" : ""}">${l}</button>`).join("")}
        </div>
      </div>
      <div class="holdings">${holdings.map((h) => holdingHtml(h)).join("")}</div>
    `;

    content.querySelectorAll("[data-sort]").forEach((b) => b.addEventListener("click", () => {
      if (sortBy === b.dataset.sort) return;
      sortBy = b.dataset.sort;
      haptic("tick");
      render();
    }));
    content.querySelectorAll("[data-ticker]").forEach((el) => el.addEventListener("click", () => {
      const t = el.dataset.ticker;
      openTicker = openTicker === t ? null : t;
      haptic("tap");
      el.classList.toggle("open", openTicker === t);
      content.querySelectorAll("[data-ticker]").forEach((o) => { if (o !== el) o.classList.remove("open"); });
    }));
  }

  function holdingHtml(h) {
    const noPrice = h.price === null;
    const day = h.dayPct === null ? "" : `<span class="chip ${tone(h.dayPct)} num">${pct(h.dayPct)}</span>`;
    const tile = h.short.length > 4 ? h.short.slice(0, 4) : h.short;
    return `
      <div class="card h ${openTicker === h.ticker ? "open" : ""}" data-ticker="${esc(h.ticker)}">
        <div class="h-row">
          <div class="tile">${esc(tile)}</div>
          <div class="h-main">
            <div class="h-name">${esc(h.name || h.short)}</div>
            <div class="h-meta num">${esc(h.short)} · ${hidden() ? "" : units(h.units) + " × "}${noPrice ? "no price" : price(h.price)}</div>
          </div>
          <div class="h-right">
            <div class="h-value num">${noPrice ? "—" : amt(h.value)}</div>
            ${day}
          </div>
        </div>
        <div class="weight"><span style="width:${(h.weight * 100).toFixed(2)}%"></span></div>
        <div class="h-detail num">
          <div><span class="label">Today</span><span class="${tone(h.dayChange)}">${h.dayChange === null ? "—" : hidden() ? pct(h.dayPct) : money(h.dayChange, true)}</span></div>
          <div><span class="label">Total gain</span><span class="${tone(h.gain)}">${h.gain === null ? "—" : (hidden() ? "" : money(h.gain, true) + " ") + "(" + pct(h.gainPct) + ")"}</span></div>
          <div><span class="label">Avg cost</span>${h.avgCost === null ? "—" : price(h.avgCost)}</div>
          <div><span class="label">Invested</span>${h.cost === null ? "—" : amt(h.cost)}</div>
          <div><span class="label">Prev close</span>${h.prevClose === null ? "—" : price(h.prevClose)}</div>
          <div><span class="label">Share of portfolio</span>${(h.weight * 100).toFixed(1)}%</div>
        </div>
      </div>`;
  }

  function emptyHtml() {
    return `
      <div class="card empty">
        <h2>Connect your Sheet</h2>
        <p>Your holdings live in a Google Sheet that pulls prices with GOOGLEFINANCE. Publish its Holdings tab as CSV and paste the link here. No Google scripts are used.</p>
        <button class="primary-btn" id="btnSetup">Add Sheet link</button>
      </div>`;
  }
  function bindEmpty() {
    const b = $("btnSetup");
    if (b) b.addEventListener("click", openSettings);
  }

  // ---------- Data ----------
  function load() {
    try { state = Native ? JSON.parse(Native.state()) : demoState(); } catch (e) { state = {}; }
    render();
  }

  function refresh() {
    if (refreshing || !state.url) return;
    refreshing = true;
    $("btnRefresh").classList.add("spin");
    if (!state.summary) render();
    if (Native) Native.refresh();
    else setTimeout(() => window.app.onRefreshed(JSON.stringify(demoState())), 600);
  }

  // ---------- Settings ----------
  function openSettings() {
    $("inUrl").value = state.url || "";
    $("inWidgetHoldings").checked = state.widgetHoldings !== false;
    $("inHide").checked = !!state.hideValues;
    $("sheet").hidden = false;
    haptic("tap");
  }
  function closeSettings() { $("sheet").hidden = true; }

  function saveSettings() {
    const url = $("inUrl").value.trim();
    if (url && !/^https:\/\/docs\.google\.com\/spreadsheets\//.test(url)) {
      alertField("That doesn't look like a Google Sheets link.");
      return;
    }
    if (url && !/output=csv/.test(url)) {
      alertField("Publish it as Comma-separated values (.csv): the link should end in output=csv.");
      return;
    }
    const changed = url !== (state.url || "");
    if (Native) Native.saveSettings(url, $("inWidgetHoldings").checked, $("inHide").checked);
    state.url = url;
    state.widgetHoldings = $("inWidgetHoldings").checked;
    state.hideValues = $("inHide").checked;
    if (changed) { state.summary = null; state.error = ""; state.fetchedMs = 0; }
    haptic("confirm");
    closeSettings();
    render();
    if (changed && url) refresh();
  }

  function alertField(msg) {
    const f = $("inUrl");
    f.setCustomValidity(msg);
    f.reportValidity();
    f.addEventListener("input", () => f.setCustomValidity(""), { once: true });
    haptic("tap");
  }

  // ---------- Pull to refresh ----------
  function setupPull() {
    const pull = $("pull");
    let startY = null, dist = 0;
    window.addEventListener("touchstart", (e) => {
      startY = window.scrollY <= 0 && $("sheet").hidden ? e.touches[0].clientY : null;
      dist = 0;
    }, { passive: true });
    window.addEventListener("touchmove", (e) => {
      if (startY === null) return;
      dist = Math.max(0, e.touches[0].clientY - startY);
      const d = Math.min(dist, 120);
      pull.style.transition = "none";
      pull.style.opacity = String(Math.min(1, d / 70));
      pull.style.transform = `translateY(${d * 0.6 - 60}px) rotate(${d * 3}deg)`;
    }, { passive: true });
    window.addEventListener("touchend", () => {
      if (startY === null) return;
      startY = null;
      pull.style.transition = "transform .25s, opacity .25s";
      if (dist > 80 && state.url) {
        haptic("confirm");
        pull.classList.add("go");
        pull.style.transform = "translateY(12px)";
        refresh();
      } else hidePull();
    });
  }
  function hidePull() {
    const pull = $("pull");
    pull.classList.remove("go");
    pull.style.transition = "transform .25s, opacity .25s";
    pull.style.transform = "translateY(-60px)";
    pull.style.opacity = "0";
  }

  // ---------- Updates ----------
  function checkUpdate(manual) {
    if (!Native || !Native.checkUpdate) return;
    manualCheck = !!manual;
    lastUpdateCheck = Date.now();
    if (manual) $("btnCheck").textContent = "Checking…";
    Native.checkUpdate();
  }
  function showUpdate() {
    const box = $("update");
    box.hidden = !update;
    if (!update) return;
    $("updateSub").textContent = `Version ${update.version} is ready. Tap Install, then confirm.`;
    const b = $("btnUpdate");
    b.disabled = false;
    b.textContent = "Install";
  }
  function installUpdate() {
    if (!update) return;
    haptic("confirm");
    const b = $("btnUpdate");
    b.disabled = true;
    b.textContent = "Downloading…";
    $("updateSub").textContent = "Android will ask you to confirm the install.";
    Native.installUpdate(update.apk);
    // If Android's screen was cancelled, let them try again.
    setTimeout(() => { if (b.disabled) { b.disabled = false; b.textContent = "Install"; } }, 20000);
  }

  // ---------- Called by Android ----------
  window.app = {
    onUpdateChecked(json) {
      let r = {};
      try { r = JSON.parse(json); } catch (e) { /* ignore */ }
      update = r.available ? r : null;
      showUpdate();
      if (manualCheck) {
        $("btnCheck").textContent = r.error ? "Couldn't check" : r.available ? `Version ${r.version} available` : "You're up to date";
        setTimeout(() => { $("btnCheck").textContent = "Check for updates"; }, 3000);
        if (r.available) closeSettings();
      }
      manualCheck = false;
    },
    onUpdateFailed(msg) {
      const b = $("btnUpdate");
      b.disabled = false;
      b.textContent = "Try again";
      $("updateSub").textContent = msg || "The update didn't install.";
    },
    onRefreshed(json) {
      refreshing = false;
      $("btnRefresh").classList.remove("spin");
      hidePull();
      try { state = JSON.parse(json); } catch (e) { /* keep what we had */ }
      render();
    },
    onResume() {
      load();
      // Refresh when coming back to the app if the last update is over 2 minutes old.
      if (state.url && Date.now() - (state.fetchedMs || 0) > 2 * 60000) refresh();
      // Look for a new version at most every 6 hours.
      if (Date.now() - lastUpdateCheck > 6 * 3600000) checkUpdate(false);
    },
    /** Android back button: close Settings first. Returns true if it handled it. */
    back() {
      if (!$("sheet").hidden) { closeSettings(); return true; }
      if (openTicker) {
        openTicker = null;
        document.querySelectorAll("[data-ticker].open").forEach((o) => o.classList.remove("open"));
        return true;
      }
      return false;
    },
  };

  // ---------- Start ----------
  $("btnRefresh").innerHTML = ICONS.refresh;
  $("btnSettings").innerHTML = ICONS.settings;
  $("btnClose").innerHTML = ICONS.close;
  $("btnRefresh").addEventListener("click", () => { haptic("tap"); refresh(); });
  $("btnSettings").addEventListener("click", openSettings);
  $("btnClose").addEventListener("click", closeSettings);
  $("btnSave").addEventListener("click", saveSettings);
  $("btnUpdate").addEventListener("click", installUpdate);
  $("btnCheck").addEventListener("click", () => { haptic("tap"); checkUpdate(true); });
  $("btnHide").addEventListener("click", () => {
    state.hideValues = !state.hideValues;
    if (Native) Native.saveSettings(state.url || "", state.widgetHoldings !== false, state.hideValues);
    haptic("tick");
    render();
  });
  $("sheet").addEventListener("click", (e) => { if (e.target === $("sheet")) closeSettings(); });
  try { $("version").textContent = Native ? "Version " + Native.version() : "Preview"; } catch (e) { /* ignore */ }
  setupPull();
  setInterval(() => { if (state.url) $("updated").textContent = timeAgo(state.fetchedMs); }, 30000);
  window.app.onResume();

  // ---------- Browser preview only (no phone) ----------
  function demoState() {
    const rows = [
      ["ASX:VAS", "Vanguard Aus Shares", 120, 98.5, 101.2, 100.4],
      ["ASX:NDQ", "Betashares Nasdaq 100", 300, 40.1, 48.62, 49.05],
      ["ASX:VGS", "Vanguard Intl Shares", 85, 110.2, 132.4, 131.9],
      ["CURRENCY:BTCAUD", "Bitcoin", 0.05, 62000, 151200, null],
    ];
    const hs = rows.map(([ticker, name, u, ac, p, pc]) => {
      const value = u * p, cost = u * ac;
      return {
        ticker, name, short: ticker.split(":")[1].replace(/AUD$/, ""), units: u, avgCost: ac, price: p, prevClose: pc,
        value, cost, gain: value - cost, gainPct: (value - cost) / cost,
        dayChange: pc === null ? null : u * (p - pc), dayPct: pc === null ? null : (p - pc) / pc,
      };
    });
    const value = hs.reduce((a, h) => a + h.value, 0);
    const cost = hs.reduce((a, h) => a + h.cost, 0);
    const withDay = hs.filter((h) => h.dayChange !== null);
    const day = withDay.reduce((a, h) => a + h.dayChange, 0);
    const dayBase = withDay.reduce((a, h) => a + h.units * h.prevClose, 0);
    hs.forEach((h) => { h.weight = h.value / value; });
    hs.sort((a, b) => b.value - a.value);
    return {
      url: "https://docs.google.com/spreadsheets/d/e/demo/pub?output=csv", widgetHoldings: true, hideValues: state.hideValues || false,
      fetchedMs: Date.now() - 4 * 60000, error: "",
      summary: { value, cost, gain: value - cost, gainPct: (value - cost) / cost, dayChange: day, dayPct: day / dayBase, missingPrices: 0, holdings: hs },
    };
  }
})();
