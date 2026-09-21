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

  // ---------------- 군집별 비교 (자연어 질의 → 백엔드 실측 분석) ----------------

  // 프론트(Vercel)와 백엔드(daisy) 배포 도메인이 다르면 이 값을 실제 백엔드 주소로 바꾼다.
  // 로컬에서 Spring Boot가 정적 파일을 같이 서빙할 때는 빈 문자열(같은 origin)로 둔다.
  const CLUSTER_API_BASE = location.hostname.endsWith("vercel.app")
      ? "https://wisoft.dev/juntaek/api"
      : "";

  function renderClusterResult(result) {
    const resultWrap = document.getElementById("clusterResultWrap");
    if (resultWrap) resultWrap.hidden = false;

    renderComparisonChart(document.getElementById("clusterChart"), result.months, result.before, result.after);

    const floorPhrase = result.floors != null ? `${result.floors}층(${result.floorBand})` : result.floorBand;
    const conditionText = `${result.structure} · ${floorPhrase} · ${result.usageLabel}`;
    document.getElementById("clusterSampleCount").textContent = result.sampleCount.toLocaleString();
    document.getElementById("clusterMaxDiff").textContent = `-${result.maxDiff.toFixed(1)}℃`;
    document.getElementById("clusterAvgReduction").textContent = `-${result.avgReduction.toFixed(1)}℃`;
    document.getElementById("clusterEnergySaving").textContent = `${result.energySavingPct.toFixed(1)}%`;
    document.getElementById("clusterConditionLabel").textContent = conditionText;
    document.getElementById("clusterChipMeta").textContent = `${result.region} · ${conditionText}`;
    document.getElementById("clusterAiText").textContent = result.aiText;
  }

  function showClusterError(message) {
    const errorEl = document.getElementById("clusterError");
    if (!errorEl) return;
    errorEl.textContent = message;
    errorEl.hidden = false;
  }

  function hideClusterError() {
    const errorEl = document.getElementById("clusterError");
    if (errorEl) errorEl.hidden = true;
  }

  let clusterRefresh = null;

  function initClusterPage() {
    const form = document.getElementById("clusterForm");
    if (!form) return;
    const queryInput = document.getElementById("clusterQuery");
    const submitBtn = document.getElementById("clusterSubmitBtn");
    let lastResult = null;

    const loadingEl = document.getElementById("clusterLoading");

    async function runAnalysis(query) {
      hideClusterError();
      submitBtn.disabled = true;
      submitBtn.textContent = "분석 중...";
      if (loadingEl) loadingEl.hidden = false;
      const resultWrap = document.getElementById("clusterResultWrap");
      if (resultWrap) resultWrap.hidden = true;
      try {
        const res = await fetch(`${CLUSTER_API_BASE}/api/cluster/analyze`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ query }),
        });
        const data = await res.json();
        if (!res.ok) {
          showClusterError(data.error || "분석에 실패했습니다. 다시 시도해 주세요.");
          return;
        }
        lastResult = data;
        renderClusterResult(data);
      } catch (err) {
        showClusterError("서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "분석하기";
        if (loadingEl) loadingEl.hidden = true;
      }
    }

    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const query = queryInput.value.trim();
      if (!query) return;
      runAnalysis(query);
    });

    // 페이지를 다시 열었을 때, 숨겨져 있던 동안 0크기로 그려졌던 차트를 재요청 없이
    // 이미 받아둔 결과로만 다시 그린다 (서버 재호출 없음).
    clusterRefresh = () => {
      if (lastResult) {
        renderComparisonChart(document.getElementById("clusterChart"), lastResult.months, lastResult.before, lastResult.after);
      }
    };
  }

  const PAGE_BREADCRUMB_LABEL = {
    "page-dashboard": "전체현황",
    "page-cluster": "유사 건물 조건 설정 및 분석",
    "page-simulator": "쿨루프 시뮬레이션",
  };

  function initPageNav() {
    const navButtons = document.querySelectorAll(".railbtn[data-page]");
    const pages = document.querySelectorAll(".page-view");
    const breadcrumbCurrent = document.getElementById("breadcrumbCurrent");

    navButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        const targetId = btn.getAttribute("data-page");
        pages.forEach((page) => {
          page.hidden = page.id !== targetId;
        });
        navButtons.forEach((b) => b.classList.toggle("active", b === btn));
        if (breadcrumbCurrent && PAGE_BREADCRUMB_LABEL[targetId]) {
          breadcrumbCurrent.textContent = PAGE_BREADCRUMB_LABEL[targetId];
        }
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
