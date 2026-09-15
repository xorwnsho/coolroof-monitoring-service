/*
 * Cool Roof Thermal Simulator — SIMULATOR.MD 명세 구현.
 * 외부 애니메이션 라이브러리 없이 단일 requestAnimationFrame 루프로 동작한다.
 */
(function () {
  const NS = "http://www.w3.org/2000/svg";

  const SUN = { x: 197, y: 81 };
  const ROOF_LEFT_START = { x: 302, y: 230 };
  const ROOF_RIDGE = { x: 512, y: 163 };
  const ROOF_RIGHT_END = { x: 726, y: 230 };
  const ROOF_LEFT_LEN = Math.hypot(ROOF_RIDGE.x - ROOF_LEFT_START.x, ROOF_RIDGE.y - ROOF_LEFT_START.y);
  const ROOF_RIGHT_LEN = Math.hypot(ROOF_RIGHT_END.x - ROOF_RIDGE.x, ROOF_RIGHT_END.y - ROOF_RIDGE.y);

  const ABSORPTIVITY = { Dark: 0.65, White: 0.15 };
  const BADGE_COLOR = { Dark: "#C5221F", White: "#1A73E8" };
  const ROOF_TEMP_COLOR = { cold: [0x1f, 0x2a, 0x37], hot: [0x8e, 0x1b, 0x1b] }; // 20°C / 85°C

  const MAX_PHOTONS = 40;
  const HEAT_LIFESPAN = 55;
  const REFLECT_LIFESPAN = 110;

  const scene = document.getElementById("crtsScene");
  const roofLine = document.getElementById("crtsRoofLine");
  const roofOutline = document.getElementById("crtsRoofOutline");
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
    badgeTail.setAttribute("fill", badgeColor);

    if (roofType === "White") {
      roofLine.setAttribute("stroke", "url(#crtsRoofWhiteGrad)");
      roofOutline.setAttribute("opacity", "1");
    } else {
      roofLine.setAttribute("stroke", roofColorForTemp(displayTemp));
      roofOutline.setAttribute("opacity", "0");
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

  function randomRoofPoint() {
    const totalLen = ROOF_LEFT_LEN + ROOF_RIGHT_LEN;
    const d = Math.random() * totalLen;
    if (d <= ROOF_LEFT_LEN) {
      const tt = d / ROOF_LEFT_LEN;
      return {
        x: ROOF_LEFT_START.x + (ROOF_RIDGE.x - ROOF_LEFT_START.x) * tt,
        y: ROOF_LEFT_START.y + (ROOF_RIDGE.y - ROOF_LEFT_START.y) * tt,
      };
    }
    const tt = (d - ROOF_LEFT_LEN) / ROOF_RIGHT_LEN;
    return {
      x: ROOF_RIDGE.x + (ROOF_RIGHT_END.x - ROOF_RIDGE.x) * tt,
      y: ROOF_RIDGE.y + (ROOF_RIGHT_END.y - ROOF_RIDGE.y) * tt,
    };
  }

  function spawnPhoton() {
    if (countPhotons() >= MAX_PHOTONS) return;

    const angle = Math.random() * Math.PI * 2;
    const r = Math.sqrt(Math.random()) * 30;
    const sx = SUN.x + Math.cos(angle) * r;
    const sy = SUN.y + Math.sin(angle) * r;

    const target = randomRoofPoint();
    const tx = target.x;
    const ty = target.y;

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

    particles.push({ type: "photon", x: sx, y: sy, tx, ty, vx, vy, el });
  }

  function spawnHeat(x, y) {
    const el = document.createElementNS(NS, "circle");
    el.setAttribute("fill", "rgba(176,58,58,0.16)");
    el.setAttribute("cx", x.toFixed(1));
    el.setAttribute("cy", y.toFixed(1));
    el.setAttribute("r", "18");
    particlesGroup.appendChild(el);

    particles.push({
      type: "heat",
      x, y,
      vx: -0.7 - Math.random() * 1.1,
      vy: -1.2 - Math.random() * 1.0,
      radius: 18,
      age: 0,
      el,
    });
  }

  function setReflectGeometry(p) {
    const hdx = (Math.cos(p.angle) * p.length) / 2;
    const hdy = (Math.sin(p.angle) * p.length) / 2;
    p.el.setAttribute("x1", (p.x - hdx).toFixed(1));
    p.el.setAttribute("y1", (p.y - hdy).toFixed(1));
    p.el.setAttribute("x2", (p.x + hdx).toFixed(1));
    p.el.setAttribute("y2", (p.y + hdy).toFixed(1));
  }

  function spawnReflect(x, y) {
    const angleDeg = -135 + (Math.random() * 30 - 15);
    const angle = (angleDeg * Math.PI) / 180;
    const length = 26 + Math.random() * 10;
    const speed = 2.4 + Math.random() * 1.2;
    const color = "#000000";

    const el = document.createElementNS(NS, "line");
    el.setAttribute("stroke", color);
    el.setAttribute("stroke-width", "1.5");
    el.setAttribute("stroke-linecap", "butt");
    el.setAttribute("opacity", "1");
    particlesGroup.appendChild(el);

    const p = { type: "reflect", x, y, angle, length, speed, age: 0, el };
    setReflectGeometry(p);
    particles.push(p);
  }

  function spawnCollision(x, y) {
    if (roofType === "Dark") {
      spawnHeat(x, y);
    } else {
      spawnReflect(x, y);
    }
  }

  function updatePhoton(p) {
    p.x += p.vx;
    p.y += p.vy;
    const remaining = Math.hypot(p.tx - p.x, p.ty - p.y);
    const speed = Math.hypot(p.vx, p.vy);
    if (remaining <= speed) {
      spawnCollision(p.tx, p.ty);
      p.el.remove();
      return false;
    }
    p.el.setAttribute("cx", p.x.toFixed(1));
    p.el.setAttribute("cy", p.y.toFixed(1));
    return true;
  }

  function updateHeat(p) {
    p.age++;
    p.x += p.vx;
    p.y += p.vy;
    p.radius = Math.min(48, p.radius + 0.18);

    let opacity = 1;
    const fadeStart = HEAT_LIFESPAN * 0.6;
    if (p.age > fadeStart) {
      opacity = Math.max(0, 1 - (p.age - fadeStart) / (HEAT_LIFESPAN - fadeStart));
    }
    p.el.setAttribute("cx", p.x.toFixed(1));
    p.el.setAttribute("cy", p.y.toFixed(1));
    p.el.setAttribute("r", p.radius.toFixed(1));
    p.el.setAttribute("opacity", opacity.toFixed(2));

    if (p.age >= HEAT_LIFESPAN) {
      p.el.remove();
      return false;
    }
    return true;
  }

  function updateReflect(p) {
    p.age++;
    p.x += Math.cos(p.angle) * p.speed;
    p.y += Math.sin(p.angle) * p.speed;

    let opacity = 1;
    const fadeStart = REFLECT_LIFESPAN * 0.5;
    if (p.age > fadeStart) {
      opacity = Math.max(0, 1 - (p.age - fadeStart) / (REFLECT_LIFESPAN - fadeStart));
    }
    setReflectGeometry(p);
    p.el.setAttribute("opacity", opacity.toFixed(2));

    if (p.age >= REFLECT_LIFESPAN) {
      p.el.remove();
      return false;
    }
    return true;
  }

  function updateParticle(p) {
    if (p.type === "photon") return updatePhoton(p);
    if (p.type === "heat") return updateHeat(p);
    return updateReflect(p);
  }

  function tick() {
    displayTemp += (targetTemp() - displayTemp) * 0.12;

    if (solarIntensity > 0 && Math.random() < (solarIntensity / 100) * 0.35) {
      spawnPhoton();
    }

    particles = particles.filter(updateParticle);
    renderStatic();

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
    roofType = roofTypeSelect.value; // 기존 입자는 각자 수명까지 유지, 신규 생성분만 새 타입 적용
    if (reducedMotion) {
      displayTemp = targetTemp();
      renderStatic();
    }
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
