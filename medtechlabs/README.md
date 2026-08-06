# MedTech Labs — website

A six-page marketing site for MedTech Labs, built as static HTML/CSS/JS with no
build step and no runtime dependencies. Open `index.html` in a browser, or serve
the folder:

```bash
cd medtechlabs
python3 -m http.server 8000   # → http://localhost:8000
```

## Pages

| File | Page |
| --- | --- |
| `index.html` | Homepage |
| `services.html` | Services |
| `facilities.html` | For Facilities |
| `coverage.html` | Coverage |
| `about.html` | About |
| `contact.html` | Contact |

Copy is taken verbatim from the client-review document. Every page ends with the
shared closing section and footer.

## Brand

Derived from *MedTechLab Brand Book V3*:

| Token | Value | Use |
| --- | --- | --- |
| `--indigo` | `#3e378a` | Primary — buttons, blocks, the mark |
| `--indigo-ink` | `#17142c` | Deepest ground, footer, story block |
| `--lavender` | `#efedf7` | Page background |
| `--teal` | `#008081` | Brand accent |
| `--teal-deep` | `#1e3933` | Closing CTA block |
| `--mint` | `#cae8e0` | Pastel highlight |
| `--orchid` / `--violet` | `#ca74e0` / `#7a52a1` | Supporting pastels |
| `--gray` | `#646464` | Body / muted text |

Type: **Bricolage Grotesque ExtraBold** for display, **Manrope** for body — both
loaded from Google Fonts, with system fallbacks. The dot-cluster logo is inlined
as an SVG `<symbol id="mark">` at the top of each page and also lives standalone
in `assets/img/logo.svg`.

## Motion

`assets/js/main.js` is a single dependency-free engine. One shared
`requestAnimationFrame` loop drives every scroll-linked effect, and it pauses
when the tab is hidden.

- **Preloader** — counting loader that assembles the dot mark (homepage only),
  with a 4.2s hard ceiling so it can never trap a visitor.
- **Page transitions** — five-panel wipe on internal navigation, reversed on
  arrival. Modifier-clicks, new tabs, `tel:`/`mailto:` and anchors are left alone.
- **Text splitting** — `data-split="lines"` masks and lifts each line;
  `data-split="chars"` staggers per character while keeping words unbreakable.
- **Scroll reveals** — `data-reveal` (`fade` / `up` / `zoom`) and `data-clip`,
  driven by IntersectionObserver, staggered with `--rv-d`.
- **Custom cursor** — lerped dot + trailing ring, grows on interactive elements,
  and reads `data-cursor` for a label. Hidden on touch.
- **Magnetic elements** — `data-magnetic="0.3"` pulls buttons toward the pointer.
- **Marquees** — infinite, auto-cloned to fill the viewport, speed reacts to
  scroll velocity and slows on hover. `data-dir="right"` reverses.
- **Parallax + skew** — `data-parallax="0.1"` on layers, `data-skew` for
  velocity-driven shear.
- **Story block** — `data-story` lights its lines one at a time as the section
  crosses the viewport.
- **Horizontal pin** — `.hpin` pins full-height and scrolls its track sideways,
  with a progress rail. Sets its own height from track width.
- **Count-up stats** — `data-count="100"`.
- **Hero canvas** — the logo's dot cluster exploded into a live field that
  repels away from the pointer and settles back.
- **Also**: accordion FAQ, card tilt (`data-tilt`), coverage-radar hover linking
  the schematic to the state list, scroll progress bar, auto-hiding nav, and a
  circular-reveal fullscreen mobile menu.

### Accessibility

`prefers-reduced-motion: reduce` disables the preloader, transitions, cursor,
parallax, marquees and the horizontal pin, and forces every revealed element to
its final state.

The site is fully readable with JavaScript disabled. Each page carries a
one-line inline `<script>` that adds a `js` class to `<html>`, and every
hidden-until-scrolled rule is scoped to `.js` — so without JS nothing is ever
hidden, the preloader and transition overlay are removed, and the burger is
replaced by inline nav links.

Skip link, focus-visible rings, `aria-expanded` on the menu and accordion, and
labelled form fields are all in place.

### Two things worth knowing before editing

- `<html>` uses `overflow-x: clip`, not `hidden`, and `<body>` sets no
  `overflow-x` at all. `overflow-x: hidden` on either makes it a scroll
  container, which silently breaks the `position: sticky` behind the horizontal
  pin sections.
- Anything inside a `data-split="chars"` heading gets torn into per-character
  spans. Elements that own a paint effect — `.accent-word` uses
  `background-clip: text` — are wrapped whole instead of split, since splitting
  them leaves the children transparent. Mark any similar element `data-nosplit`.

## Before launch

These are placeholders in the markup and need real content:

- **Lab addresses** — street addresses for Berlin, Winslow and Pennsauken
  (`coverage.html`, currently "to be confirmed before launch").
- **State count** — the brochure says 8 states but lists 9. The site uses **9**
  throughout; confirm which is right.
- **Testimonial** — `index.html` carries a marked placeholder awaiting a real
  quote, name and facility.
- **Photography** — the hero collage and media panels are brand-gradient
  stand-ins, sized and positioned for the final photoshoot to drop straight in.
- **Contact form** — front-end only. `form[data-demo]` intercepts submit and
  shows the confirmation state; wire it to a real endpoint before launch.
