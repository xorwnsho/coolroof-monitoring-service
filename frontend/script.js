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

  function showClusterError(message, elementId = "clusterError") {
    const errorEl = document.getElementById(elementId);
    if (!errorEl) return;
    errorEl.textContent = message;
    errorEl.hidden = false;
  }

  function hideClusterError(elementId = "clusterError") {
    const errorEl = document.getElementById(elementId);
    if (errorEl) errorEl.hidden = true;
  }

  let clusterRefresh = null;

  // ---------------- 01. 내 건물 선택 (건물명/주소 검색 → 건축물대장 실측 정보) ----------------

  function extractYear(dateValue) {
    if (!dateValue) return "-";
    if (Array.isArray(dateValue)) return `${dateValue[0]}년`;
    const year = String(dateValue).split("-")[0];
    return year ? `${year}년` : "-";
  }

  function parseYear(dateValue) {
    if (!dateValue) return null;
    if (Array.isArray(dateValue)) return dateValue[0];
    const year = parseInt(String(dateValue).split("-")[0], 10);
    return Number.isNaN(year) ? null : year;
  }

  function regionFromAddress(building, addressName) {
    const address = building.newPlatPlc || building.platPlc || addressName || "";
    return address.trim().split(/\s+/).slice(0, 2).join(" ");
  }

  // 아파트 단지처럼 한 지번에 동이 여러 개 등록된 경우(101동/102동/유치원 등) 구분해서 보여준다.
  // "주건축물" 같은 원 표준동 표기는 단일 건물엔 의미가 없어서 붙이지 않는다.
  function displayBuildingName(b) {
    const name = b.bldNm || "(이름 없음)";
    if (b.dongNm && !b.dongNm.includes("주건축물")) {
      return `${name} ${b.dongNm}`;
    }
    return name;
  }

  function initBuildingSearch() {
    const input = document.getElementById("buildingSearchInput");
    const searchBtn = document.getElementById("buildingSearchBtn");
    if (!input || !searchBtn) return;

    const resultsEl = document.getElementById("buildingSearchResults");
    const selectedWrap = document.getElementById("buildingSelectedWrap");
    const loadingEl = document.getElementById("clusterLoading");
    const similarCard = document.getElementById("similarConditionsCard");

    let selectedBuilding = null;
    let selectedRegion = null;

    function selectBuilding(item) {
      resultsEl.hidden = true;
      resultsEl.innerHTML = "";

      const b = item.building;
      selectedBuilding = b;
      selectedRegion = regionFromAddress(b, item.addressName);

      document.getElementById("buildingSelectedName").textContent = displayBuildingName(b) || item.addressName;
      document.getElementById("buildingUsage").textContent = b.mainPurpsCdNm || "-";
      document.getElementById("buildingYear").textContent = extractYear(b.useApprovalDate);
      document.getElementById("buildingFloors").textContent = b.floorCount != null ? `${b.floorCount}층` : "-";
      document.getElementById("buildingRoofArea").textContent =
          b.roofFootprintArea != null ? `${Math.round(b.roofFootprintArea)} m²` : "정보 없음";
      document.getElementById("buildingRoofType").textContent = b.roofType || "-";
      document.getElementById("buildingStructure").textContent = b.structureType || "-";
      selectedWrap.hidden = false;
      if (similarCard) similarCard.hidden = false;

      const resultWrap = document.getElementById("clusterResultWrap");
      if (resultWrap) resultWrap.hidden = true;
    }

    function renderResults(items) {
      resultsEl.innerHTML = "";
      if (items.length === 1) {
        selectBuilding(items[0]);
        return;
      }
      items.forEach((item) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "building-search-result-item";
        btn.innerHTML = `<span class="name">${displayBuildingName(item.building)}</span>` +
            `<span class="addr">${item.building.newPlatPlc || item.building.platPlc || item.addressName}</span>`;
        btn.addEventListener("click", () => selectBuilding(item));
        resultsEl.appendChild(btn);
      });
      resultsEl.hidden = false;
      selectedWrap.hidden = true;
    }

    async function runSearch(query) {
      hideClusterError();
      resultsEl.hidden = true;
      selectedWrap.hidden = true;
      if (loadingEl) loadingEl.hidden = false;
      try {
        const res = await fetch(`${CLUSTER_API_BASE}/api/buildings/search?query=${encodeURIComponent(query)}`);
        const data = await res.json();
        if (!res.ok) {
          showClusterError(data.error || "검색에 실패했습니다. 다시 시도해 주세요.");
          return;
        }
        renderResults(data);
      } catch (err) {
        showClusterError("서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      } finally {
        if (loadingEl) loadingEl.hidden = true;
      }
    }

    searchBtn.addEventListener("click", () => {
      const query = input.value.trim();
      if (query) runSearch(query);
    });
    input.addEventListener("keydown", (e) => {
      if (e.key !== "Enter") return;
      e.preventDefault();
      const query = input.value.trim();
      if (query) runSearch(query);
    });

    // ---------------- 02. 유사 조건 ----------------

    const toggleButtons = document.querySelectorAll(".condition-toggle");
    toggleButtons.forEach((btn) => {
      btn.addEventListener("click", () => btn.classList.toggle("active"));
    });

    const findSimilarBtn = document.getElementById("findSimilarBtn");
    const similarLoadingEl = document.getElementById("similarLoading");
    if (findSimilarBtn) {
      findSimilarBtn.addEventListener("click", async () => {
        if (!selectedBuilding) return;
        hideClusterError("similarError");
        findSimilarBtn.disabled = true;
        findSimilarBtn.textContent = "찾는 중...";
        if (similarLoadingEl) similarLoadingEl.hidden = false;
        const resultWrap = document.getElementById("clusterResultWrap");
        if (resultWrap) resultWrap.hidden = true;

        const cond = {};
        toggleButtons.forEach((btn) => {
          cond[btn.getAttribute("data-cond")] = btn.classList.contains("active");
        });

        const payload = {
          region: selectedRegion,
          structureType: selectedBuilding.structureType,
          usage: selectedBuilding.mainPurpsCdNm,
          sameUsage: !!cond.sameUsage,
          builtYear: parseYear(selectedBuilding.useApprovalDate),
          yearTolerance: !!cond.yearTolerance,
          floors: selectedBuilding.floorCount,
          floorTolerance: !!cond.floorTolerance,
          roofArea: selectedBuilding.roofFootprintArea,
          roofAreaTolerance: !!cond.roofAreaTolerance,
          roofType: selectedBuilding.roofType,
          sameRoofType: !!cond.sameRoofType,
        };

        try {
          const res = await fetch(`${CLUSTER_API_BASE}/api/buildings/similar`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          });
          const data = await res.json();
          if (!res.ok) {
            showClusterError(data.error || "유사 건물 찾기에 실패했습니다. 다시 시도해 주세요.", "similarError");
            return;
          }
          renderClusterResult(data);
        } catch (err) {
          showClusterError("서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", "similarError");
        } finally {
          findSimilarBtn.disabled = false;
          findSimilarBtn.textContent = "유사 건물 찾기";
          if (similarLoadingEl) similarLoadingEl.hidden = true;
        }
      });
    }
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
    initBuildingSearch();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
