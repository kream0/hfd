(() => {
  // The APK link's QR code, and al-Ikhlāṣ's āyāt (words per āya), filled in by tools/site/build.py.
  const QR = {{QR_JSON}};
  const AYAT = {{AYAT_JSON}};
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const root = document.documentElement;
  const css = name => getComputedStyle(root).getPropertyValue(name).trim();
  let T = {};
  const readTheme = () => {
    T = {
      bg: css("--bg"), surface: css("--surface"), fg: css("--fg"), dim: css("--dim"), faint: css("--faint"),
      off: css("--dot-off"), red: css("--red"), orange: css("--s-orange"), yellow: css("--s-yellow"),
      green: css("--s-green"), qrFg: css("--qr-fg"), qrBg: css("--qr-bg"),
    };
  };
  readTheme();

  // ---------------------------------------------------------------- colour helpers
  const hex = h => { h = h.replace("#", ""); if (h.length === 3) h = [...h].map(c => c + c).join(""); const n = parseInt(h, 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
  const mix = (a, b, t) => { const A = hex(a), B = hex(b); return `rgb(${A.map((v, i) => Math.round(v + (B[i] - v) * t)).join(",")})`; };
  // The app's scale, for downloads and for an āya's strength: red → orange → yellow → green.
  const scaleColor = p => p < 1 / 3 ? mix(T.red, T.orange, p * 3) : p < 2 / 3 ? mix(T.orange, T.yellow, (p - 1 / 3) * 3) : mix(T.yellow, T.green, (p - 2 / 3) * 3);

  // ---------------------------------------------------------------- the plan: each āya ×3, a pause after each
  // As the player builds it: āya, pause as long as the āya (to say it again), ×3, next āya. Each
  // āya's strength ring moves a step towards green every time its three repetitions are done.
  const memo = (() => {
    const bar = document.getElementById("plan"), chip = document.getElementById("plan-chip");
    const rows = [...document.querySelectorAll("#ayat li")];
    const rings = rows.map(li => li.querySelector("canvas"));
    const REPS = 3, CYCLE = 15000;
    const segs = [];
    AYAT.forEach((words, a) => {
      const len = 0.8 + 0.35 * words;
      for (let r = 1; r <= REPS; r++) { segs.push({ a, r, rec: true, len }); segs.push({ a, r, rec: false, len }); }
    });
    const total = segs.reduce((s, x) => s + x.len, 0);
    let acc = 0;
    segs.forEach(s => { s.from = acc / total; acc += s.len; s.to = acc / total; });
    const at = f => segs.find(s => f < s.to) || segs[segs.length - 1];
    const START = [0.5, 0.25, 0, 0];
    let strength = START.slice(), pass = -1, lastSeg = null, start = null;

    const ring = (canvas, s) => {
      const ctx = canvas.getContext("2d"), W = canvas.width, c = W / 2, lw = W * 0.13, rad = c - lw;
      ctx.clearRect(0, 0, W, W);
      ctx.lineWidth = lw; ctx.lineCap = "round";
      ctx.strokeStyle = T.off; ctx.beginPath(); ctx.arc(c, c, rad, 0, 7); ctx.stroke();
      ctx.strokeStyle = scaleColor(s); ctx.beginPath(); ctx.arc(c, c, rad, -Math.PI / 2, -Math.PI / 2 + 2 * Math.PI * Math.max(s, 0.04)); ctx.stroke();
    };

    const drawBar = f => {
      const ctx = bar.getContext("2d"), W = bar.width, H = bar.height;
      const n = Math.max(24, Math.round(W / (H * 0.36))), cell = W / n, rad = Math.min(cell, H) * 0.24;
      const headAt = Math.min(n - 1, Math.floor(f * n));
      ctx.clearRect(0, 0, W, H);
      for (let i = 0; i < n; i++) {
        if (i === headAt) continue;
        const s = at((i + .5) / n), played = i < headAt;
        ctx.fillStyle = s.rec ? (played ? T.fg : T.off) : (played ? T.faint : T.off);
        ctx.beginPath(); ctx.arc((i + .5) * cell, H / 2, s.rec ? rad : rad * 0.5, 0, 7); ctx.fill();
      }
      ctx.fillStyle = T.red;
      ctx.beginPath(); ctx.arc((headAt + .5) * cell, H / 2, rad * 2.1, 0, 7); ctx.fill();
    };

    const renderAt = f => {
      const s = at(f);
      // An āya's repetitions all heard: a step stronger.
      if (lastSeg && lastSeg !== s && lastSeg.r === REPS && !lastSeg.rec && s.a !== lastSeg.a) {
        strength[lastSeg.a] = Math.min(1, strength[lastSeg.a] + 0.25);
      }
      lastSeg = s;
      drawBar(f);
      rows.forEach((li, i) => li.classList.toggle("on", i === s.a));
      rings.forEach((c, i) => ring(c, strength[i]));
      const label = s.rec ? `ÉCOUTE ${s.r}/${REPS}` : `À VOUS ${s.r}/${REPS}`;
      if (chip.textContent !== label) chip.textContent = label;
      chip.style.color = s.rec ? T.fg : T.red;
    };

    const render = now => {
      if (start === null) start = now - 0.07 * CYCLE;
      const k = Math.floor((now - start) / CYCLE);
      if (k !== pass) {
        // Each pass round the plan; once every āya is green, the next pass starts over.
        if (pass >= 0 && strength[strength.length - 1] >= 1 && strength.every(v => v >= 1)) strength = START.slice();
        if (pass >= 0 && lastSeg) strength[lastSeg.a] = Math.min(1, strength[lastSeg.a] + 0.25);
        pass = k; lastSeg = null;
      }
      renderAt(((now - start) % CYCLE) / CYCLE);
    };
    const still = () => { strength = [1, 0.75, 0.5, 0]; lastSeg = null; renderAt(0.62); };
    return { render, still, el: bar };
  })();

  // ---------------------------------------------------------------- feature icons (dot pictograms)
  const ICONS = {
    list: ["1011111", "0000000", "1011111", "0000000", "1011100", "0000000", "1010000", "0000100", "0001110"],
    book: ["1100011", "1110111", "1011101", "1001001", "1001001", "1001001", "1001001", "1111111", "0001000"],
    loop: ["0011100", "0100010", "1000001", "1000101", "1001101", "1000101", "1000001", "0100010", "0011100"],
    ear: ["0111000", "1000100", "1010100", "1000100", "0111100", "0000100", "0000100", "0000100", "0000100"],
    learn: ["0000001", "0000001", "0000101", "0000101", "0010101", "0010101", "1010101", "1010101", "1010101"],
    calendar: ["1010101", "1111111", "1000001", "1010101", "1000001", "1010101", "1000001", "1010101", "1111111"],
    mic: ["1011100", "0011100", "0011100", "0011100", "1011101", "1011101", "0100010", "0011100", "0111110"],
    save: ["0001000", "0001000", "0001000", "1001001", "0101010", "0011100", "0001000", "0000000", "1111111"],
    journal: ["1011111", "0000000", "0011111", "0000000", "0011110", "0000000", "0011111", "0000000", "0011100"],
    bell: ["0001000", "0011100", "0111110", "0111110", "0111110", "0111110", "1111111", "0000000", "0011100"],
    theme: ["0011100", "0111010", "1111001", "1111001", "1111001", "1111001", "1111001", "0111010", "0011100"],
    update: ["0001000", "0011100", "0101010", "1001001", "0001000", "0001000", "0001000", "0000000", "1111111"],
  };
  const drawIcon = canvas => {
    const rows = ICONS[canvas.dataset.icon], ctx = canvas.getContext("2d"), W = canvas.width;
    const n = 9, cell = W / n, ox = (W - rows[0].length * cell) / 2;
    ctx.clearRect(0, 0, W, W);
    rows.forEach((r, y) => [...r].forEach((b, x) => {
      ctx.fillStyle = b === "1" ? (y === 0 && x === rows[0].indexOf("1") ? T.red : T.fg) : T.off;
      ctx.beginPath(); ctx.arc(ox + (x + .5) * cell, (y + .5) * cell, cell * (b === "1" ? .36 : .18), 0, 7); ctx.fill();
    }));
  };

  // ---------------------------------------------------------------- QR code (drawn in dots)
  const drawQR = () => {
    const canvas = document.getElementById("qr"), ctx = canvas.getContext("2d"), n = QR.length, q = 2, W = canvas.width, cell = W / (n + 2 * q);
    ctx.fillStyle = T.qrBg; ctx.fillRect(0, 0, W, W);
    ctx.fillStyle = T.qrFg;
    const finder = (r, c) => (r < 7 && c < 7) || (r < 7 && c >= n - 7) || (r >= n - 7 && c < 7);
    const rr = (x, y, w, h, rad) => { ctx.beginPath(); ctx.roundRect ? ctx.roundRect(x, y, w, h, rad) : ctx.rect(x, y, w, h); };
    for (const [r0, c0] of [[0, 0], [0, n - 7], [n - 7, 0]]) {
      const X = (c0 + q) * cell, Y = (r0 + q) * cell;
      // Rounded, not so much that scanners miss them (1.8 / 1.2 / 0.9 cells didn't decode).
      ctx.fillStyle = T.qrFg; rr(X, Y, 7 * cell, 7 * cell, cell * 1.2); ctx.fill();
      ctx.fillStyle = T.qrBg; rr(X + cell, Y + cell, 5 * cell, 5 * cell, cell * .8); ctx.fill();
      ctx.fillStyle = T.qrFg; rr(X + 2 * cell, Y + 2 * cell, 3 * cell, 3 * cell, cell * .6); ctx.fill();
    }
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
      if (QR[r][c] !== "1" || finder(r, c)) continue;
      ctx.beginPath(); ctx.arc((c + q + .5) * cell, (r + q + .5) * cell, cell * .46, 0, 7); ctx.fill();
    }
  };

  // ---------------------------------------------------------------- paint + loop
  const paintStatic = () => {
    document.querySelectorAll("canvas[data-icon]").forEach(drawIcon);
    drawQR();
  };
  paintStatic();
  memo.still();

  const onTheme = () => { readTheme(); paintStatic(); if (reduce) memo.still(); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", onTheme);
  new MutationObserver(onTheme).observe(root, { attributes: true, attributeFilter: ["data-theme"] });

  if (!reduce) {
    const seen = new Map();
    const io = new IntersectionObserver(es => es.forEach(e => seen.set(e.target, e.isIntersecting)), { rootMargin: "80px" });
    [memo.el].forEach(el => { seen.set(el, true); io.observe(el); });
    const loop = now => {
      if (seen.get(memo.el)) memo.render(now);
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }

  // ---------------------------------------------------------------- theme switch (remembered on this device)
  const dark = matchMedia("(prefers-color-scheme: dark)");
  const modeButtons = document.querySelectorAll(".mode button");
  const showMode = () => {
    const current = root.dataset.theme || (dark.matches ? "dark" : "light");
    modeButtons.forEach(b => b.setAttribute("aria-pressed", String(b.dataset.mode === current)));
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = current === "dark" ? "#000000" : "#EEE8DA";
  };
  try { const saved = localStorage.getItem("hfd-theme"); if (saved === "dark" || saved === "light") root.dataset.theme = saved; } catch (e) {}
  modeButtons.forEach(b => b.addEventListener("click", () => {
    root.dataset.theme = b.dataset.mode;
    try { localStorage.setItem("hfd-theme", b.dataset.mode); } catch (e) {}
    showMode();
  }));
  dark.addEventListener("change", showMode);
  new MutationObserver(showMode).observe(root, { attributes: true, attributeFilter: ["data-theme"] });
  showMode();

  // ---------------------------------------------------------------- copy link
  const copy = document.getElementById("copy");
  copy.addEventListener("click", () => {
    const url = document.getElementById("apk-url").textContent;
    const done = ok => { copy.textContent = ok ? "COPIÉ" : "SÉLECTIONNÉ"; setTimeout(() => (copy.textContent = "COPIER"), 1800); };
    const select = () => { const s = getSelection(), r = document.createRange(); r.selectNodeContents(document.getElementById("apk-url")); s.removeAllRanges(); s.addRange(r); done(false); };
    try { navigator.clipboard.writeText(url).then(() => done(true), select); } catch (e) { select(); }
  });
})();
