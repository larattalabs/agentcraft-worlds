"""BRAM - lead, platform. Tan skin, thick walrus moustache and heavy brows, a tweed flat cap in deep
aubergine with a brass button, a quilted aubergine work jacket (walnut corduroy collar, brass zip,
patch pockets, brass lead badge) over a cream henley, rolled corduroy cuffs, khaki work trousers,
black work boots with cream laces. Classic arms. Silhouette: broad and square, flat cap, the only
cap and the only moustache in the cast."""
from chars.base import Box, H, P, Skin, T, legend_hair, legend_skin, mirror_limbs


def build():
    sk = Skin(slim=False)
    au = T("cast_ramps", "bram")
    L = {}
    L.update(legend_skin("tan"))
    L.update(legend_hair("espresso"))
    L.update({
        "w": P("ramps.cream[1]"), "e": P("details.bram.eye"), "b": P("details.bram.brow"),
        "@": au[0], "A": au[1], "B": au[2], "C": au[3], "D": au[4],
        "q": P("ramps.cream[0]"), "c": P("ramps.cream[1]"), "r": P("ramps.cream[2]"), "t": P("ramps.cream[3]"),
        "p": P("ramps.walnut[1]"), "P": P("ramps.walnut[2]"), "o": P("ramps.walnut[3]"),
        "a": P("ramps.stone[1]"), "x": P("ramps.stone[2]"), "X": P("ramps.stone[3]"),
        "u": P("ramps.ink[0]"), "I": P("ramps.ink[1]"), "i": P("ramps.ink[2]"), "z": P("ramps.ink[3]"),
        "y": P("ramps.brass[1]"), "Y": P("ramps.brass[2]"), "k": P("ramps.brass[3]"),
        "n": P("ramps.leather[1]"), "N": P("ramps.leather[2]"),
    })

    # ---------------- head ----------------
    hd = Box("head")
    hd.whole("base", "front", ["HJHHHHJH",
                               "GHHHHHHG",
                               "SSSSSSSS",
                               "SbbSSbbS",
                               "SweSSewS",
                               "SSbbbbSS",
                               "SSsddsSS",
                               "dsSSSSsd"])
    # col 0 = back edge, col 7 = front edge
    hd.whole("base", "right", ["HHHHHHHH",
                               "GHHHHHHG",
                               "KGGSSSSS",
                               "KGGsdSSS",
                               "KKGdsSSS",
                               "KKGSSSbb",
                               "dsSSSSSS",
                               "dsSSSSsd"])
    # col 0 = front edge, col 7 = back edge
    hd.whole("base", "left", ["HHHHHHHH",
                              "GHHHHHHG",
                              "SSSSSGGK",
                              "SSSsdGGK",
                              "SSSdsGKK",
                              "bbSSSGKK",
                              "SSSSSSsd",
                              "dsSSSSsd"])
    hd.whole("base", "back", ["HJHHHHJH",
                              "HHHHHHHH",
                              "GHHHHHHG",
                              "GGHHHHGG",
                              "KGGGGGGK",
                              "dKGGGGKd",
                              "dsSSSSsd",
                              "dsssssds"])
    hd.whole("base", "top", ["KGGGGGGK"] + ["GHHHHHHG"] * 6 + ["KGGGGGGK"])
    hd.whole("base", "bottom", ["dddddddd"] + ["dssssssd"] * 6 + ["dddddddd"])
    # overlay: tweed flat cap (checker of A/B), brim shadow, brass button
    hd.whole("overlay", "front", ["ABABABAB",
                                  "BCBCBCBC",
                                  ".CCCCCC.",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    hd.whole("overlay", "right", ["BABABABA",
                                  "CBCBCBCB",
                                  "CC......",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    hd.whole("overlay", "left", ["ABABABAB",
                                 "BCBCBCBC",
                                 "......CC",
                                 "........",
                                 "........",
                                 "........",
                                 "........",
                                 "........"])
    hd.whole("overlay", "back", ["ABABABAB",
                                 "BCBCBCBC",
                                 "CCCCCCCC",
                                 "........",
                                 "........",
                                 "........",
                                 "........",
                                 "........"])
    hd.whole("overlay", "top", ["BABABABA",
                                "ABABABAB",
                                "BABABABA",
                                "ABAByBAB",
                                "BABABABA",
                                "ABABABAB",
                                "BABABABA",
                                "CBCBCBCB"])
    hd.commit(sk, L)

    # ---------------- body: quilted aubergine work jacket, corduroy collar, brass zip ----------------
    bd = Box("body")
    bd.garment("base", 0, 11, "A", "B", "C", top=("C", "B"), bottom="C")
    bd.patch("base", "front", 0, 0, ["BPPqqPPC",
                                     "BAPcrPAC"])
    for yy in (3, 6, 9):          # quilting stitch lines
        bd.patch("base", "front", 1, yy, ["AAAAAA"])
    for yy in range(2, 11):       # brass zip, left of centre
        bd.put("base", "front", 3, yy, "Y" if yy % 2 == 0 else "k")
        bd.put("base", "front", 4, yy, "C")
    bd.patch("base", "front", 5, 6, ["AAA", "CCC", "BBB"])   # chest pocket (viewer right)
    bd.patch("base", "front", 0, 11, ["CCCCCCCC"])
    bd.patch("base", "back", 0, 0, ["CPPPPPPC"])
    for yy in (3, 6, 9):
        bd.patch("base", "back", 1, yy, ["AAAAAA"])
    bd.patch("base", "back", 0, 11, ["CCCCCCCC"])
    for f in ("right", "left"):
        bd.patch("base", f, 0, 11, ["CCCC"])
        for yy in (3, 6, 9):
            bd.patch("base", f, 1, yy, ["AA"])
    bd.patch("base", "top", 0, 0, ["PPPPPPPP", "PBBBBBBP", "PBBBBBBP", "PPPPPPPP"])
    bd.put("overlay", "front", 6, 5, "y")      # brass lead badge
    bd.put("overlay", "front", 6, 6, "Y")
    bd.commit(sk, L)

    # ---------------- right arm (classic): jacket sleeve, corduroy cuff, tan hand ----------------
    ra = Box("right_arm")
    ra.garment("base", 0, 8, "A", "B", "C", top=("B", "A"))
    for f in ("front", "back", "right", "left"):
        w = ra.dims[f][0]
        ra.patch("base", f, 0, 9, ["P" * w])
    ra.whole("base", "bottom", ["ssss", "sdds", "sdds", "ssss"])
    ra.patch("base", "front", 0, 10, ["hSSs", "SSss"])
    ra.patch("base", "right", 0, 10, ["sSSh", "ssSS"])
    ra.patch("base", "back", 0, 10, ["sSSS", "dssS"])
    ra.patch("base", "left", 0, 10, ["Ssss", "sssd"])
    ra.garment("overlay", 0, 8, "A", "B", "C")
    for f in ("front", "back", "right", "left"):
        ra.patch("overlay", f, 0, 9, ["p" * ra.dims[f][0]])      # rolled corduroy cuff
    ra.commit(sk, L)

    # ---------------- right leg: khaki work trousers, rolled cuff, black boots ----------------
    rl = Box("right_leg")
    rl.garment("base", 0, 8, "a", "x", "X", top=("X", "x"))
    rl.patch("base", "front", 0, 9, ["uIII", "IIII", "zzzz"])
    rl.patch("base", "right", 0, 9, ["IIIu", "IIII", "zzzz"])
    rl.patch("base", "back", 0, 9, ["IIII", "IIII", "zzzz"])
    rl.patch("base", "left", 0, 9, ["uIII", "IIII", "zzzz"])
    rl.patch("base", "front", 1, 10, ["q"])          # cream lace
    rl.whole("base", "bottom", ["zzzz", "zIIz", "zIIz", "zzzz"])
    rl.garment("overlay", 0, 7, "a", "x", "X")
    for f in ("front", "back", "right", "left"):
        rl.patch("overlay", f, 0, 8, ["X" * rl.dims[f][0]])      # rolled cuff
    rl.commit(sk, L)

    mirror_limbs(sk)
    return sk
