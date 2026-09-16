/*
 * Cool Roof Thermal Simulator — SIMULATOR.MD 명세 구현.
 * 외부 애니메이션 라이브러리 없이 단일 requestAnimationFrame 루프로 동작한다.
 */
(function () {
  const NS = "http://www.w3.org/2000/svg";

  const SUN = { x: 197, y: 81 };
  const ROOF_LEFT_START = { x: 302, y: 230 };
  const ROOF_LEFT_END = { x: 512, y: 163 };

  const ABSORPTIVITY = { Dark: 0.65, White: 0.15 };
  const BADGE_COLOR = { Dark: "#C5221F", White: "#1A73E8" };
  const ROOF_TEMP_COLOR = { cold: [0x1f, 0x2a, 0x37], hot: [0x8e, 0x1b, 0x1b] }; // 20°C / 85°C

  const MAX_PHOTONS = 18;

  const scene = document.getElementById("crtsScene");
  const roofBody = document.getElementById("crtsRoofBody");
  const particlesGroup = document.getElementById("crtsParticles");
  const badgeRect = document.getElementById("crtsBadgeRect");
  const badgeTail = document.getElementById("crtsBadgeTail");
  const badgeText = document.getElementById("crtsBadgeText");
  const tempValueEl = document.getElementById("crtsTempValue");
  const intensityInput = document.getElementById("crtsIntensity");
  const intensityValueBox = document.getElementById("crtsIntensityValue");
  const roofTypeSelect = document.getElementById("crtsRoofType");

  const reducedMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  let solarIntensity = Number(intensityInput.value);
  let roofType = roofTypeSelect.value;
  let displayTemp = 20 + ABSORPTIVITY[roofType] * solarIntensity; // 초기값은 목표치로 바로 세팅
  let particles = [];
  let rafId = null;

  function targetTemp() {
    return 20 + ABSORPTIVITY[roofType] * solarIntensity;
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

  function renderStatic() {
    const shown = Math.round(displayTemp);
    const label = shown + "°C";
    tempValueEl.textContent = label;
    badgeText.textContent = label;

    const badgeColor = BADGE_COLOR[roofType];
    badgeRect.setAttribute("stroke", badgeColor);
    badgeTail.setAttribute("stroke", badgeColor);

    if (roofType === "White") {
      roofBody.setAttribute("fill", "url(#crtsRoofWhiteGrad)");
      roofBody.setAttribute("stroke", "#DDE1E6");
      roofBody.setAttribute("stroke-width", "1");
    } else {
      roofBody.setAttribute("fill", roofColorForTemp(displayTemp));
      roofBody.removeAttribute("stroke");
    }

    scene.setAttribute(
      "aria-label",
      `${roofType} roof, solar intensity ${solarIntensity}, surface temperature ${shown} degrees Celsius`
    );
  }

  // ---------------- 입자 시스템 ----------------

  function countPhotons() {
    let n = 0;
    for (let i = 0; i < particles.length; i++) {
      if (particles[i].type === "photon") n++;
    }
    return n;
  }

  function spawnPhoton() {
    if (countPhotons() >= MAX_PHOTONS) return;

    const angle = Math.random() * Math.PI * 2;
    const r = Math.sqrt(Math.random()) * 16;
    const sx = SUN.x + Math.cos(angle) * r;
    const sy = SUN.y + Math.sin(angle) * r;

    const tt = 0.2 + Math.random() * 0.6;
    const tx = ROOF_LEFT_START.x + (ROOF_LEFT_END.x - ROOF_LEFT_START.x) * tt;
    const ty = ROOF_LEFT_START.y + (ROOF_LEFT_END.y - ROOF_LEFT_START.y) * tt;

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

    particles.push({ type: "photon", kind: "incoming", x: sx, y: sy, tx, ty, vx, vy, r: radius, el });
  }

  // 광자가 지붕에 도달하면(Dark) 같은 객체/같은 배열 슬롯을 그대로 두고
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

  // 광자가 지붕에 도달하면 같은 객체/같은 배열 슬롯을 그대로 두고
  // kind만 'reflected'로 바꿔 반사광으로 전환한다 (별도 배열/로직 없음).
  function convertToReflected(p) {
    const angle = (-135 * Math.PI) / 180 + (Math.random() - 0.5) * ((30 * Math.PI) / 180);
    const speed = 2.0 + Math.random() * 0.8;

    p.vx = Math.cos(angle) * speed;
    p.vy = Math.sin(angle) * speed;
    p.angle = angle;
    p.len = 13 + Math.random() * 5;
    p.kind = "reflected";
    p.life = 70;
    p.maxLife = 70;

    console.log("[reflect] vx=" + p.vx.toFixed(3) + " vy=" + p.vy.toFixed(3));

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
        if (roofType === "Dark") {
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

  function updateParticle(p) {
    return updatePhoton(p);
  }

  let tickCount = 0;

  function tick() {
    displayTemp += (targetTemp() - displayTemp) * 0.12;

    if (solarIntensity > 0 && Math.random() < (solarIntensity / 100) * 0.14) {
      spawnPhoton();
    }

    particles = particles.filter(updateParticle);
    renderStatic();

    tickCount++;
    if (tickCount % 60 === 0) {
      const heatCount = particles.filter((p) => p.kind === "heat").length;
      console.log("[heat] count=" + heatCount);
    }

    rafId = requestAnimationFrame(tick);
  }

  function handleIntensityChange() {
    solarIntensity = Number(intensityInput.value);
    intensityValueBox.textContent = String(solarIntensity);
    intensityInput.setAttribute("aria-valuetext", String(solarIntensity));
    setSliderFill();
    if (reducedMotion) {
      displayTemp = targetTemp();
      renderStatic();
    }
  }

  function handleRoofTypeChange() {
    roofType = roofTypeSelect.value;
    // 타입 전환 시 이전 타입의 입자를 한 프레임도 남기지 않고 즉시 전부 제거,
    // 온도·지붕색·배지색도 보간 없이 즉시 스냅한다 (FIXSI.MD 수정 2 — 이전 명세 무효화).
    particles = [];
    particlesGroup.innerHTML = "";
    displayTemp = targetTemp();
    renderStatic();
  }

  intensityInput.addEventListener("input", handleIntensityChange);
  intensityInput.addEventListener("change", handleIntensityChange);
  roofTypeSelect.addEventListener("change", handleRoofTypeChange);

  setSliderFill();
  renderStatic();

  if (!reducedMotion) {
    rafId = requestAnimationFrame(tick);
  }

  window.addEventListener("beforeunload", () => {
    if (rafId) cancelAnimationFrame(rafId);
  });
})();
