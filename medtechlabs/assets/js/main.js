/* ==========================================================================
   MedTech Labs — motion engine
   Vanilla, dependency-free. Everything degrades gracefully without JS
   and switches off under prefers-reduced-motion.
   ========================================================================== */
(() => {
  'use strict';

  const REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const COARSE  = window.matchMedia('(hover: none), (pointer: coarse)').matches;
  const $  = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerp  = (a, b, t) => a + (b - a) * t;

  /* ------------------------------------------------------------ rAF bus */
  const ticks = [];
  const onTick = fn => ticks.push(fn);
  let raf = 0;
  const loop = () => { for (const fn of ticks) fn(); raf = requestAnimationFrame(loop); };
  const startLoop = () => { if (!raf) raf = requestAnimationFrame(loop); };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { cancelAnimationFrame(raf); raf = 0; } else startLoop();
  });

  /* ------------------------------------------------------------ scroll state */
  const S = { y: window.scrollY, prev: window.scrollY, vel: 0, smoothVel: 0, dir: 1, h: innerHeight };
  const measure = () => { S.h = innerHeight; };
  addEventListener('resize', measure, { passive: true });
  onTick(() => {
    S.y = window.scrollY;
    S.vel = S.y - S.prev;
    if (S.vel !== 0) S.dir = S.vel > 0 ? 1 : -1;
    S.smoothVel = lerp(S.smoothVel, S.vel, 0.12);
    S.prev = S.y;
  });

  /* ==========================================================================
     1. Preloader — counts up, assembles the dot mark, hands off to the page
     ========================================================================== */
  function preloader() {
    const el = $('.preloader');
    if (!el) { document.body.classList.add('is-ready'); return Promise.resolve(); }
    if (REDUCED) { el.remove(); document.body.classList.add('is-ready'); return Promise.resolve(); }

    const num  = $('.preloader__count', el);
    const bar  = $('.preloader__bar i', el);
    const dots = $$('.preloader__mark circle', el);
    document.body.classList.add('is-locked');

    dots.forEach((d, i) => {
      d.style.opacity = '0';
      d.style.transform = 'scale(0)';
      d.style.transition = `opacity .5s ease ${i * 26}ms, transform .7s cubic-bezier(.34,1.56,.64,1) ${i * 26}ms`;
    });
    requestAnimationFrame(() => dots.forEach(d => { d.style.opacity = '1'; d.style.transform = 'scale(1)'; }));

    return new Promise(res => {
      let p = 0, target = 0, done = false;
      const settle = () => {
        if (done) return; done = true;
        el.classList.add('is-done');
        el.style.opacity = '0';
        setTimeout(() => { el.remove(); }, 520);
        document.body.classList.remove('is-locked');
        document.body.classList.add('is-ready');
        res();
      };
      // fake-but-honest progress: tracks real load, never stalls
      const step = () => {
        target = document.readyState === 'complete' ? 100 : Math.min(92, target + Math.random() * 9);
        p = lerp(p, target, 0.14);
        const v = Math.round(p);
        if (num) num.textContent = String(v).padStart(3, '0');
        if (bar) bar.style.width = v + '%';
        if (v >= 100) { setTimeout(settle, 260); return; }
        requestAnimationFrame(step);
      };
      step();
      setTimeout(settle, 4200); // hard ceiling — never trap the visitor
    });
  }

  /* ==========================================================================
     2. Page transitions — wipe out, navigate, wipe in
     ========================================================================== */
  function transitions() {
    const tr = $('.transition');
    if (!tr) return;
    // play the "in" (reveal) leg on arrival
    if (!REDUCED) {
      tr.classList.add('is-out');
      setTimeout(() => tr.classList.remove('is-out'), 1100);
    }
    if (REDUCED) return;

    document.addEventListener('click', e => {
      const a = e.target.closest('a');
      if (!a) return;
      const href = a.getAttribute('href') || '';
      if (a.target === '_blank' || a.hasAttribute('download')) return;
      if (!href || href.startsWith('#') || href.startsWith('mailto:') || href.startsWith('tel:')) return;
      if (a.origin && a.origin !== location.origin) return;
      if (a.pathname === location.pathname) return;
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;

      e.preventDefault();
      document.body.classList.remove('menu-open');
      tr.classList.remove('is-out');
      tr.classList.add('is-in');
      setTimeout(() => { location.href = a.href; }, 780);
    });

    // returning via bfcache should not land on a covered screen
    addEventListener('pageshow', ev => { if (ev.persisted) tr.classList.remove('is-in'); });
  }

  /* ==========================================================================
     3. Text splitting — lines (mask + rise) and chars (stagger)
     ========================================================================== */
  function splitText() {
    $$('[data-split="lines"]').forEach(el => {
      if (el.dataset.done) return;
      const raw = el.innerHTML.split(/<br\s*\/?>/i).map(s => s.trim()).filter(Boolean);
      el.innerHTML = raw.map((l, i) =>
        `<span class="line" style="--i:${i}"><i>${l}</i></span>`).join('');
      el.dataset.done = '1';
    });

    $$('[data-split="chars"]').forEach(el => {
      if (el.dataset.done) return;
      let n = 0;
      const walk = node => {
        Array.from(node.childNodes).forEach(child => {
          if (child.nodeType === 3) {
            const frag = document.createDocumentFragment();
            // split on words so wrapping stays sane, then chars inside
            child.textContent.split(/(\s+)/).forEach(word => {
              if (!word) return;
              if (/^\s+$/.test(word)) { frag.appendChild(document.createTextNode(word)); return; }
              const w = document.createElement('span');
              w.style.display = 'inline-block';
              w.style.whiteSpace = 'nowrap';
              for (const ch of word) {
                const c = document.createElement('span');
                c.className = 'char';
                c.style.setProperty('--i', n++);
                c.textContent = ch;
                w.appendChild(c);
              }
              frag.appendChild(w);
            });
            node.replaceChild(frag, child);
          } else if (child.nodeType === 1 && !child.classList.contains('char')) {
            // gradient/clipped runs must stay intact — splitting them strips the
            // background off the children and the text renders transparent.
            if (child.classList.contains('accent-word') || child.hasAttribute('data-nosplit')) {
              const wrap = document.createElement('span');
              wrap.className = 'char';
              wrap.style.setProperty('--i', n++);
              node.replaceChild(wrap, child);
              wrap.appendChild(child);
            } else {
              walk(child);
            }
          }
        });
      };
      walk(el);
      el.dataset.done = '1';
    });
  }

  /* ==========================================================================
     4. Reveal on scroll
     ========================================================================== */
  function reveals() {
    const items = $$('[data-reveal], [data-clip], [data-split]');
    if (REDUCED || !('IntersectionObserver' in window)) {
      items.forEach(el => el.classList.add('is-in'));
      return;
    }
    const io = new IntersectionObserver(entries => {
      entries.forEach(en => {
        if (!en.isIntersecting) return;
        en.target.classList.add('is-in');
        io.unobserve(en.target);
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.08 });
    items.forEach(el => io.observe(el));
  }

  /* ==========================================================================
     5. Custom cursor + magnetic elements
     ========================================================================== */
  function cursor() {
    if (REDUCED || COARSE) return;
    const c = $('.cursor');
    if (!c) return;
    const dot = $('.cursor__dot', c), ring = $('.cursor__ring', c);
    let mx = innerWidth / 2, my = innerHeight / 2;
    let dx = mx, dy = my, rx = mx, ry = my;

    addEventListener('mousemove', e => {
      mx = e.clientX; my = e.clientY;
      document.body.classList.add('cursor-on');
    }, { passive: true });
    addEventListener('mouseleave', () => document.body.classList.remove('cursor-on'));

    onTick(() => {
      dx = lerp(dx, mx, 0.85); dy = lerp(dy, my, 0.85);
      rx = lerp(rx, mx, 0.16);  ry = lerp(ry, my, 0.16);
      dot.style.transform  = `translate3d(${dx}px, ${dy}px, 0)`;
      ring.style.transform = `translate3d(${rx}px, ${ry}px, 0)`;
    });

    const hoverSel = 'a, button, .card, .hslide, .chip, .statelist li, .acc__btn, .step, input, textarea, select';
    document.addEventListener('mouseover', e => {
      const t = e.target.closest(hoverSel);
      document.body.classList.toggle('cursor-hover', !!t);
      const label = t && t.dataset ? t.dataset.cursor : null;
      document.body.classList.toggle('cursor-view', !!label);
      ring.textContent = label || '';
    });

    /* magnetic pull */
    $$('[data-magnetic]').forEach(el => {
      const strength = parseFloat(el.dataset.magnetic) || 0.32;
      let tx = 0, ty = 0, cx = 0, cy = 0, active = false;
      el.addEventListener('mouseenter', () => { active = true; });
      el.addEventListener('mouseleave', () => { active = false; tx = ty = 0; });
      el.addEventListener('mousemove', e => {
        const r = el.getBoundingClientRect();
        tx = (e.clientX - (r.left + r.width / 2)) * strength;
        ty = (e.clientY - (r.top + r.height / 2)) * strength;
      });
      onTick(() => {
        if (!active && Math.abs(cx) < 0.05 && Math.abs(cy) < 0.05) return;
        cx = lerp(cx, tx, 0.18); cy = lerp(cy, ty, 0.18);
        el.style.transform = `translate3d(${cx}px, ${cy}px, 0)`;
      });
    });
  }

  /* ==========================================================================
     6. Nav — stick, auto-hide, mobile menu, scroll progress
     ========================================================================== */
  function nav() {
    const bar = $('.nav');
    const prog = $('.progress i');
    const burger = $('.nav__burger');

    if (burger) {
      burger.addEventListener('click', () => {
        const open = document.body.classList.toggle('menu-open');
        document.body.classList.toggle('is-locked', open);
        burger.setAttribute('aria-expanded', String(open));
      });
    }
    addEventListener('keydown', e => {
      if (e.key === 'Escape' && document.body.classList.contains('menu-open')) {
        document.body.classList.remove('menu-open', 'is-locked');
        if (burger) burger.setAttribute('aria-expanded', 'false');
      }
    });

    onTick(() => {
      if (bar) {
        bar.classList.toggle('is-stuck', S.y > 40);
        const hide = S.y > 460 && S.dir > 0 && !document.body.classList.contains('menu-open');
        bar.classList.toggle('is-hidden', hide);
      }
      if (prog) {
        const max = document.documentElement.scrollHeight - S.h;
        prog.style.transform = `scaleX(${max > 0 ? clamp(S.y / max, 0, 1) : 0})`;
      }
    });
  }

  /* ==========================================================================
     7. Marquees — infinite, direction-aware, velocity-reactive
     ========================================================================== */
  function marquees() {
    $$('.marquee').forEach(m => {
      const track = $('.marquee__track', m);
      const group = $('.marquee__group', track);
      if (!track || !group) return;

      // duplicate until the track comfortably covers 2x viewport
      const fill = () => {
        while (track.scrollWidth < m.offsetWidth * 2 + group.offsetWidth && track.children.length < 12) {
          track.appendChild(group.cloneNode(true));
        }
      };
      fill();
      addEventListener('resize', fill, { passive: true });

      if (REDUCED) return;
      const base = parseFloat(m.dataset.speed) || 0.6;
      const dirs = m.dataset.dir === 'right' ? -1 : 1;
      let x = 0, paused = false;
      m.addEventListener('mouseenter', () => { paused = true; });
      m.addEventListener('mouseleave', () => { paused = false; });

      onTick(() => {
        const w = group.offsetWidth;
        if (!w) return;
        const boost = clamp(Math.abs(S.smoothVel) * 0.16, 0, 7);
        const speed = (paused ? base * 0.18 : base + boost) * dirs;
        x -= speed;
        if (x <= -w) x += w;
        if (x > 0) x -= w;
        track.style.transform = `translate3d(${x}px, 0, 0)`;
      });
    });
  }

  /* ==========================================================================
     8. Parallax + scroll skew
     ========================================================================== */
  function parallax() {
    if (REDUCED) return;
    const items = $$('[data-parallax]').map(el => ({
      el, k: parseFloat(el.dataset.parallax) || 0.12, y: 0
    }));
    const skews = $$('[data-skew]');
    if (!items.length && !skews.length) return;

    onTick(() => {
      for (const it of items) {
        const r = it.el.getBoundingClientRect();
        if (r.bottom < -200 || r.top > S.h + 200) continue;
        const centre = r.top + r.height / 2 - S.h / 2;
        it.y = lerp(it.y, -centre * it.k, 0.1);
        it.el.style.transform = `translate3d(0, ${it.y.toFixed(2)}px, 0)`;
      }
      if (skews.length) {
        const sk = clamp(S.smoothVel * 0.055, -3.2, 3.2);
        for (const el of skews) el.style.transform = `skewY(${sk.toFixed(3)}deg)`;
      }
    });
  }

  /* ==========================================================================
     9. Story — pinned copy that lights up line by line with scroll
     ========================================================================== */
  function story() {
    const wrap = $('[data-story]');
    if (!wrap) return;
    const lines = $$('.story__line', wrap);
    if (!lines.length) return;
    if (REDUCED) { lines.forEach(l => l.classList.add('is-lit')); return; }

    onTick(() => {
      const r = wrap.getBoundingClientRect();
      if (r.bottom < 0 || r.top > S.h) return;
      // progress 0→1 as the block crosses the middle band of the viewport
      const p = clamp((S.h * 0.78 - r.top) / (r.height * 0.72 + S.h * 0.12), 0, 1);
      const lit = Math.round(p * (lines.length + 1));
      lines.forEach((l, i) => l.classList.toggle('is-lit', i < lit));
    });
  }

  /* ==========================================================================
     10. Horizontal pinned scroller
     ========================================================================== */
  function horizontalPin() {
    $$('.hpin').forEach(sec => {
      const track = $('.hpin__track', sec);
      const rail  = $('.hpin__rail i', sec);
      if (!track) return;

      if (REDUCED) { sec.style.height = 'auto'; return; }

      let dist = 0, cur = 0;
      const measurePin = () => {
        // NB: read the resolved gutter off a laid-out element — the custom
        // property itself is an unresolved clamp() string and parses to NaN.
        const gut = parseFloat(getComputedStyle(track).paddingRight) || 0;
        dist = Math.max(0, track.scrollWidth - innerWidth + gut);
        sec.style.height = (innerHeight + dist * 1.05) + 'px';
      };
      measurePin();
      addEventListener('resize', measurePin, { passive: true });
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(measurePin);

      onTick(() => {
        const r = sec.getBoundingClientRect();
        if (r.bottom < -100 || r.top > S.h + 100) return;
        const p = clamp(-r.top / (sec.offsetHeight - S.h || 1), 0, 1);
        cur = lerp(cur, -p * dist, 0.12);
        track.style.transform = `translate3d(${cur.toFixed(2)}px, 0, 0)`;
        if (rail) rail.style.transform = `scaleX(${p.toFixed(3)})`;
      });
    });
  }

  /* ==========================================================================
     11. Count-up stats
     ========================================================================== */
  function counters() {
    const els = $$('[data-count]');
    if (!els.length) return;
    if (REDUCED || !('IntersectionObserver' in window)) {
      els.forEach(el => el.textContent = el.dataset.count);
      return;
    }
    const run = el => {
      const to = parseFloat(el.dataset.count);
      const dur = 1700;
      const t0 = performance.now();
      const tick = now => {
        const t = clamp((now - t0) / dur, 0, 1);
        const eased = 1 - Math.pow(1 - t, 4);
        el.textContent = Math.round(to * eased).toLocaleString();
        if (t < 1) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    };
    const io = new IntersectionObserver(en => en.forEach(e => {
      if (e.isIntersecting) { run(e.target); io.unobserve(e.target); }
    }), { threshold: 0.4 });
    els.forEach(el => { el.textContent = '0'; io.observe(el); });
  }

  /* ==========================================================================
     12. Accordion
     ========================================================================== */
  function accordions() {
    $$('.acc').forEach(acc => {
      const items = $$('.acc__item', acc);
      items.forEach(item => {
        const btn = $('.acc__btn', item);
        const panel = $('.acc__panel', item);
        if (!btn || !panel) return;
        btn.addEventListener('click', () => {
          const open = item.classList.contains('is-open');
          if (!acc.dataset.multi) {
            items.forEach(other => {
              if (other === item) return;
              other.classList.remove('is-open');
              const p = $('.acc__panel', other);
              const b = $('.acc__btn', other);
              if (p) p.style.height = '0px';
              if (b) b.setAttribute('aria-expanded', 'false');
            });
          }
          item.classList.toggle('is-open', !open);
          btn.setAttribute('aria-expanded', String(!open));
          panel.style.height = open ? '0px' : $('.acc__inner', panel).offsetHeight + 'px';
        });
      });
      addEventListener('resize', () => {
        items.forEach(item => {
          if (!item.classList.contains('is-open')) return;
          const panel = $('.acc__panel', item);
          panel.style.height = $('.acc__inner', panel).offsetHeight + 'px';
        });
      }, { passive: true });
    });
  }

  /* ==========================================================================
     13. Hero canvas — the dot-cluster mark, exploded into a live field
     ========================================================================== */
  function heroField() {
    const cv = $('.hero__canvas');
    if (!cv || REDUCED) return;
    const ctx = cv.getContext('2d');
    let w = 0, h = 0, dpr = 1, pts = [];
    const mouse = { x: -9999, y: -9999 };

    const build = () => {
      dpr = Math.min(devicePixelRatio || 1, 2);
      const r = cv.getBoundingClientRect();
      w = r.width; h = r.height;
      cv.width = w * dpr; cv.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      const gap = w < 720 ? 62 : 78;
      pts = [];
      for (let y = gap * 0.5; y < h; y += gap) {
        for (let x = gap * 0.5; x < w; x += gap) {
          const jx = x + (Math.sin(x * 0.031 + y * 0.017) * gap * 0.24);
          const jy = y + (Math.cos(y * 0.029 + x * 0.013) * gap * 0.24);
          pts.push({ x: jx, y: jy, ox: jx, oy: jy, vx: 0, vy: 0, r: 1.6 + Math.random() * 3.1, ph: Math.random() * 6.28 });
        }
      }
    };
    build();
    addEventListener('resize', build, { passive: true });

    cv.parentElement.addEventListener('mousemove', e => {
      const r = cv.getBoundingClientRect();
      mouse.x = e.clientX - r.left; mouse.y = e.clientY - r.top;
    }, { passive: true });
    cv.parentElement.addEventListener('mouseleave', () => { mouse.x = mouse.y = -9999; });

    let t = 0;
    onTick(() => {
      const r = cv.getBoundingClientRect();
      if (r.bottom < 0 || r.top > innerHeight) return;
      t += 0.012;
      ctx.clearRect(0, 0, w, h);

      for (const p of pts) {
        // drift
        const dxo = Math.sin(t + p.ph) * 4.2;
        const dyo = Math.cos(t * 0.9 + p.ph) * 4.2;
        // mouse repulsion
        const dx = p.x - mouse.x, dy = p.y - mouse.y;
        const d2 = dx * dx + dy * dy;
        const R = 170;
        if (d2 < R * R) {
          const d = Math.sqrt(d2) || 1;
          const f = (1 - d / R) * 15;
          p.vx += (dx / d) * f * 0.16;
          p.vy += (dy / d) * f * 0.16;
        }
        p.vx += ((p.ox + dxo) - p.x) * 0.028;
        p.vy += ((p.oy + dyo) - p.y) * 0.028;
        p.vx *= 0.88; p.vy *= 0.88;
        p.x += p.vx; p.y += p.vy;

        const disp = Math.min(1, Math.hypot(p.x - p.ox, p.y - p.oy) / 46);
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r * (1 + disp * 0.85), 0, 6.2832);
        ctx.fillStyle = disp > 0.18
          ? `rgba(0, 128, 129, ${0.22 + disp * 0.5})`
          : `rgba(62, 55, 138, ${0.13 + disp * 0.3})`;
        ctx.fill();
      }
    });
  }

  /* ==========================================================================
     14. Dot-cluster deco (SVG) — breathing mark used on inner pages
     ========================================================================== */
  function dotDeco() {
    $$('.dotfield').forEach(svg => {
      if (svg.dataset.built) return;
      const N = 5, R = 46, C = 50;
      const frag = [];
      for (let ring = 0; ring <= N; ring++) {
        const count = ring === 0 ? 1 : ring * 6;
        const rad = (ring / N) * R;
        for (let i = 0; i < count; i++) {
          const a = (i / count) * Math.PI * 2 + ring * 0.4;
          const x = C + Math.cos(a) * rad;
          const y = C + Math.sin(a) * rad;
          const rr = ring === 0 ? 3.6 : 3.4 - (ring / N) * 1.5 + (i % 2 ? 0.7 : 0);
          frag.push(`<circle cx="${x.toFixed(2)}" cy="${y.toFixed(2)}" r="${rr.toFixed(2)}" style="animation:pulseDot ${(2.4 + ring * 0.35).toFixed(2)}s var(--e-inout) ${(ring * 0.14 + (i % 3) * 0.09).toFixed(2)}s infinite"/>`);
        }
      }
      svg.setAttribute('viewBox', '0 0 100 100');
      svg.innerHTML = `<g fill="currentColor">${frag.join('')}</g>`;
      svg.dataset.built = '1';
    });
  }

  /* ==========================================================================
     15. Card tilt
     ========================================================================== */
  function tilt() {
    if (REDUCED || COARSE) return;
    $$('[data-tilt]').forEach(el => {
      const max = parseFloat(el.dataset.tilt) || 6;
      el.addEventListener('mousemove', e => {
        const r = el.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        el.style.transform = `perspective(900px) rotateX(${(-py * max).toFixed(2)}deg) rotateY(${(px * max).toFixed(2)}deg) translateY(-8px)`;
      });
      el.addEventListener('mouseleave', () => { el.style.transform = ''; });
    });
  }

  /* ==========================================================================
     16. Contact form — front-end only, no backend wired yet
     ========================================================================== */
  function forms() {
    $$('form[data-demo]').forEach(f => {
      f.addEventListener('submit', e => {
        e.preventDefault();
        if (!f.reportValidity()) return;
        f.classList.add('is-sent');
        f.scrollIntoView({ behavior: REDUCED ? 'auto' : 'smooth', block: 'center' });
      });
    });
  }

  /* ==========================================================================
     17. Coverage map interactions
     ========================================================================== */
  function mapLink() {
    const states = $$('.map-state');
    const rows = $$('[data-state]');
    if (!states.length) return;
    const setOn = (code, on) => {
      states.forEach(s => { if (s.dataset.code === code) s.classList.toggle('is-hot', on); });
      rows.forEach(r => { if (r.dataset.state === code) r.style.background = on ? 'var(--indigo-ink)' : ''; });
    };
    states.forEach(s => {
      s.addEventListener('mouseenter', () => setOn(s.dataset.code, true));
      s.addEventListener('mouseleave', () => setOn(s.dataset.code, false));
    });
    rows.forEach(r => {
      r.addEventListener('mouseenter', () => setOn(r.dataset.state, true));
      r.addEventListener('mouseleave', () => setOn(r.dataset.state, false));
    });
  }

  /* ==========================================================================
     boot
     ========================================================================== */
  function boot() {
    splitText();
    dotDeco();
    transitions();
    nav();
    reveals();
    cursor();
    marquees();
    parallax();
    story();
    horizontalPin();
    counters();
    accordions();
    heroField();
    tilt();
    forms();
    mapLink();
    startLoop();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => { boot(); preloader(); });
  } else {
    boot(); preloader();
  }
})();
