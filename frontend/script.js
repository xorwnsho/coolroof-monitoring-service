(function () {
  const tooltip = document.getElementById("tooltip");

  function showTip(x, y, html) {
    tooltip.innerHTML = html;
    tooltip.style.left = x + "px";
    tooltip.style.top = y + "px";
    tooltip.style.opacity = "1";
  }
  function hideTip() {
    tooltip.style.opacity = "0";
  }

  function renderEffectChart() {
    const el = document.getElementById("effectChart");
    const { months, before, after } = EFFECT_CHART;

    const w = el.clientWidth || 420;
    const h = el.clientHeight || 240;
    const padL = 30, padR = 10, padT = 10, padB = 22;
    const innerW = w - padL - padR;
    const innerH = h - padT - padB;
    const maxY = 60, minY = 0;

    const xAt = (i) => padL + (innerW * i) / (months.length - 1);
    const yAt = (v) => padT + innerH - (innerH * (v - minY)) / (maxY - minY);

    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");

    const defs = document.createElementNS(ns, "defs");
    function grad(id, color) {
      const g = document.createElementNS(ns, "linearGradient");
      g.setAttribute("id", id);
      g.setAttribute("x1", "0"); g.setAttribute("y1", "0");
      g.setAttribute("x2", "0"); g.setAttribute("y2", "1");
      const s1 = document.createElementNS(ns, "stop");
      s1.setAttribute("offset", "0%"); s1.setAttribute("stop-color", color); s1.setAttribute("stop-opacity", "0.35");
      const s2 = document.createElementNS(ns, "stop");
      s2.setAttribute("offset", "100%"); s2.setAttribute("stop-color", color); s2.setAttribute("stop-opacity", "0");
      g.appendChild(s1); g.appendChild(s2);
      defs.appendChild(g);
    }
    const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    grad("gradBefore", cssVar("--surface-hot"));
    grad("gradAfter", cssVar("--accent"));
    svg.appendChild(defs);

    [0, 20, 40, 60].forEach((v) => {
      const y = yAt(v);
      const l = document.createElementNS(ns, "line");
      l.setAttribute("x1", padL); l.setAttribute("x2", w - padR);
      l.setAttribute("y1", y); l.setAttribute("y2", y);
      l.setAttribute("class", "grid-line");
      svg.appendChild(l);
      const t = document.createElementNS(ns, "text");
      t.setAttribute("x", 2); t.setAttribute("y", y + 3);
      t.setAttribute("class", "axis-label");
      t.textContent = v;
      svg.appendChild(t);
    });

    months.forEach((m, i) => {
      const t = document.createElementNS(ns, "text");
      t.setAttribute("x", xAt(i));
      t.setAttribute("y", h - 6);
      t.setAttribute("text-anchor", i === months.length - 1 ? "end" : i === 0 ? "start" : "middle");
      t.setAttribute("class", "axis-label");
      t.textContent = m;
      svg.appendChild(t);
    });

    // 여름철(7~8월) 최대 차이 구간 하이라이트
    const summerStart = 6, summerEnd = 7;
    const band = document.createElementNS(ns, "rect");
    band.setAttribute("x", xAt(summerStart));
    band.setAttribute("y", padT);
    band.setAttribute("width", xAt(summerEnd) - xAt(summerStart));
    band.setAttribute("height", innerH);
    band.setAttribute("fill", "rgba(87,174,128,0.08)");
    svg.appendChild(band);

    function line(values, color, gradId) {
      const top = values.map((v, i) => `${xAt(i)},${yAt(v)}`).join(" L ");
      const path = document.createElementNS(ns, "path");
      const d = `M ${xAt(0)},${yAt(0)} L ${top} L ${xAt(values.length - 1)},${yAt(0)} Z`;
      path.setAttribute("d", d);
      path.setAttribute("fill", `url(#${gradId})`);
      svg.appendChild(path);

      const poly = document.createElementNS(ns, "polyline");
      poly.setAttribute("points", values.map((v, i) => `${xAt(i)},${yAt(v)}`).join(" "));
      poly.setAttribute("fill", "none");
      poly.setAttribute("stroke", color);
      poly.setAttribute("stroke-width", "2");
      poly.setAttribute("stroke-linecap", "round");
      poly.setAttribute("stroke-linejoin", "round");
      svg.appendChild(poly);
    }
    line(before, cssVar("--surface-hot"), "gradBefore");
    line(after, cssVar("--accent"), "gradAfter");

    const overlay = document.createElementNS(ns, "rect");
    overlay.setAttribute("x", padL); overlay.setAttribute("y", padT);
    overlay.setAttribute("width", innerW); overlay.setAttribute("height", innerH);
    overlay.setAttribute("fill", "transparent");
    svg.appendChild(overlay);

    overlay.addEventListener("mousemove", (e) => {
      const rect = svg.getBoundingClientRect();
      const scaleX = w / rect.width;
      const mx = (e.clientX - rect.left) * scaleX;
      const i = Math.round(((mx - padL) / innerW) * (months.length - 1));
      const idx = Math.max(0, Math.min(months.length - 1, i));
      showTip(e.clientX, e.clientY, `<b>${months[idx]}</b><br>시공 전 ${before[idx].toFixed(0)}℃ · 시공 후 ${after[idx].toFixed(0)}℃ (차이 ${(before[idx] - after[idx]).toFixed(0)}℃)`);
    });
    overlay.addEventListener("mouseleave", hideTip);

    el.innerHTML = "";
    el.appendChild(svg);
  }

  function initPageNav() {
    const navButtons = document.querySelectorAll(".railbtn[data-page]");
    const pages = document.querySelectorAll(".page-view");

    navButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        const targetId = btn.getAttribute("data-page");
        pages.forEach((page) => {
          page.hidden = page.id !== targetId;
        });
        navButtons.forEach((b) => b.classList.toggle("active", b === btn));
        if (targetId === "page-dashboard") renderEffectChart();
      });
    });
  }

  function init() {
    renderEffectChart();
    window.addEventListener("resize", renderEffectChart);
    initPageNav();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
