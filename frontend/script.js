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

  function renderComparisonChart(el, months, before, after) {
    if (!el) return;
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

  function renderEffectChart() {
    const el = document.getElementById("effectChart");
    renderComparisonChart(el, EFFECT_CHART.months, EFFECT_CHART.before, EFFECT_CHART.after);
  }

  // ---------------- 군집별 비교 (건축물대장 주구조/층수/주용도 기반 목데이터) ----------------

  const CLUSTER_STRUCTURE_INFO = {
    "철근콘크리트구조": { factor: 1.00, sample: 420, reduction: 0.36 },
    "철골구조": { factor: 1.08, sample: 180, reduction: 0.42 },
    "철골철근콘크리트구조": { factor: 1.04, sample: 95, reduction: 0.39 },
    "조적구조": { factor: 0.95, sample: 60, reduction: 0.30 },
    "목구조": { factor: 0.90, sample: 18, reduction: 0.26 },
  };

  const CLUSTER_USAGE_INFO = {
    "": { factor: 1.00, sampleMult: 1.3, label: "전체 용도" },
    "공동주택": { factor: 0.98, sampleMult: 0.42, label: "공동주택(아파트)" },
    "업무시설": { factor: 1.00, sampleMult: 0.38, label: "업무시설" },
    "근린생활시설": { factor: 1.02, sampleMult: 0.5, label: "근린생활시설" },
    "공장": { factor: 1.06, sampleMult: 0.3, label: "공장" },
    "창고시설": { factor: 1.08, sampleMult: 0.22, label: "창고시설" },
    "교육연구시설": { factor: 0.97, sampleMult: 0.28, label: "교육연구시설" },
  };

  const CLUSTER_BASE_BEFORE = [8, 10, 15, 22, 30, 42, 52, 54, 44, 28, 16, 9];

  function floorBand(floors) {
    if (floors <= 4) return { key: "저층", factor: 1.03, sampleMult: 1.15 };
    if (floors <= 15) return { key: "중층", factor: 1.00, sampleMult: 1.0 };
    return { key: "고층", factor: 0.94, sampleMult: 0.55 };
  }

  function computeCluster(structureKey, floors, usageKey) {
    const struct = CLUSTER_STRUCTURE_INFO[structureKey];
    const usage = CLUSTER_USAGE_INFO[usageKey] || CLUSTER_USAGE_INFO[""];
    const band = floorBand(floors);

    const combinedFactor = struct.factor * band.factor * usage.factor;
    const before = CLUSTER_BASE_BEFORE.map((v) => Math.round(Math.min(59, v * combinedFactor) * 10) / 10);
    const after = before.map((v) => Math.round(v * (1 - struct.reduction) * 10) / 10);

    const diffs = before.map((v, i) => v - after[i]);
    const maxDiff = Math.max(...diffs);
    const avgReduction = diffs.reduce((a, b) => a + b, 0) / diffs.length;
    const energySavingPct = Math.min(42, Math.round(maxDiff * 1.2 * 10) / 10);
    const sampleCount = Math.max(8, Math.round(struct.sample * band.sampleMult * usage.sampleMult));

    return {
      months: EFFECT_CHART.months,
      before, after,
      maxDiff, avgReduction, energySavingPct, sampleCount,
      bandLabel: band.key, usageLabel: usage.label,
    };
  }

  function renderClusterResult(structureKey, floors, usageKey) {
    const result = computeCluster(structureKey, floors, usageKey);

    renderComparisonChart(document.getElementById("clusterChart"), result.months, result.before, result.after);

    const usageText = usageKey ? result.usageLabel : "전체 용도";
    document.getElementById("clusterSampleCount").textContent = result.sampleCount.toLocaleString();
    document.getElementById("clusterMaxDiff").textContent = `-${result.maxDiff.toFixed(1)}℃`;
    document.getElementById("clusterAvgReduction").textContent = `-${result.avgReduction.toFixed(1)}℃`;
    document.getElementById("clusterEnergySaving").textContent = `${result.energySavingPct.toFixed(1)}%`;
    document.getElementById("clusterConditionLabel").textContent =
      `${structureKey} · ${floors}층(${result.bandLabel}) · ${usageText}`;
    document.getElementById("clusterChipMeta").textContent = `${structureKey} · ${floors}층 · ${usageText}`;

    document.getElementById("clusterAiText").innerHTML =
      `선택하신 <b style="color:var(--ink);">${structureKey}</b> / ` +
      `<b style="color:var(--ink);">${floors}층(${result.bandLabel})</b>` +
      (usageKey ? ` / <b style="color:var(--ink);">${result.usageLabel}</b>` : "") +
      ` 조건과 유사한 전국 실증 건물 <b style="color:var(--ink);">${result.sampleCount.toLocaleString()}건</b>을 비교한 결과입니다.` +
      `<br><br>` +
      `이 군집은 여름철(7~8월) 표면온도 차이가 최대 <b style="color:var(--ink);">${result.maxDiff.toFixed(1)}℃</b>까지 벌어지며, ` +
      `연중 평균 <b style="color:var(--ink);">${result.avgReduction.toFixed(1)}℃</b>의 저감 효과를 보입니다. ` +
      `이는 냉방 에너지 사용량을 약 <b style="color:var(--ink);">${result.energySavingPct.toFixed(1)}%</b> 절감하는 수준입니다.` +
      `<br><br>` +
      `<b style="color:var(--ink);">결론: 이 조건의 건물이라면 쿨루프 시공 효과가 뚜렷하게 나타날 것으로 예상됩니다.</b>`;
  }

  let clusterRefresh = null;

  function initClusterPage() {
    const form = document.getElementById("clusterForm");
    if (!form) return;
    const structureSel = document.getElementById("clusterStructure");
    const floorsInput = document.getElementById("clusterFloors");
    const usageSel = document.getElementById("clusterUsage");

    function submitCurrent() {
      const floors = Math.max(1, Math.min(80, Number(floorsInput.value) || 1));
      renderClusterResult(structureSel.value, floors, usageSel.value);
    }

    form.addEventListener("submit", (e) => {
      e.preventDefault();
      submitCurrent();
    });

    clusterRefresh = submitCurrent;
    submitCurrent();
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
        if (targetId === "page-cluster" && clusterRefresh) clusterRefresh();
      });
    });
  }

  function init() {
    renderEffectChart();
    window.addEventListener("resize", renderEffectChart);
    initPageNav();
    initClusterPage();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
