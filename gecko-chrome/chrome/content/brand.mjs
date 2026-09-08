/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { NAVIS_MARK } from "./brand-geometry.mjs";
export { NAVIS_MARK } from "./brand-geometry.mjs";

// The generated model also supplies native platform artwork. Brand colors
// stay independent of the user's UI accent; small icons never animate.

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";
const documentIds = new WeakMap();

function markGeometry(id, { compact = false } = {}) {
  if (!/^navis-[a-z0-9-]+$/u.test(id)) {
    throw new TypeError("A Navis mark requires an application-owned SVG ID");
  }
  const rectangle = NAVIS_MARK.contour;
  const gradient = ([name, { type, units, stops, ...coordinates }]) => [
    type === "linear" ? "linearGradient" : "radialGradient",
    { id: `${id}-${name}`, gradientUnits: units, ...coordinates },
    stops.map(({ offset, color, opacity = 1 }) => ["stop", { offset, "stop-color": color, "stop-opacity": opacity }]),
  ];
  const relief = ([name, { bounds, shadows }]) => ["filter", {
    id: `${id}-relief-${name}`, x: bounds[0], y: bounds[1], width: bounds[2], height: bounds[3],
    "color-interpolation-filters": NAVIS_MARK.effects.colorInterpolationFilters,
  }, shadows.map(({ dx, dy, stdDeviation, color, opacity }) => ["feDropShadow", {
    dx, dy, stdDeviation, "flood-color": color, "flood-opacity": opacity,
  }])];
  const seam = (name, d) => ["path", {
    class: `navis-mark-seam navis-mark-seam-${name}`, d, fill: "none", stroke: NAVIS_MARK.seams.color,
    "stroke-width": NAVIS_MARK.seams.strokeWidth, "stroke-linecap": NAVIS_MARK.seams.linecap,
    "stroke-linejoin": NAVIS_MARK.seams.linejoin,
    ...(!compact && { filter: `url(#${id}-relief-seam)` }),
  }];
  return [
    ["defs", {}, [
      ...Object.entries(NAVIS_MARK.gradients).filter(([name]) => !compact || ["contour", "channel"].includes(name)).map(gradient),
      ...(!compact ? Object.entries(NAVIS_MARK.effects.relief).map(relief) : []),
      ["clipPath", { id: `${id}-clip` }, [["rect", rectangle]]],
    ]],
    ["g", { "clip-path": `url(#${id}-clip)` }, [
      ["g", { class: "navis-mark-body" }, [
        ["rect", { ...rectangle, fill: `url(#${id}-contour)`, stroke: "none",
          ...(!compact && { filter: `url(#${id}-relief-contour)` }) }],
        ...(!compact ? [
          ["rect", { ...rectangle, fill: `url(#${id}-frostDiffuse)`, stroke: "none" }],
          ["rect", { ...rectangle, fill: `url(#${id}-frostFog)`, stroke: "none" }],
          ["rect", { ...rectangle, fill: "none", stroke: `url(#${id}-rim)`,
            "stroke-width": NAVIS_MARK.effects.rim.strokeWidth, opacity: NAVIS_MARK.effects.rim.opacity }],
        ] : []),
        ["g", { transform: `rotate(${NAVIS_MARK.transform.rotate} ${NAVIS_MARK.transform.cx} ${NAVIS_MARK.transform.cy})` }, [
          ["path", { d: NAVIS_MARK.channel.path, fill: "none", stroke: `url(#${id}-channel)`,
            "stroke-opacity": NAVIS_MARK.channel.opacity, "stroke-width": NAVIS_MARK.channel.strokeWidth,
            "stroke-linecap": NAVIS_MARK.channel.linecap, "stroke-linejoin": NAVIS_MARK.channel.linejoin,
            ...(!compact && { filter: `url(#${id}-relief-channel)` }) }],
          ...NAVIS_MARK.seams.paths.map((path, index) => seam(index, path)),
        ]],
      ]],
    ]],
  ];
}

/** Native namespace construction works in both XHTML chrome and HTML. */
export function appendNavisMark(ownerDocument, svg) {
  const next = (documentIds.get(ownerDocument) || 0) + 1;
  documentIds.set(ownerDocument, next);
  const append = (parent, geometry) => {
    for (const [name, attributes, children = []] of geometry) {
      const node = ownerDocument.createElementNS(SVG_NAMESPACE, name);
      for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
      append(node, children);
      parent.append(node);
    }
  };
  svg.setAttribute("viewBox", NAVIS_MARK.viewBox.join(" "));
  append(svg, markGeometry(`navis-icon-${next}`, { compact: true }));
}

