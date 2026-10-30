"""Generate Hiyori launcher icon paths from a system font glyph.

Extracts the outline of U+3072 (ひ) from Yu Gothic Bold and emits the
pathData strings used by:
  app/src/main/res/drawable/ic_launcher_foreground.xml  (432 viewport)
  app/src/main/res/drawable/ic_launcher_monochrome.xml  (432 viewport)
  app/src/main/res/drawable/ic_mihon.xml                (148x141 viewport)

Usage: python scripts/gen_hiyori_icon.py
"""
import pathlib

from fontTools.ttLib import TTFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform

FONT = "C:/Windows/Fonts/YuGothB.ttc"
FONT_INDEX = 0
CODEPOINT = 0x3072


def glyph_path_data(font, target_max_dim, cx, cy):
    glyph_set = font.getGlyphSet()
    glyph_name = font.getBestCmap()[CODEPOINT]
    glyph = glyph_set[glyph_name]

    bounds_pen = BoundsPen(glyph_set)
    glyph.draw(bounds_pen)
    (x_min, y_min, x_max, y_max) = bounds_pen.bounds
    scale = target_max_dim / max(x_max - x_min, y_max - y_min)
    bcx, bcy = (x_min + x_max) / 2, (y_min + y_max) / 2

    # Flip Y (font units are y-up, viewport is y-down) and center at (cx, cy).
    t = Transform(scale, 0, 0, -scale,
                  cx - bcx * scale, cy + bcy * scale)
    out = SVGPathPen(glyph_set, ntos=lambda v: f"{round(v, 2):g}")
    glyph.draw(TransformPen(out, t))
    return out.getCommands()


def main():
    font = TTFont(FONT, fontNumber=FONT_INDEX)
    fg = glyph_path_data(font, 105, 216, 216)
    mono = glyph_path_data(font, 140, 216, 216)
    in_app = glyph_path_data(font, 148, 74, 70.5)

    def vec(viewport, fill, path):
        w, h = viewport
        return (
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="108dp"\n'
            f'    android:height="108dp"\n'
            f'    android:viewportWidth="{w}"\n'
            f'    android:viewportHeight="{h}">\n'
            '  <path\n'
            f'      android:pathData="{path}"\n'
            f'      android:fillColor="{fill}"/>\n'
            '</vector>\n'
        )

    res = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
    (res / "ic_launcher_foreground.xml").write_text(vec((432, 432), "#031019", fg), encoding="utf-8")
    (res / "ic_launcher_monochrome.xml").write_text(vec((432, 432), "#FFFFFFFF", mono), encoding="utf-8")
    # ic_mihon.xml keeps its original 148x141 viewport / 256dp size (in-app logo, splash)
    (res / "ic_mihon.xml").write_text(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="256dp"\n'
        '    android:height="243.89189dp"\n'
        '    android:viewportWidth="148"\n'
        '    android:viewportHeight="141">\n'
        '  <path\n'
        '      android:fillColor="#FFFFFFFF"\n'
        f'      android:pathData="{in_app}"/>\n'
        '</vector>\n',
        encoding="utf-8",
    )
    print("written:", sorted(p.name for p in res.glob("ic_*.xml")))


if __name__ == "__main__":
    main()
