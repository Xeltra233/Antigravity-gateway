#!/usr/bin/env python3
"""Compare the web preview against the Android build on the same measured metrics.

Both builds are rendered from the same spec, but they do not share a renderer: the web page leans
on `backdrop-filter`, CSS gradients and SVG filters, while the app rebuilds those by hand. Eyeballing
two screenshots is not enough to call them aligned, so this script reduces both to numbers and prints
the deltas next to the tolerance each metric is allowed.

Usage:
    python scripts/visual-align.py --skin acr --ref ref_acr.png --app app_acr.png
    python scripts/visual-align.py --skin skc --ref ref_skc.png --app app_skc.png

Exit code is 0 when every metric is inside tolerance, 1 otherwise.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    import numpy as np
    from PIL import Image
except ImportError:  # pragma: no cover - dependency hint
    sys.stderr.write("Pillow and numpy are required: pip install pillow numpy\n")
    raise SystemExit(2)


@dataclass
class Metric:
    name: str
    ref: float
    app: float
    tolerance: float
    unit: str = ""
    informational: bool = False

    @property
    def delta(self) -> float:
        return self.app - self.ref

    @property
    def ok(self) -> bool:
        return self.informational or abs(self.delta) <= self.tolerance

    def line(self) -> str:
        mark = "ref " if self.informational else ("ok  " if self.ok else "FAIL")
        return (
            f"  {mark} {self.name:<24} ref={self.ref:8.3f} app={self.app:8.3f} "
            f"delta={self.delta:+7.3f} tol=±{self.tolerance:.3f}{self.unit}"
        )


def luminance(rgb: np.ndarray) -> float:
    return 0.2126 * rgb[0] / 255 + 0.7152 * rgb[1] / 255 + 0.0722 * rgb[2] / 255


def region(image: np.ndarray, box: tuple[float, float, float, float]) -> np.ndarray:
    """Crop by relative coordinates so one ROI table works for both render sizes."""
    height, width = image.shape[:2]
    x0, y0, x1, y1 = box
    return image[
        int(y0 * height): int(y1 * height),
        int(x0 * width): int(x1 * width),
    ].reshape(-1, 3)


def mean_rgb(image: np.ndarray, box: tuple[float, float, float, float]) -> np.ndarray:
    return region(image, box).mean(axis=0)


def ink_percentile(image: np.ndarray, box: tuple[float, float, float, float], q: float = 3.0) -> float:
    """Brightness of the darkest `q` percent of a region: the ink without the paper."""
    pixels = region(image, box)
    lums = 0.2126 * pixels[:, 0] / 255 + 0.7152 * pixels[:, 1] / 255 + 0.0722 * pixels[:, 2] / 255
    return float(np.percentile(lums, q))


def stroke_width_px(image: np.ndarray, box: tuple[float, float, float, float], threshold: float = 0.55) -> float:
    """Median length of the darkest vertical run per column.

    A coverage ratio depends on how tall the sampled band is, which makes it incomparable across two
    screenshots at different scales. The run length does not: divide it by the scale and both builds
    report the stroke in dp.
    """
    height, width = image.shape[:2]
    x0, y0, x1, y1 = box
    band = image[int(y0 * height): int(y1 * height), int(x0 * width): int(x1 * width)]
    lums = 0.2126 * band[..., 0] / 255 + 0.7152 * band[..., 1] / 255 + 0.0722 * band[..., 2] / 255
    runs: list[int] = []
    for column in lums.T:
        dark = column < threshold
        if not dark.any():
            continue
        longest = current = 0
        for value in dark:
            current = current + 1 if value else 0
            longest = max(longest, current)
        runs.append(longest)
    return float(np.median(runs)) if runs else 0.0


def dominant_accent(pixels: np.ndarray, share: float = 0.002) -> np.ndarray:
    """Mean of the most saturated pixels on the page.

    Neither flat skin draws a large accent surface, so the accent only exists as glyphs and a toggle
    knob. Picking the top share by saturation finds it without hand-placing a box on a 20px target.
    """
    saturation = pixels.max(axis=1) - pixels.min(axis=1)
    count = max(int(len(pixels) * share), 1)
    return pixels[np.argsort(saturation)[-count:]].mean(axis=0)


def crayon_blue(pixels: np.ndarray, share: float = 0.08) -> float:
    """Blue ratio of the most saturated pixels: the paper showing through the hatching dilutes a
    plain mean, and the hatching is exactly what this metric is about."""
    blueness = pixels[:, 2] - pixels[:, 0]
    count = max(int(len(pixels) * share), 1)
    chosen = pixels[np.argsort(blueness)[-count:]]
    return float(chosen[:, 2].mean() / max(chosen[:, 0].mean(), 1))


def gloss_band(image: np.ndarray, box: tuple[float, float, float, float]) -> float:
    """Top-of-panel brightness minus bottom-of-panel brightness, normalised."""
    x0, y0, x1, y1 = box
    span = (y1 - y0)
    top = mean_rgb(image, (x0, y0, x1, y0 + span * 0.30))
    bottom = mean_rgb(image, (x0, y1 - span * 0.30, x1, y1))
    return luminance(top) - luminance(bottom)


# The two builds do not share a layout: the preview page shows a status header over grouped rows,
# the app shows a form. What they do share is the component itself, so each side gets its own ROI
# table and the metrics compare component properties rather than screen positions.
#
# Relative coordinates (x0, y0, x1, y1). Refresh them whenever either layout changes.
ROIS_REF = {
    # The flat skins have no hard-edged components, so they are compared on surface tone and on the
    # accent colour, taken page-wide as the most saturated pixels.
    "min": {
        "surface": (0.360, 0.750, 0.630, 0.950),
        "accent": (0.0, 0.0, 1.0, 1.0),
    },
    "mnd": {
        "surface": (0.360, 0.750, 0.630, 0.950),
        "accent": (0.0, 0.0, 1.0, 1.0),
    },
    "acr": {
        "panel": (0.345, 0.276, 0.642, 0.568),
        "field": (0.345, 0.641, 0.634, 0.701),
        "primary": (0.361, 0.836, 0.481, 0.885),
        "paper": (0.345, 0.900, 0.642, 0.960),
        "outline": (0.345, 0.262, 0.642, 0.272),
    },
    # Reference for this skin is the api.html line-work sheet, not the older preview page.
    "skc": {
        "panel": (0.353, 0.471, 0.642, 0.682),
        "field": (0.353, 0.471, 0.642, 0.682),
        "primary": (0.586, 0.300, 0.642, 0.349),
        "paper": (0.120, 0.649, 0.305, 0.909),
        "outline": (0.361, 0.4755, 0.634, 0.4810),
    },
}

ROIS_APP = {
    "min": {
        "surface": (0.093, 0.700, 0.907, 0.830),
        "accent": (0.0, 0.0, 1.0, 1.0),
    },
    "mnd": {
        "surface": (0.093, 0.700, 0.907, 0.830),
        "accent": (0.0, 0.0, 1.0, 1.0),
    },
    # Re-measured after the page rebuild: card stack, Model ID field, primary button, tab bar.
    "acr": {
        "panel": (0.030, 0.145, 0.970, 0.395),
        "field": (0.030, 0.500, 0.970, 0.545),
        "primary": (0.030, 0.600, 0.970, 0.625),
        "paper": (0.030, 0.960, 0.970, 0.999),
        "outline": (0.030, 0.142, 0.970, 0.146),
    },
    "skc": {
        "panel": (0.030, 0.145, 0.970, 0.395),
        "field": (0.030, 0.500, 0.970, 0.545),
        "primary": (0.030, 0.600, 0.970, 0.625),
        "paper": (0.030, 0.700, 0.970, 0.830),
        "outline": (0.030, 0.142, 0.970, 0.146),
    },
}


def build_metrics(skin: str, ref: np.ndarray, app: np.ndarray, ref_scale: float, app_scale: float) -> list[Metric]:
    ref_rois = ROIS_REF[skin]
    app_rois = ROIS_APP[skin]
    metrics: list[Metric] = []

    if skin in ("min", "mnd"):
        ref_surface = mean_rgb(ref, ref_rois["surface"])
        app_surface = mean_rgb(app, app_rois["surface"])
        metrics.append(Metric("surface L", luminance(ref_surface), luminance(app_surface), 0.045))
        ref_accent = dominant_accent(region(ref, ref_rois["accent"]))
        app_accent = dominant_accent(region(app, app_rois["accent"]))
        for index, channel in enumerate("RGB"):
            metrics.append(
                Metric(
                    "accent %s" % channel,
                    float(ref_accent[index]),
                    float(app_accent[index]),
                    26.0,
                    " /255",
                )
            )
        return metrics

    if skin == "acr":
        ref_panel = mean_rgb(ref, ref_rois["panel"])
        app_panel = mean_rgb(app, app_rois["panel"])
        metrics.append(Metric("panel L", luminance(ref_panel), luminance(app_panel), 0.06))
        metrics.append(
            Metric(
                "panel blue ratio",
                float(ref_panel[2] / max(ref_panel[0], 1)),
                float(app_panel[2] / max(app_panel[0], 1)),
                0.45,
            )
        )

        ref_field = mean_rgb(ref, ref_rois["field"])
        app_field = mean_rgb(app, app_rois["field"])
        metrics.append(Metric("field L", luminance(ref_field), luminance(app_field), 0.07))

        ref_primary = mean_rgb(ref, ref_rois["primary"])
        app_primary = mean_rgb(app, app_rois["primary"])
        metrics.append(Metric("primary L", luminance(ref_primary), luminance(app_primary), 0.08))

        metrics.append(
            Metric(
                "gloss band",
                gloss_band(ref, ref_rois["panel"]),
                gloss_band(app, app_rois["panel"]),
                0.05,
            )
        )
        return metrics

    ref_paper = mean_rgb(ref, ref_rois["paper"])
    app_paper = mean_rgb(app, app_rois["paper"])
    metrics.append(Metric("paper R", ref_paper[0], app_paper[0], 6, " /255"))
    metrics.append(Metric("paper G", ref_paper[1], app_paper[1], 6, " /255"))
    metrics.append(Metric("paper B", ref_paper[2], app_paper[2], 6, " /255"))

    metrics.append(
        Metric(
            "ink 3rd percentile",
            ink_percentile(ref, ref_rois["outline"]),
            ink_percentile(app, app_rois["outline"]),
            0.10,
        )
    )
    # Reported, not gated: the browser rasterises a snapped 2px line while Android anti-aliases a
    # fractional stroke, so the same visual weight measures differently on the two renderers. Read the
    # pair together with the eye; the gated metrics are the ones that survive that difference.
    metrics.append(
        Metric(
            "outline width (dp)",
            stroke_width_px(ref, ref_rois["outline"]) / ref_scale,
            stroke_width_px(app, app_rois["outline"]) / app_scale,
            0.45,
            " dp",
            informational=True,
        )
    )

    ref_primary = mean_rgb(ref, ref_rois["primary"])
    app_primary = mean_rgb(app, app_rois["primary"])
    metrics.append(Metric("crayon L", luminance(ref_primary), luminance(app_primary), 0.10))
    metrics.append(
        Metric(
            "crayon blue ratio",
            crayon_blue(region(ref, ref_rois["primary"])),
            crayon_blue(region(app, app_rois["primary"])),
            0.45,
        )
    )
    return metrics


def load(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB")).astype(float)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skin", required=True, choices=sorted(ROIS_APP))
    parser.add_argument("--ref", required=True, type=Path, help="web preview screenshot")
    parser.add_argument("--app", required=True, type=Path, help="Android screenshot")
    parser.add_argument(
        "--ref-scale", type=float, default=1.0,
        help="captured pixels per design pixel in the web screenshot (1.0 at dpr 1)",
    )
    parser.add_argument(
        "--app-scale", type=float, default=2.75,
        help="captured pixels per dp in the Android screenshot, i.e. screen density / 160",
    )
    args = parser.parse_args()

    for path in (args.ref, args.app):
        if not path.exists():
            sys.stderr.write(f"missing screenshot: {path}\n")
            return 2

    ref = load(args.ref)
    app = load(args.app)
    print(f"skin={args.skin}  ref={args.ref.name} {ref.shape[1]}x{ref.shape[0]}  "
          f"app={args.app.name} {app.shape[1]}x{app.shape[0]}")

    metrics = build_metrics(args.skin, ref, app, args.ref_scale, args.app_scale)
    for metric in metrics:
        print(metric.line())

    failures = [m for m in metrics if not m.ok]
    if failures:
        print(f"\n{len(failures)} of {len(metrics)} metrics outside tolerance")
        return 1
    print(f"\nall {len(metrics)} metrics inside tolerance")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
