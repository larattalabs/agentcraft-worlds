"""CASS - lead, product. Light skin with freckles, auburn hair in a high ponytail (fuchsia scrunchie,
the tail hangs down the back over the jacket) and long side-swept bangs, a fuchsia clip, a cropped
fuchsia bomber (cream chest stripe, ribbed collar/cuffs/hem, brass zip, brass lead badge) over a
cream tee, ink high-waisted shorts with a brass buckle, bare knees, cream knee socks with a
fuchsia band, chunky ink boots. Slim arms. Silhouette: short jacket, shorts and knee socks, the
only bare legs in the cast, with the ponytail breaking the outline at the back."""
from chars.base import Box, H, P, Skin, T, legend_hair, legend_skin, mirror_limbs


def build():
    sk = Skin(slim=True)
    fu = T("cast_ramps", "cass")
    L = {}
    L.update(legend_skin("light"))
    L.update(legend_hair("auburn"))
    L.update({
        "w": P("ramps.cream[1]"), "e": P("details.cass.eye"), "m": P("details.cass.lip"),
        "p": P("details.cass.cheek"), "f": P("details.cass.freckle"),
        "@": fu[0], "A": fu[1], "B": fu[2], "C": fu[3], "D": fu[4],
        "q": P("ramps.cream[0]"), "c": P("ramps.cream[1]"), "r": P("ramps.cream[2]"), "t": P("ramps.cream[3]"),
        "u": P("ramps.ink[0]"), "I": P("ramps.ink[1]"), "i": P("ramps.ink[2]"), "z": P("ramps.ink[3]"),
        "y": P("ramps.brass[1]"), "Y": P("ramps.brass[2]"), "k": P("ramps.brass[3]"),
    })

    # ---------------- head ----------------
    hd = Box("head")
    hd.whole("base", "front", ["HJHHHHJH",
                               "HHHJHHHH",
                               "HHHSSGGH",
                               "SweSSewS",
                               "SfSsSSfS",
                               "SpSmmSpS",
                               "SSSSSSSs",
                               "dsSSSSsd"])
    # col 0 = back edge, col 7 = front edge
    hd.whole("base", "right", ["HHJJHHHH",
                               "GHHHHHHH",
                               "KGHHHHSS",
                               "KGHsSSSS",
                               "KGGdsSSS",
                               "KKGSSSSS",
                               "dKSSSSSS",
                               "dsSSSSSS"])
    # col 0 = front edge, col 7 = back edge
    hd.whole("base", "left", ["HHHHJHHH",
                              "HHHHHHHG",
                              "HHHHHGGK",
                              "SSSsdHGK",
                              "SSSdsHGK",
                              "SSSSSGKK",
                              "SSSSSSKd",
                              "SSSSSSdd"])
    hd.whole("base", "back", ["HJHHHHJH",
                              "HHHJHHHH",
                              "GHHHHHHG",
                              "GGHHHHGG",
                              "KGGHHGGK",
                              "dKGGGGKd",
                              "dsKGGKsd",
                              "dsSSSSsd"])
    hd.whole("base", "top", ["KGGGGGGK",
                             "GHHHHHHG",
                             "GHJHHHHG",
                             "GJHHHJHG",
                             "GHHJHHHG",
                             "GHJHHHJG",
                             "GHHHJHHG",
                             "HHJHHHHH"])
    hd.whole("base", "bottom", ["dddddddd"] + ["dssssssd"] * 6 + ["dddddddd"])
    # overlay: swept bangs, fuchsia clip, scrunchie + ponytail on the back
    hd.whole("overlay", "front", ["HJHHHHJH",
                                  "HHHJHHHH",
                                  "HHH..GGH",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    hd.whole("overlay", "left", ["HHHHJHHH",
                                 "HBAHHHHG",
                                 "HHHHHGGK",
                                 "........",
                                 "........",
                                 "........",
                                 "........",
                                 "........"])
    hd.whole("overlay", "right", ["HHJJHHHH",
                                  "GHHHHHHH",
                                  "KGHHHH..",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    hd.whole("overlay", "back", ["HJHHHHJH",
                                 "HBAABAAH",
                                 "GHJJHHHG",
                                 "GHHJHHHG",
                                 ".HHHHHG.",
                                 "..HJHG..",
                                 "..HHHG..",
                                 "...HG..."])
    hd.whole("overlay", "top", ["KGGGGGGK",
                                "GHHHHHHG",
                                "GHJHHHHG",
                                "GJHHHJHG",
                                "GHHJHHHG",
                                "GHJHHHJG",
                                "GHHHJHHG",
                                "HHJHHHHH"])
    hd.commit(sk, L)

    # ---------------- body: cropped fuchsia bomber, cream tee, ink high waist ----------------
    bd = Box("body")
    bd.garment("base", 0, 7, "A", "B", "C", top=("C", "B"), bottom="i")
    bd.garment("base", 8, 9, "q", "c", "r")
    bd.garment("base", 10, 11, "u", "I", "i")
    bd.patch("base", "front", 0, 0, ["CDCqqCDC",
                                     "BAAqrABC"])
    for yy in range(2, 8):                 # brass zip
        bd.put("base", "front", 3, yy, "Y" if yy % 2 == 0 else "k")
        bd.put("base", "front", 4, yy, "C")
    bd.patch("base", "front", 0, 5, ["qqqq..qq"])      # cream chest stripe, split by the zip
    bd.put("base", "front", 3, 5, "Y")
    bd.put("base", "front", 4, 5, "C")
    bd.patch("base", "front", 0, 7, ["CDCDCDCD"])      # ribbed hem
    bd.patch("base", "front", 3, 10, ["yY"])            # buckle
    bd.patch("base", "back", 0, 0, ["CDCDCDCD"])
    bd.patch("base", "back", 0, 5, ["cccccccc"])
    bd.patch("base", "back", 0, 7, ["CDCDCDCD"])
    for f in ("right", "left"):
        bd.patch("base", f, 0, 5, ["cccc"])
        bd.patch("base", f, 0, 7, ["CDCD"])
    bd.put("overlay", "front", 6, 3, "y")      # brass lead badge
    bd.put("overlay", "front", 6, 4, "Y")
    # ponytail lies over the bomber back
    bd.patch("overlay", "back", 2, 0, ["HJJH", "HHJH", ".HHG", ".HJG", "..HG", "..GK"])
    bd.commit(sk, L)

    # ---------------- right arm (slim): bomber sleeve, cream stripe, ribbed cuff, light skin ----------------
    ra = Box("right_arm", slim=True)
    ra.garment("base", 0, 9, "A", "B", "C", top=("B", "A"))
    for f in ("front", "back", "right", "left"):
        w = ra.dims[f][0]
        ra.patch("base", f, 0, 7, ["c" * w])
        ra.patch("base", f, 0, 9, [("CDC" * 2)[:w]])
    ra.whole("base", "bottom", ["sss", "sds", "sds", "sss"])
    ra.patch("base", "front", 0, 10, ["hSs", "SSs"])
    ra.patch("base", "right", 0, 10, ["sSSh", "ssSS"])
    ra.patch("base", "back", 0, 10, ["sSS", "dsS"])
    ra.patch("base", "left", 0, 10, ["Ssss", "sssd"])
    ra.garment("overlay", 0, 6, "A", "B", "C")
    ra.commit(sk, L)

    # ---------------- right leg: ink shorts, bare knee, cream sock with fuchsia band, chunky boots ----------------
    rl = Box("right_leg")
    rl.garment("base", 0, 4, "u", "I", "i", top=("i", "I"))
    rl.patch("base", "front", 0, 5, ["hSSs", "SSSs"])
    rl.patch("base", "right", 0, 5, ["sSSh", "sSSS"])
    rl.patch("base", "back", 0, 5, ["sSSs", "sSSs"])
    rl.patch("base", "left", 0, 5, ["Ssss", "SSss"])
    rl.garment("base", 7, 9, "q", "c", "r")
    for f in ("front", "back", "right", "left"):
        rl.patch("base", f, 0, 7, ["B" * rl.dims[f][0]])
    rl.garment("base", 10, 11, "u", "I", "i")
    rl.patch("base", "front", 0, 11, ["zzzz"])
    rl.patch("base", "back", 0, 11, ["zzzz"])
    rl.patch("base", "right", 0, 11, ["zzzz"])
    rl.patch("base", "left", 0, 11, ["zzzz"])
    rl.patch("base", "front", 1, 10, ["c"])    # lace
    rl.whole("base", "bottom", ["zzzz", "zIIz", "zIIz", "zzzz"])
    rl.garment("overlay", 0, 4, "u", "I", "i")
    for f in ("front", "back", "right", "left"):
        rl.patch("overlay", f, 0, 4, ["i" * rl.dims[f][0]])    # shorts hem flare
        rl.patch("overlay", f, 0, 7, ["B" * rl.dims[f][0]])    # slouch band
        rl.patch("overlay", f, 0, 9, ["I" * rl.dims[f][0], "I" * rl.dims[f][0], "z" * rl.dims[f][0]])
    rl.commit(sk, L)

    mirror_limbs(sk)
    return sk
