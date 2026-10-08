"""Generates every launcher and in-app logo resource from one picture of the logo.

Usage: python tools/make_app_icons.py [--preview preview.png]

The picture (tools/branding/anta-logo.png) shows the rounded-square icon on a backdrop. The constants below say
where the icon sits in it; measure them again when the picture is replaced.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools" / "branding" / "anta-logo.png"
RES = ROOT / "anta" / "src" / "main" / "res"

SQUARE = (50, 36, 519, 505)  # left, top, right, bottom of the icon inside the picture
CORNER = 0.207  # corner radius, as a fraction of the icon's side
EMBLEM_CENTRE = (0.525, 0.501)  # centre of the drawing (calendar, ball, tick) inside the icon
EMBLEM_RADIUS = 0.484  # farthest point of the drawing from that centre
EMBLEM_CONTRAST = 50  # how far a pixel must be from the backdrop gradient to count as drawing

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP, ADAPTIVE_DP, SAFE_DP, MASK_DP = 48, 108, 66, 72  # Android launcher icon geometry
LEGACY_FILL = 44 / 48  # share of a legacy icon the shape fills
SAFE_MARGIN = 0.98  # keep the drawing a hair inside the adaptive-icon safe zone
FEATHER = 0.04  # share of the icon's side over which its edge blends into the extended gradient
APP_LOGO_PX, APP_MARK_PX = 384, 256
SUPERSAMPLE = 4


def rounded_mask(size: int, inset: float = 0.0, blur: float = 0.0) -> Image.Image:
    """Antialiased mask of the icon's rounded-square shape, optionally shrunk and softened."""
    big = size * SUPERSAMPLE
    mask = Image.new("L", (big, big), 0)
    edge = round(inset * big)
    ImageDraw.Draw(mask).rounded_rectangle([edge, edge, big - 1 - edge, big - 1 - edge], radius=max(1, round(CORNER * big) - edge), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    return mask.filter(ImageFilter.GaussianBlur(blur * size)) if blur else mask


def gradient_coefficients(square: np.ndarray) -> list[np.ndarray]:
    """Quadratic fit of the icon's backdrop gradient, taken from the ring between the drawing and the edge."""
    side = square.shape[0]
    v, u = np.mgrid[0:side, 0:side] / side
    inside = np.asarray(rounded_mask(side, inset=0.035)) > 127
    ring = ((u < 0.10) | (u > 0.90) | (v < 0.10) | (v > 0.90)) & inside
    return [np.linalg.lstsq(_basis(u[ring], v[ring]), square[..., channel][ring], rcond=None)[0] for channel in range(3)]


def _basis(u: np.ndarray, v: np.ndarray) -> np.ndarray:
    return np.stack([np.ones_like(u), u, v, u * u, u * v, v * v], axis=-1)


def gradient(coefficients: list[np.ndarray], u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """The backdrop gradient at icon coordinates (0..1 inside the icon); held constant a little beyond its edge."""
    u, v = np.clip(u, -0.06, 1.06), np.clip(v, -0.06, 1.06)
    return np.clip(np.stack([_basis(u, v) @ c for c in coefficients], axis=-1), 0, 255)


def legacy_icon(icon: Image.Image, size: int) -> Image.Image:
    """Pre-Android-8 launcher icon: the rounded square with a little padding."""
    shape = round(size * LEGACY_FILL)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(icon.resize((shape, shape), Image.LANCZOS), ((size - shape) // 2, (size - shape) // 2))
    return canvas


def adaptive_layer(square: Image.Image, coefficients: list[np.ndarray], size: int) -> Image.Image:
    """Full-bleed adaptive-icon layer: the artwork scaled so its drawing sits inside the safe zone, centred on
    the drawing, with the backdrop gradient continued out to the edge of the layer."""
    side = round(size * (SAFE_DP / ADAPTIVE_DP / 2) / EMBLEM_RADIUS * SAFE_MARGIN)
    left, top = round(size / 2 - EMBLEM_CENTRE[0] * side), round(size / 2 - EMBLEM_CENTRE[1] * side)
    y, x = np.mgrid[0:size, 0:size]
    backdrop = gradient(coefficients, (x - left) / side, (y - top) / side)
    layer = Image.fromarray(backdrop.astype("uint8"), "RGB")
    layer.paste(square.resize((side, side), Image.LANCZOS), (left, top), rounded_mask(side, inset=FEATHER, blur=FEATHER / 2))
    return layer


def round_icon(layer: Image.Image, size: int) -> Image.Image:
    """What a circular launcher mask shows of the adaptive layer; used as the legacy round icon."""
    full = layer.size[0]
    visible = round(full * MASK_DP / ADAPTIVE_DP)
    offset = (full - visible) // 2
    crop = layer.crop((offset, offset, offset + visible, offset + visible)).convert("RGBA")
    big = visible * SUPERSAMPLE
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
    crop.putalpha(mask.resize((visible, visible), Image.LANCZOS))
    return crop.resize((size, size), Image.LANCZOS)


def emblem_mark(square: np.ndarray, coefficients: list[np.ndarray], size: int) -> Image.Image:
    """The drawing alone on a transparent background, for places that show the mark without its tile."""
    side = square.shape[0]
    v, u = np.mgrid[0:side, 0:side] / side
    differs = np.abs(square - gradient(coefficients, u, v)).max(axis=2) > EMBLEM_CONTRAST
    drawing = differs & (np.asarray(rounded_mask(side, inset=0.035)) > 127)
    # Parts of the drawing coloured like the backdrop are still enclosed by its outline: everything the
    # backdrop cannot reach from the icon's corner belongs to the drawing.
    outside = Image.fromarray(np.where(drawing, 255, 0).astype("uint8"), "L").copy()  # fromarray images are read-only
    ImageDraw.floodfill(outside, (2, 2), 128)
    alpha = Image.fromarray(np.where(np.asarray(outside) == 128, 0, 255).astype("uint8"), "L")
    alpha = alpha.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.8))
    mark = Image.fromarray(square.astype("uint8"), "RGB").convert("RGBA")
    mark.putalpha(alpha)
    box = alpha.getbbox()
    extent = max(box[2] - box[0], box[3] - box[1])
    centre = ((box[0] + box[2]) // 2, (box[1] + box[3]) // 2)
    canvas = Image.new("RGBA", (extent, extent), (0, 0, 0, 0))
    canvas.alpha_composite(mark.crop(box), (extent // 2 - (centre[0] - box[0]), extent // 2 - (centre[1] - box[1])))
    return canvas.resize((size, size), Image.LANCZOS)


def preview(icon: Image.Image, layer: Image.Image, mark: Image.Image) -> Image.Image:
    """Contact sheet: legacy icon, circle and squircle masks with the safe zone drawn, in-app logo and mark."""
    tile, gap = 300, 20
    sheet = Image.new("RGBA", (5 * tile + 6 * gap, tile + 2 * gap), (236, 238, 240, 255))
    visible = round(layer.size[0] * MASK_DP / ADAPTIVE_DP)
    offset = (layer.size[0] - visible) // 2
    shown = layer.crop((offset, offset, offset + visible, offset + visible)).convert("RGBA").resize((tile, tile), Image.LANCZOS)
    circle = Image.new("L", (tile, tile), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, tile - 1, tile - 1], fill=255)
    squircle = Image.new("L", (tile, tile), 0)
    ImageDraw.Draw(squircle).rounded_rectangle([0, 0, tile - 1, tile - 1], radius=tile // 4, fill=255)
    masked = [Image.composite(shown, Image.new("RGBA", (tile, tile), (0, 0, 0, 0)), mask) for mask in (circle, squircle)]
    safe = tile * SAFE_DP / MASK_DP
    ImageDraw.Draw(masked[0]).ellipse([(tile - safe) / 2, (tile - safe) / 2, (tile + safe) / 2, (tile + safe) / 2], outline=(255, 0, 0, 255), width=1)
    header = Image.new("RGBA", (tile, tile), (0, 125, 65, 255))
    header.alpha_composite(icon.resize((200, 200), Image.LANCZOS), (50, 50))
    search = Image.new("RGBA", (tile, tile), (255, 255, 255, 255))
    search.alpha_composite(mark.resize((200, 200), Image.LANCZOS), (20, 20))
    search.alpha_composite(mark.resize((53, 53), Image.LANCZOS), (235, 235))
    for index, image in enumerate([legacy_icon(icon, tile), *masked, header, search]):
        sheet.alpha_composite(image, (gap + index * (tile + gap), gap))
    return sheet


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview", type=Path, help="also write a contact sheet of the results to this file")
    arguments = parser.parse_args()

    square_image = Image.open(SOURCE).convert("RGB").crop(SQUARE)
    square = np.asarray(square_image).astype(float)
    side = square_image.size[0]
    coefficients = gradient_coefficients(square)
    icon = square_image.convert("RGBA")
    icon.putalpha(rounded_mask(side, inset=0.004))
    master = max(DENSITIES.values())
    layer = adaptive_layer(square_image, coefficients, round(ADAPTIVE_DP * master))
    mark = emblem_mark(square, coefficients, APP_MARK_PX)

    for density, scale in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        legacy_icon(icon, round(LEGACY_DP * scale)).save(folder / "ic_launcher.png", optimize=True)
        round_icon(layer, round(LEGACY_DP * scale)).save(folder / "ic_launcher_round.png", optimize=True)
        size = round(ADAPTIVE_DP * scale)
        layer.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher_foreground.png", optimize=True)
    icon.resize((APP_LOGO_PX, APP_LOGO_PX), Image.LANCZOS).save(RES / "drawable-nodpi" / "app_logo.png", optimize=True)
    mark.save(RES / "drawable-nodpi" / "app_mark.png", optimize=True)
    if arguments.preview:
        preview(icon, layer, mark).save(arguments.preview)


if __name__ == "__main__":
    main()
