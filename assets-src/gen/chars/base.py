"""Shared legend/painting helpers for character modules."""
from __future__ import annotations

from common import PALETTE, hex2rgba, pc
from skinlib import Skin  # noqa: F401  (re-export)


def T(group, name):
    """Tones of a palette group entry as an RGBA list (lightest -> darkest)."""
    g = PALETTE[group][name]
    return [hex2rgba(t) for t in (g["tones"] if isinstance(g, dict) else g)]


def H(h):
    return hex2rgba(h)


def P(path):
    """A palette entry by path, e.g. P("ramps.cream[1]"), P("details.kit.eye")."""
    return pc(path)


def legend_skin(tone):
    t = T("skin_tones", tone)
    return {"h": t[0], "S": t[1], "s": t[2], "d": t[3]}


def legend_hair(name):
    t = T("hair", name)
    return {"J": t[0], "H": t[1], "G": t[2], "K": t[3]}


def faces(sk, part, layer, legend, **grids):
    for face, rows in grids.items():
        sk.grid(part, layer, face, rows, legend)


def blank(w, h):
    return ["." * w] * h


E8 = blank(8, 8)
E4 = blank(4, 4)
E3x4 = blank(3, 4)
E4x12 = blank(4, 12)
E3x12 = blank(3, 12)


def row_only(w, h, rows: dict):
    """Overlay grid that is transparent except the given {row_index: row_string}."""
    out = ["." * w] * h
    out = list(out)
    for i, r in rows.items():
        assert len(r) == w, (r, w)
        out[i] = r
    return out


# ---------------------------------------------------------------------------------------------
# Box painter for the lead characters (ines, bram, cass): start every face from a filled garment
# with the shared shading rule, then patch details on top. Same output as literal grids
# (Skin.grid), just less typing for large flat masses.
from skinlib import FACES, part_dims  # noqa: E402


class Box:
    """One body part, both layers, as editable character grids (see skinlib for face orientation)."""

    def __init__(self, part, slim=False):
        self.part = part
        self.w, self.h, self.d = part_dims(part, slim)
        dims = {"top": (self.w, self.d), "bottom": (self.w, self.d), "right": (self.d, self.h),
                "front": (self.w, self.h), "left": (self.d, self.h), "back": (self.w, self.h)}
        self.dims = dims
        self.g = {"base": {f: [["?"] * dims[f][0] for _ in range(dims[f][1])] for f in FACES},
                  "overlay": {f: [["."] * dims[f][0] for _ in range(dims[f][1])] for f in FACES}}

    def put(self, layer, face, x, y, ch):
        w, h = self.dims[face]
        if 0 <= x < w and 0 <= y < h:
            self.g[layer][face][y][x] = ch

    def rows(self, layer, face, y0, y1, ch):
        """Fill rows y0..y1 (inclusive) of a face with one char."""
        w, _ = self.dims[face]
        for y in range(y0, y1 + 1):
            for x in range(w):
                self.put(layer, face, x, y, ch)

    def patch(self, layer, face, x, y, rows_):
        """Stamp text rows at (x, y); '.' and ' ' leave what is there."""
        for j, r in enumerate(rows_):
            for i, c in enumerate(r):
                if c not in ". ":
                    self.put(layer, face, x + i, y + j, c)

    def whole(self, layer, face, rows_):
        """Replace a whole face from text rows ('.' stays transparent/unset)."""
        w, h = self.dims[face]
        assert len(rows_) == h and all(len(r) == w for r in rows_), (self.part, face)
        for y, r in enumerate(rows_):
            for x, c in enumerate(r):
                self.g[layer][face][y][x] = c

    def garment(self, layer, y0, y1, hi, base, lo, faces_=("front", "back", "right", "left"), top=None, bottom=None):
        """Shaded mass over rows y0..y1: lit left edge on the front, shaded back edges."""
        for f in faces_:
            w, _ = self.dims[f]
            for y in range(y0, y1 + 1):
                for x in range(w):
                    if f == "front":
                        c = hi if x == 0 else lo if x == w - 1 else base
                    elif f == "back":
                        c = lo if x in (0, w - 1) else base
                    elif f == "right":      # col 0 = back edge
                        c = lo if x == 0 else hi if x == w - 1 else base
                    else:                   # left: col 0 = front edge
                        c = hi if x == 0 else lo if x == w - 1 else base
                    self.put(layer, f, x, y, c)
        if top:
            for y in range(self.d):
                for x in range(self.w):
                    self.put(layer, "top", x, y, top[0] if (x in (0, self.w - 1) or y == 0) else top[1])
        if bottom:
            self.rows(layer, "bottom", 0, self.d - 1, bottom)

    def commit(self, sk, L):
        for layer in ("base", "overlay"):
            for f in FACES:
                rows_ = ["".join(r) for r in self.g[layer][f]]
                if layer == "base":
                    assert not any("?" in r for r in rows_), (self.part, f, rows_)
                sk.grid(self.part, layer, f, rows_, L)


def mirror_limbs(sk):
    for src, dst in (("right_arm", "left_arm"), ("right_leg", "left_leg")):
        for layer in ("base", "overlay"):
            sk.mirror_limb(src, dst, layer)
