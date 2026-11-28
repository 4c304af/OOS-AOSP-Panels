#!/usr/bin/env python3
"""Build the clock font from the same AOSP outlines as BatteryDigitGlyphs.

Install tools/font-requirements.txt into an isolated Python environment, then run
this script from any directory. The generated font is checked in, so Android
builds do not need Python. Digits preserve their cubic contours and proportional
widths; only a matching colon and whitespace are added.
"""

import json
from pathlib import Path

import pathops
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen
from fontTools.svgLib.path import parse_path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(__file__).with_name("battery_clock_glyphs.json")
OUTPUT = ROOT / "res/font/battery_clock.otf"
UPM = 1300
UNIT = 100
SIDE_BEARING = 0.25
BASELINE = 9.01


def battery_outline(svg, stroke_width):
    source = pathops.Path()
    parse_path(svg, source.getPen())
    if stroke_width == 0:
        return source
    # Resolve filled overlaps before an inward offset so hidden internal
    # segments do not cut seams into joined digit strokes.
    if stroke_width < 0:
        source = pathops.op(source, source, pathops.PathOp.UNION)
    stroke = pathops.Path(source)
    stroke.stroke(abs(stroke_width), pathops.LineCap.ROUND_CAP,
                  pathops.LineJoin.ROUND_JOIN, 4)
    # CFF has cubic outlines, not Skia's rational conics. The approximation
    # error here is below 0.01 font units, well below a rendered pixel.
    stroke.convertConicsToQuads(0.0001)
    operation = pathops.PathOp.DIFFERENCE if stroke_width < 0 else pathops.PathOp.UNION
    return pathops.op(source, stroke, operation)


def colon_outline():
    result = pathops.Path()
    pen = result.getPen()
    radius, x = 0.55, 0.55
    k = radius * 0.5522847498307936
    for y in (2.35, 6.65):
        pen.moveTo((x + radius, y))
        pen.curveTo((x + radius, y + k), (x + k, y + radius), (x, y + radius))
        pen.curveTo((x - k, y + radius), (x - radius, y + k), (x - radius, y))
        pen.curveTo((x - radius, y - k), (x - k, y - radius), (x, y - radius))
        pen.curveTo((x + k, y - radius), (x + radius, y - k), (x + radius, y))
        pen.closePath()
    return result


def charstring(outline, advance, unit=UNIT, vertical_shift=BASELINE * UNIT, bearing=25):
    pen = T2CharStringPen(advance, None, roundTolerance=0.0)
    if outline is not None:
        x_min, _, _, _ = outline.bounds
        # SVG is y-down; OpenType is y-up. Leave room for the expanded stroke.
        transform = (unit, 0, 0, -unit, bearing - x_min * unit, vertical_shift)
        outline.draw(TransformPen(pen, transform))
    return pen.getCharString(private=None, globalSubrs=None)


def main():
    data = json.loads(SOURCE.read_text(encoding="utf-8"))
    outlines = {
        "digit" + digit: battery_outline(spec["path"], data["clock_stroke_width"])
        for digit, spec in data["digits"].items()
    }
    _, y_min, _, y_max = outlines["digit0"].bounds
    # Keep the visible digit height and center unchanged while reducing weight.
    unit = BASELINE * UNIT / (y_max - y_min)
    vertical_shift = BASELINE * UNIT / 2 + (y_min + y_max) * unit / 2
    outlines["colon"] = colon_outline()
    order = [".notdef", "space"] + list(outlines)
    metrics = {".notdef": (500, 0), "space": (350, 0)}
    strings = {name: charstring(None, width) for name, (width, _) in metrics.items()}
    for name, outline in outlines.items():
        x_min, _, x_max, _ = outline.bounds
        bearing = 50 if name == "colon" else round(SIDE_BEARING * UNIT)
        advance = round((x_max - x_min) * unit + 2 * bearing)
        metrics[name] = (advance, bearing)
        strings[name] = charstring(outline, advance, unit, vertical_shift, bearing)

    cmap = {ord(":"): "colon", 0x2236: "colon", 0xFE55: "colon", 0xFF1A: "colon"}
    for zero in data["decimal_zero_codepoints"]:
        for digit in range(10):
            cmap[zero + digit] = "digit" + str(digit)
    for point in (0x20, 0xA0, 0x2007, 0x202F):
        cmap[point] = "space"

    builder = FontBuilder(UPM, isTTF=False)
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap(cmap)
    builder.setupHorizontalMetrics(metrics)
    builder.setupHorizontalHeader(ascent=1100, descent=-200, lineGap=0)
    copyright = "Copyright (C) 2025 The Android Open Source Project. Clock conversion 2026."
    builder.setupNameTable({
        "familyName": "Native Panels Battery Clock",
        "styleName": "Regular",
        "uniqueFontIdentifier": "NativePanelsBatteryClock-1.0",
        "fullName": "Native Panels Battery Clock Regular",
        "psName": "NativePanelsBatteryClock-Regular",
        "version": "Version 1.000",
        "copyright": copyright,
        "licenseDescription": "Licensed under the Apache License, Version 2.0.",
        "licenseInfoURL": "https://www.apache.org/licenses/LICENSE-2.0",
    })
    builder.setupOS2(version=4, sTypoAscender=1100, sTypoDescender=-200, sTypoLineGap=0,
                     usWinAscent=1100, usWinDescent=200, sxHeight=650,
                     sCapHeight=901, usWeightClass=400, fsSelection=0xC0)
    builder.setupPost()
    builder.setupCFF("NativePanelsBatteryClock-Regular", {
        "FullName": "Native Panels Battery Clock Regular",
        "FamilyName": "Native Panels Battery Clock", "Weight": "Regular",
        "Notice": copyright + " Apache-2.0. " + data["source"],
    }, strings, {})
    builder.font["head"].created = builder.font["head"].modified = 2082844800
    builder.font.recalcTimestamp = False
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    builder.save(OUTPUT)
    print(f"Generated {OUTPUT.name}: {OUTPUT.stat().st_size} bytes, {len(cmap)} characters")


if __name__ == "__main__":
    main()
