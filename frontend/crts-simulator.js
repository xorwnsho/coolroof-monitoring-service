/*
 * Cool Roof Thermal Simulator — "두 집 나란히 비교" 구조.
 * 왼쪽 = 일반 지붕(기존 Dark 규칙 고정), 오른쪽 = 쿨루프(기존 White 규칙 고정).
 * 입자 시스템(광자·열기 뭉치·반사광을 하나의 배열 + kind 필드로 관리)은 기존 그대로
 * 재사용하고, house 필드만 추가해 어느 집 소속인지 구분한다.
 * 외부 애니메이션 라이브러리 없이 단일 requestAnimationFrame 루프로 동작한다.
 */
(function () {
  const NS = "http://www.w3.org/2000/svg";

  const SUN = { x: 400, y: 72 };

  // 각 집의 "태양을 향한 경사면"만 광자 목표 지점으로 쓴다.
  const HOUSES = {
    dark: {
      slopeStart: { x: 200, y: 180 }, // 용마루
      slopeEnd: { x: 350, y: 235 }, // 처마(오른쪽 경사면)
      absorptivity: 0.65,
      badgeColor: "#C5221F",
    },
    white: {
      slopeStart: { x: 450, y: 235 }, // 처마(왼쪽 경사면)
      slopeEnd: { x: 600, y: 180 }, // 용마루
      absorptivity: 0.25, // 환경부 쿨루프 가이드(2024) 실측치 기준
      badgeColor: "#1A73E8",
    },
  };

  const ROOF_TEMP_COLOR = { cold: [0x1f, 0x2a, 0x37], hot: [0x8e, 0x1b, 0x1b] }; // 20°C / 85°C

  const MAX_PHOTONS_PER_HOUSE = 18;
  const SPAWN_PROB_COEF = 0.20;

  const scene = document.getElementById("crtsScene");
  const roofBodyDark = document.getElementById("crtsRoofBodyDark");
  const particlesGroup = document.getElementById("crtsParticles");
  const badgeTextDark = document.getElementById("crtsBadgeTextDark");
  const badgeTextWhite = document.getElementById("crtsBadgeTextWhite");
  const darkValueEl = document.getElementById("crtsDarkValue");
  const whiteValueEl = document.getElementById("crtsWhiteValue");
  const diffValueEl = document.getElementById("crtsDiffValue");
  const intensityInput = document.getElementById("crtsIntensity");
  const intensityValueBox = document.getElementById("crtsIntensityValue");
  const presetButtons = document.querySelectorAll(".crts-preset-btn");

  const reducedMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  let solarIntensity = Number(intensityInput.value);
  let darkDisplayTemp = 20 + HOUSES.dark.absorptivity * solarIntensity;
  let whiteDisplayTemp = 20 + HOUSES.white.absorptivity * solarIntensity;
  let particles = [];
  let rafId = null;

  function targetTemp(house) {
    return 20 + HOUSES[house].absorptivity * solarIntensity;
  }

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function roofColorForTemp(temp) {
    const t = Math.min(1, Math.max(0, (temp - 20) / (85 - 20)));
    const c = ROOF_TEMP_COLOR;
    const r = Math.round(lerp(c.cold[0], c.hot[0], t));
    const g = Math.round(lerp(c.cold[1], c.hot[1], t));
    const b = Math.round(lerp(c.cold[2], c.hot[2], t));
    return `rgb(${r},${g},${b})`;
  }

  function setSliderFill() {
    intensityInput.style.setProperty("--crts-fill", solarIntensity + "%");
  }

  function formatDiff(diff) {
    const rounded = Math.round(diff);
    return rounded === 0 ? "0°C" : "−" + rounded + "°C";
  }

  function renderStatic() {
    const darkShown = Math.round(darkDisplayTemp);
    const whiteShown = Math.round(whiteDisplayTemp);
    const diffShown = darkDisplayTemp - whiteDisplayTemp;

    const darkLabel = darkShown + "°C";
    const whiteLabel = whiteShown + "°C";
    darkValueEl.textContent = darkLabel;
    whiteValueEl.textContent = whiteLabel;
    diffValueEl.textContent = formatDiff(diffShown);
    badgeTextDark.textContent = darkLabel;
    badgeTextWhite.textContent = whiteLabel;

    // 왼쪽 집은 항상 일반 지붕(고정 색), 오른쪽 집은 항상 쿨루프(고정 그라데이션) — 전환 없음.
    roofBodyDark.setAttribute("fill", roofColorForTemp(darkDisplayTemp));

    scene.setAttribute(
      "aria-label",
      `Dark roof ${darkShown} degrees Celsius, cool roof ${whiteShown} degrees Celsius, solar intensity ${solarIntensity}`
    );
  }

  // ---------------- 입자 시스템 (광자·열기 뭉치·반사광 하나의 배열 + kind/house 필드) ----------------

  function countPhotons(house) {
    let n = 0;
    for (let i = 0; i < particles.length; i++) {
      if (particles[i].type === "photon" && particles[i].house === house) n++;
    }
    return n;
  }

  function spawnPhoton(house) {
    if (countPhotons(house) >= MAX_PHOTONS_PER_HOUSE) return;

    const angle = Math.random() * Math.PI * 2;
    const r = Math.sqrt(Math.random()) * 16;
    const sx = SUN.x + Math.cos(angle) * r;
    const sy = SUN.y + Math.sin(angle) * r;

    const slope = HOUSES[house];
    const tt = 0.2 + Math.random() * 0.6;
    const tx = slope.slopeStart.x + (slope.slopeEnd.x - slope.slopeStart.x) * tt;
    const ty = slope.slopeStart.y + (slope.slopeEnd.y - slope.slopeStart.y) * tt;

    const dx = tx - sx;
    const dy = ty - sy;
    const dist = Math.hypot(dx, dy) || 1;
    const speed = 1.8 + Math.random() * 0.8;
    const vx = (dx / dist) * speed;
    const vy = (dy / dist) * speed;
    const radius = 4 + Math.random() * 2;

    const el = document.createElementNS(NS, "circle");
    el.setAttribute("r", radius.toFixed(1));
    el.setAttribute("fill", "#F9A825");
    el.setAttribute("cx", sx.toFixed(1));
    el.setAttribute("cy", sy.toFixed(1));
    particlesGroup.appendChild(el);

    particles.push({ type: "photon", kind: "incoming", house, x: sx, y: sy, tx, ty, vx, vy, r: radius, el });
  }

  // 광자가 지붕에 도달하면(왼쪽 집) 같은 객체/같은 배열 슬롯을 그대로 두고
  // kind만 'heat'로 바꿔 열기 뭉치로 전환한다 (별도 배열/로직 없음).
  function convertToHeat(p) {
    // 열기 뭉치는 지붕에 흡수되는 열이므로 이동하지 않는다. 제자리에서만 커지고 옅어진다.
    p.vx = 0;
    p.vy = 0;
    p.r = 9;
    p.growth = 0.23;
    p.kind = "heat";
    p.life = 60;
    p.maxLife = 60;

    p.el.setAttribute("fill", "#B03A3A");
  }

  function renderHeat(p) {
    p.el.setAttribute("cx", p.x.toFixed(1));
    p.el.setAttribute("cy", p.y.toFixed(1));
    p.el.setAttribute("r", p.r.toFixed(1));
    p.el.setAttribute("opacity", (0.16 * Math.max(0, p.life / p.maxLife)).toFixed(3));
  }

  // 광자가 지붕에 도달하면(오른쪽 집) 같은 객체/같은 배열 슬롯을 그대로 두고
  // kind만 'reflected'로 바꿔 반사광으로 전환한다 (별도 배열/로직 없음).
  function convertToReflected(p) {
    // -135°는 오른쪽 집 기준 좌상단 = 태양 방향이라 그대로 쓴다.
    const angle = (-135 * Math.PI) / 180 + (Math.random() - 0.5) * ((30 * Math.PI) / 180);
    const speed = 2.0 + Math.random() * 0.8;

    p.vx = Math.cos(angle) * speed;
    p.vy = Math.sin(angle) * speed;
    p.angle = angle;
    p.len = 13 + Math.random() * 5;
    p.kind = "reflected";
    p.life = 70;
    p.maxLife = 70;

    const line = document.createElementNS(NS, "line");
    line.setAttribute("stroke", "#000000");
    line.setAttribute("stroke-width", "1.5");
    line.setAttribute("stroke-linecap", "butt");
    particlesGroup.replaceChild(line, p.el);
    p.el = line;
  }

  function renderIncoming(p) {
    p.el.setAttribute("cx", p.x.toFixed(1));
    p.el.setAttribute("cy", p.y.toFixed(1));
  }

  function renderReflected(p) {
    // x1,y1,x2,y2를 캐시하지 않고 매 렌더마다 p.x/p.y에서 새로 계산한다.
    const x1 = p.x;
    const y1 = p.y;
    const x2 = p.x + Math.cos(p.angle) * p.len;
    const y2 = p.y + Math.sin(p.angle) * p.len;
    p.el.setAttribute("x1", x1.toFixed(1));
    p.el.setAttribute("y1", y1.toFixed(1));
    p.el.setAttribute("x2", x2.toFixed(1));
    p.el.setAttribute("y2", y2.toFixed(1));
    p.el.setAttribute("opacity", Math.max(0, p.life / p.maxLife).toFixed(2));
  }

  function updatePhoton(p) {
    // incoming/reflected/heat 공통: 완전히 동일한 x += vx, y += vy 경로.
    p.x += p.vx;
    p.y += p.vy;

    if (p.kind === "incoming") {
      const remaining = Math.hypot(p.tx - p.x, p.ty - p.y);
      const speed = Math.hypot(p.vx, p.vy);
      if (remaining <= speed) {
        p.x = p.tx;
        p.y = p.ty;
        if (p.house === "dark") {
          convertToHeat(p);
          renderHeat(p);
        } else {
          convertToReflected(p);
          renderReflected(p);
        }
        return true;
      }
      renderIncoming(p);
      return true;
    }

    if (p.kind === "heat") {
      // x, y는 갱신하지 않는다 (제자리 고정).
      p.r = Math.min(32, p.r + p.growth);
      p.life -= 1;
      renderHeat(p);
      if (p.life <= 0) {
        p.el.remove();
        return false;
      }
      return true;
    }

    // kind === "reflected"
    p.life -= 1;
    renderReflected(p);
    const offscreen = p.x < -50 || p.y < -50;
    if (p.life <= 0 || offscreen) {
      p.el.remove();
      return false;
    }
    return true;
  }

  function tick() {
    darkDisplayTemp += (targetTemp("dark") - darkDisplayTemp) * 0.12;
    whiteDisplayTemp += (targetTemp("white") - whiteDisplayTemp) * 0.12;

    if (solarIntensity > 0) {
      // 두 집이 같은 햇빛을 받는다는 의미이므로 생성 확률은 동일 — 단, 매 프레임 각자 독립 판정.
      if (Math.random() < (solarIntensity / 100) * SPAWN_PROB_COEF) spawnPhoton("dark");
      if (Math.random() < (solarIntensity / 100) * SPAWN_PROB_COEF) spawnPhoton("white");
    }

    particles = particles.filter(updatePhoton);
    renderStatic();

    rafId = requestAnimationFrame(tick);
  }

  function wattsLabel() {
    return solarIntensity * 10 + " W/m²";
  }

  function updatePresetActiveState() {
    presetButtons.forEach((btn) => {
      const presetValue = Number(btn.getAttribute("data-intensity"));
      btn.classList.toggle("active", presetValue === solarIntensity);
    });
  }

  function handleIntensityChange() {
    solarIntensity = Number(intensityInput.value);
    intensityValueBox.textContent = wattsLabel();
    intensityInput.setAttribute("aria-valuetext", wattsLabel());
    setSliderFill();
    updatePresetActiveState();
    if (reducedMotion) {
      darkDisplayTemp = targetTemp("dark");
      whiteDisplayTemp = targetTemp("white");
      renderStatic();
    }
  }

  intensityInput.addEventListener("input", handleIntensityChange);
  intensityInput.addEventListener("change", handleIntensityChange);

  presetButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      intensityInput.value = btn.getAttribute("data-intensity");
      handleIntensityChange();
    });
  });

  setSliderFill();
  updatePresetActiveState();
  renderStatic();

  if (!reducedMotion) {
    rafId = requestAnimationFrame(tick);
  }

  window.addEventListener("beforeunload", () => {
    if (rafId) cancelAnimationFrame(rafId);
  });
})();
