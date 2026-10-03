"""INES - lead, systems. Brown skin, ink-black undercut with one long fringe swept across the
forehead, a small brass hoop, pale-lilac cropped blazer (peplum flare, notch lapels, brass lead
badge) over an ink turtleneck, ink high-waisted wide-leg trousers, cognac oxfords. Slim arms.
Silhouette: tall and narrow on top, wide at the ankles (flared overlay), the only one in trousers
that pool over the shoes."""
from chars.base import Box, H, P, Skin, T, legend_hair, legend_skin, mirror_limbs


def build():
    sk = Skin(slim=True)
    li = T("cast_ramps", "ines")
    L = {}
    L.update(legend_skin("brown"))
    L.update(legend_hair("ink"))
    L.update({
        "w": P("ramps.cream[1]"), "e": P("details.ines.eye"), "m": P("details.ines.lip"),
        "@": li[0], "A": li[1], "B": li[2], "C": li[3], "D": li[4],
        "u": P("ramps.ink[0]"), "I": P("ramps.ink[1]"), "i": P("ramps.ink[2]"), "z": P("ramps.ink[3]"),
        "y": P("ramps.brass[1]"), "Y": P("ramps.brass[2]"), "k": P("ramps.brass[3]"),
        "l": P("ramps.leather[0]"), "n": P("ramps.leather[1]"), "N": P("ramps.leather[2]"), "M": P("ramps.leather[3]"),
    })

    # ---------------- head ----------------
    hd = Box("head")
    hd.whole("base", "front", ["HJHHHHJH",
                               "GHHHHHHH",
                               "SKKSSKGH",
                               "SweSSewS",
                               "SSSsSSSs",
                               "SSSmmSSS",
                               "SSSSSSSs",
                               "dsSSSSsd"])
    # col 0 = back edge, col 7 = front edge
    hd.whole("base", "right", ["HHHJHHHH",
                               "GHHHHHHG",
                               "KGHsSSSS",
                               "KGsSsdSS",
                               "KKGsdsSS",
                               "KKKGSSSS",
                               "dKGSSSSS",
                               "ddsSSSSS"])
    # col 0 = front edge, col 7 = back edge (the fringe falls on this side)
    hd.whole("base", "left", ["HHJHHHHH",
                              "HHHHHHHG",
                              "HHGHsKKG",
                              "SHSSsdGK",
                              "SSSsdsGK",
                              "SSSSSGKK",
                              "SSSSSsKd",
                              "SSsSSsdd"])
    hd.whole("base", "back", ["HJHHHHJH",
                              "HHHHHHHH",
                              "GHHHHHHG",
                              "GGHHHHGG",
                              "KGGGGGGK",
                              "sKsGGsKs",
                              "SsSKKSsS",
                              "dsSSSSsd"])
    hd.whole("base", "top", ["KGGGGGGK",
                             "GHHHHHHG",
                             "GHJHHHHG",
                             "GJHHHJHG",
                             "GHHHJHHG",
                             "GHJHHHHG",
                             "GHHHHJHH",
                             "HHJHHHHH"])
    hd.whole("base", "bottom", ["dddddddd"] + ["dssssssd"] * 6 + ["dddddddd"])
    # overlay: volume on the crown and the long fringe, brass hoop
    hd.whole("overlay", "front", ["HJHHJHHH",
                                  "G.....HH",
                                  "......GH",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    hd.whole("overlay", "top", ["KGGGGGGK",
                                "GHHJHHHG",
                                "GHHHHJHG",
                                "GJHHHHHG",
                                "GHHHJHHG",
                                "GHJHHHHG",
                                "GHHHHJHH",
                                "HHJHHHHH"])
    hd.whole("overlay", "left", ["HHJHHHHH",
                                 "HHHHHHHG",
                                 "HH.....G",
                                 "........",
                                 "........",
                                 "........",
                                 "........",
                                 "........"])
    hd.whole("overlay", "right", ["HHHJHHHH",
                                  "GHHHHHHG",
                                  "........",
                                  "........",
                                  "...y....",
                                  "...k....",
                                  "........",
                                  "........"])
    hd.whole("overlay", "back", ["HJHHHHJH",
                                 "HHHHHHHH",
                                 "GHHHHHHG",
                                 "........",
                                 "........",
                                 "........",
                                 "........",
                                 "........"])
    hd.commit(sk, L)

    # ---------------- body: lilac cropped blazer, ink turtleneck, ink high waist ----------------
    bd = Box("body")
    bd.garment("base", 0, 9, "A", "B", "C", top=("C", "B"), bottom="i")
    bd.rows("base", "front", 10, 11, "I")
    bd.rows("base", "back", 10, 11, "I")
    bd.rows("base", "right", 10, 11, "I")
    bd.rows("base", "left", 10, 11, "I")
    bd.patch("base", "front", 0, 0, ["BA@II@AC",
                                     "BA@II@AC",
                                     "BBA@@ABC",
                                     "BBBDBBBC"])
    bd.patch("base", "front", 0, 7, ["BCCBBCCC"])         # pocket flaps
    bd.patch("base", "front", 0, 9, ["CCCCCCCC"])         # hem
    bd.patch("base", "front", 0, 10, ["uuuuuuuI", "IIyYYIIi"])   # waistband with brass buckle
    bd.patch("base", "back", 2, 0, ["IIII"])
    bd.patch("base", "back", 3, 7, ["CC", "CC", "CC"])   # back vent
    bd.patch("base", "back", 0, 9, ["CCCCCCCC"])
    bd.patch("base", "top", 2, 1, ["IIII", "IIII"])
    # peplum flare + badge on the overlay
    for f in ("front", "back"):
        bd.patch("overlay", f, 0, 8, ["CBBBBBBC", "CCCCCCCC"])
    for f in ("right", "left"):
        bd.patch("overlay", f, 0, 8, ["CBBC", "CCCC"])
    bd.put("overlay", "front", 6, 3, "y")
    bd.put("overlay", "front", 6, 4, "Y")
    bd.commit(sk, L)

    # ---------------- right arm (slim): lilac sleeve, ink cuff, deep skin ----------------
    ra = Box("right_arm", slim=True)
    ra.garment("base", 0, 8, "A", "B", "C", top=("B", "A"))
    ra.rows("base", "front", 9, 9, "I")
    ra.rows("base", "back", 9, 9, "i")
    ra.rows("base", "right", 9, 9, "I")
    ra.rows("base", "left", 9, 9, "i")
    ra.whole("base", "bottom", ["sss", "sds", "sds", "sss"])
    ra.patch("base", "front", 0, 10, ["hSs", "SSs"])
    ra.patch("base", "right", 0, 10, ["sSSh", "ssSS"])
    ra.patch("base", "back", 0, 10, ["sSS", "dsS"])
    ra.patch("base", "left", 0, 10, ["Ssss", "sssd"])
    ra.patch("base", "front", 0, 8, ["CCC"])
    ra.patch("base", "back", 0, 8, ["CCC"])
    ra.patch("base", "right", 0, 8, ["CCCC"])
    ra.patch("base", "left", 0, 8, ["CCCC"])
    ra.commit(sk, L)

    # ---------------- right leg: wide-leg ink trousers (flared on the overlay), cognac oxfords ----------------
    rl = Box("right_leg")
    rl.garment("base", 0, 9, "u", "I", "i", top=("i", "I"))
    rl.patch("base", "front", 0, 10, ["lllN", "NNNM"])
    rl.patch("base", "right", 0, 10, ["NNln", "MMMM"])
    rl.patch("base", "back", 0, 10, ["NNNN", "MMMM"])
    rl.patch("base", "left", 0, 10, ["nlNN", "MMMM"])
    rl.whole("base", "bottom", ["MMMM", "MNNM", "MNNM", "MMMM"])
    rl.garment("overlay", 0, 9, "u", "I", "i")
    for f in ("front", "back", "right", "left"):
        w = rl.dims[f][0]
        rl.patch("overlay", f, 0, 9, ["z" * w])
    rl.put("overlay", "front", 1, 3, "u")
    rl.commit(sk, L)

    mirror_limbs(sk)
    return sk