/** Only fixed geometry and validated IDs reach markup; no page data/innerHTML. */
export function renderNavisMark(id, { animated = false, variant = "full" } = {}) {
  if (!["full", "compact"].includes(variant) || (animated && variant === "compact")) {
    throw new TypeError("A compact Navis mark is always static");
  }
  const serialize = geometry => geometry.map(([name, attributes, children = []]) =>
    `<${name}${Object.entries(attributes).map(([key, value]) => ` ${key}="${value}"`).join("")}>${serialize(children)}</${name}>`
  ).join("");
  return `<svg xmlns="${SVG_NAMESPACE}" class="navis-brand-mark" viewBox="${NAVIS_MARK.viewBox.join(" ")}" aria-hidden="true" focusable="false"${animated ? ' data-brand-entrance="pending"' : ""}>${serialize(markGeometry(id, { compact: variant === "compact" }))}</svg>`;
}

const counterflow = NAVIS_MARK.motion.counterflow;
export const NAVIS_BRAND_STYLE = `
  .navis-brand-mark { display: block; width: 100%; height: 100%; flex: 0 0 auto; }
  [data-brand-entrance="playing"] .navis-mark-body { animation: navis-mark-in ${counterflow.contourDurationMs}ms cubic-bezier(${counterflow.contourEasing.join(",")}) both; }
  [data-brand-entrance="playing"] .navis-mark-seam { animation: navis-mark-counterflow ${counterflow.seamDurationMs}ms ${counterflow.seamDelayMs}ms cubic-bezier(${counterflow.seamEasing.join(",")}) both; }
  @keyframes navis-mark-in { from { opacity: 0; } ${counterflow.contourOpaqueAt * 100}%, to { opacity: 1; } }
  @keyframes navis-mark-counterflow {
    from { opacity: 0; stroke-dasharray: ${counterflow.seamDashLength}; stroke-dashoffset: ${counterflow.seamDashLength}; }
    ${counterflow.seamOpaqueAt * 100}% { opacity: 1; }
    to { opacity: 1; stroke-dasharray: ${counterflow.seamDashLength}; stroke-dashoffset: 0; }
  }
  @media (prefers-reduced-motion: reduce) {
    [data-brand-entrance] .navis-mark-body, [data-brand-entrance] .navis-mark-seam { animation: none; }
  }
`;

/** One visible entrance; no timers, animation loops, geometry reads or startup delay. */
export function installBrandEntrances(ownerWindow) {
  const { document } = ownerWindow;
  const marks = [...document.querySelectorAll('[data-brand-entrance="pending"]')];
  if (!marks.length) return;
  const reducedMotion = ownerWindow.matchMedia("(prefers-reduced-motion: reduce)");
  const visible = new Set();
  let observer;
  const finish = mark => {
    mark.dataset.brandEntrance = "done";
    visible.delete(mark);
    observer?.unobserve(mark);
  };
  const update = () => {
    for (const mark of marks) {
      if (reducedMotion.matches || (document.hidden && mark.dataset.brandEntrance === "playing")) {
        finish(mark);
      } else if (!document.hidden && visible.has(mark) && mark.dataset.brandEntrance === "pending") {
        mark.dataset.brandEntrance = "playing";
      }
    }
  };
  const complete = event => {
    if (event.animationName !== "navis-mark-counterflow") return;
    const mark = event.target.closest("[data-brand-entrance]");
    if (marks.includes(mark)) finish(mark);
  };
  if (reducedMotion.matches || !ownerWindow.IntersectionObserver) {
    marks.forEach(finish);
    return;
  }
  observer = new ownerWindow.IntersectionObserver(entries => {
    for (const entry of entries) {
      if (entry.isIntersecting) visible.add(entry.target);
      else {
        visible.delete(entry.target);
        if (entry.target.dataset.brandEntrance === "playing") finish(entry.target);
      }
    }
    update();
  });
  marks.forEach(mark => observer.observe(mark));
  document.addEventListener("animationend", complete);
  document.addEventListener("visibilitychange", update);
  reducedMotion.addEventListener("change", update);
  ownerWindow.addEventListener("pagehide", () => {
    marks.forEach(finish);
    observer.disconnect();
    document.removeEventListener("animationend", complete);
    document.removeEventListener("visibilitychange", update);
    reducedMotion.removeEventListener("change", update);
  }, { once: true });
}

export const NAVIS_BRAND_SCRIPT = `(${installBrandEntrances.toString()})(window);`;
