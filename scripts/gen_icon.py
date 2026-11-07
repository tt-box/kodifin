# Génère l'icône Kodifin : « K » en verre épais biseauté, rempli d'un ciel étoilé bleu nuit,
# arêtes blanc -> cyan -> azur avec halo cyan, posé sur un disque bleu acier.
#   python -I -X utf8 scripts/gen_icon.py fg   > app/src/main/res/drawable/ic_launcher_foreground.xml
#   python -I -X utf8 scripts/gen_icon.py mono > app/src/main/res/drawable/ic_launcher_monochrome.xml
#   python -I -X utf8 scripts/gen_icon.py svg  > docs/icon/kodifin-icon.svg
# Les formes sont dessinées dans un repère libre puis ramenées au viewport 108x108 de l'icône adaptative.
# Le fond de l'icône adaptative est app/src/main/res/drawable/ic_launcher_background.xml (mêmes couleurs que BG).
import random
import re
import sys

CX, CY, R = 516, 470, 650          # disque, dans le repère des formes
S = 33.0 / R                        # disque de 66 dp : zone sûre de l'icône adaptative
TX, TY = 54 - CX * S, 54 - CY * S

SHAPES = [
    ("bar", "M88,60 L295,60 L295,305 L85,590 Z"),
    ("top", "M480,215 L640,62 L905,58 L610,390 L655,198 L480,242 Z"),
    ("leg", "M460,440 C450,570 490,720 625,880 L900,880 C770,730 560,560 545,330 L580,250 Z"),
    ("arc", "M85,880 L85,830 C95,640 260,400 580,250 L470,430 C350,495 298,600 295,720 L295,880 Z"),
    ("tri", "M762,358 L948,455 L765,590 Z"),
]
ALL_K = " ".join(d for _, d in SHAPES)

# --- Palette -------------------------------------------------------------------------------
BG_EDGE, BG_CENTER = "#1A3E60", "#2B7FA8"                 # bleu acier -> bleu électrique (45°)
DISC = [(0, "#FF3A9CC8"), (0.45, "#FF2B7FA8"), (1, "#FF16344F")]   # radial : centre -> bords
SKY = [(0, "#FF15365F"), (0.55, "#FF0E2240"), (1, "#FF0B192C")]   # bleu nuit très sombre
SKY_C, SKY_R = (520, 470), 560
STAR_CYAN, STAR_LIGHT = "#00E5FF", "#9FF6FF"
RIM = [(0, "#FFFFFFFF"), (0.45, "#FF00B4DB"), (1, "#FF0083B0")]  # reflet spéculaire -> cyan -> azur
GLOW = "#00E5FF"
# Épaisseur du verre : copies décalées, de la face arrière (sombre) vers l'avant (cyan).
DEPTH, STEP = 7, (2.0, 3.0)
EDGE_BACK, EDGE_FRONT = (0x00, 0x3A, 0x58), (0x00, 0xB4, 0xDB)
SHADOW_OFFSET = (20, 32)

rnd = random.Random(42)


def bbox(d):
    n = list(map(float, re.findall(r"-?\d+(?:\.\d+)?", d)))
    xs, ys = n[0::2], n[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def circ(x, y, r):
    return (f"M{x - r:.1f},{y:.1f} a{r:.1f},{r:.1f} 0 1,0 {2 * r:.1f},0 "
            f"a{r:.1f},{r:.1f} 0 1,0 {-2 * r:.1f},0 Z")


# --- Scène ---------------------------------------------------------------------------------
# Un élément est ("path", props) ou ("clip", d, [éléments]) ou ("move", dx, dy, [éléments]).
# props : d, fill (couleur | ("lin", x1,y1,x2,y2, stops) | ("rad", cx,cy,r, stops)), fa,
#         stroke (idem), sa, sw.
def P(d, **kw):
    return ("path", dict(d=d, **kw))


def glass_inside(d):
    x0, y0, x1, y1 = bbox(d)
    w, h = x1 - x0, y1 - y0
    rect = f"M{x0 - 30},{y0 - 30} L{x1 + 30},{y0 - 30} L{x1 + 30},{y1 + 30} L{x0 - 30},{y1 + 30} Z"
    out = [P(d, fill=("rad", SKY_C[0], SKY_C[1], SKY_R, SKY))]
    # léger voile de nébuleuse cyan / bleu
    for col, a in (("00E5FF", "33"), ("2B6FD8", "40")):
        cx, cy = x0 + rnd.uniform(0.2, 0.8) * w, y0 + rnd.uniform(0.2, 0.8) * h
        out.append(P(rect, fill=("rad", cx, cy, max(w, h) * 0.5, [(0, f"#{a}{col}"), (1, f"#00{col}")])))
    # micro-points lumineux bleus et cyan
    n = int(w * h / 1000) + 6
    for r, col, a, k in ((2.0, STAR_CYAN, 0.55, n), (3.0, STAR_CYAN, 0.9, n // 2),
                         (3.4, STAR_LIGHT, 1.0, n // 4), (4.6, "#FFFFFF", 1.0, max(2, n // 10))):
        out.append(P(" ".join(circ(x0 + rnd.random() * w, y0 + rnd.random() * h, r) for _ in range(k)),
                     fill=col, fa=a))
    # reflet spéculaire en haut de la pièce
    gloss = f"M{x0 - 30},{y0 - 30} L{x1 + 30},{y0 - 30} L{x1 + 30},{y0 + h * 0.16} L{x0 - 30},{y0 + h * 0.38} Z"
    out.append(P(gloss, fill=("lin", 0, y0, 0, y0 + h * 0.38, [(0, "#59FFFFFF"), (1, "#00FFFFFF")])))
    # biseau interne : arête claire (moitié intérieure du trait, l'autre est coupée par le clip)
    out.append(P(d, stroke="#FFFFFF", sa=0.3, sw=16))
    out.append(P(d, stroke="#00E5FF", sa=0.35, sw=6))
    return out


def scene():
    out = [
        P(circ(CX, CY, R), fill=("rad", CX - 60, CY - 120, R * 1.05, DISC)),
        P(circ(CX, CY, R - 6), stroke="#7FD0F0", sa=0.35, sw=5),
        P(circ(CX, CY, R - 16), stroke="#0B2440", sa=0.45, sw=10),
        # ombre portée douce : noyau + halos de plus en plus larges et transparents
        ("move", *SHADOW_OFFSET, [
            P(ALL_K, stroke="#000000", sa=0.05, sw=60),
            P(ALL_K, stroke="#000000", sa=0.07, sw=38),
            P(ALL_K, stroke="#000000", sa=0.10, sw=18),
            P(ALL_K, fill="#000000", fa=0.4),
        ]),
        # halo cyan autour des arêtes
        P(ALL_K, stroke=GLOW, sa=0.10, sw=56),
        P(ALL_K, stroke=GLOW, sa=0.18, sw=34),
        P(ALL_K, stroke=GLOW, sa=0.35, sw=16),
    ]
    for k in range(DEPTH, 0, -1):          # tranche du verre
        t = 1 - (k - 1) / (DEPTH - 1)
        c = "#%02X%02X%02X" % tuple(round(b + (f - b) * t) for b, f in zip(EDGE_BACK, EDGE_FRONT))
        out.append(("move", STEP[0] * k, STEP[1] * k, [P(ALL_K, fill=c, stroke=c, sw=3)]))
    for _, d in SHAPES:
        out.append(("clip", d, glass_inside(d)))
        x0, y0, x1, y1 = bbox(d)
        out.append(P(d, stroke=("lin", x0, y0, x1, y1, RIM), sw=8))
    return out


# --- SVG -----------------------------------------------------------------------------------
class Svg:
    def __init__(self):
        self.defs, self.n = [], 0

    def paint(self, v, attr):
        if isinstance(v, str):
            return f'{attr}="{v}"'
        self.n += 1
        gid = f"g{self.n}"
        stops = "".join(f'<stop offset="{o}" stop-color="#{c[3:]}" stop-opacity="{int(c[1:3], 16) / 255:.2f}"/>'
                        for o, c in v[-1])
        if v[0] == "lin":
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{v[1]:.1f}" '
                             f'y1="{v[2]:.1f}" x2="{v[3]:.1f}" y2="{v[4]:.1f}">{stops}</linearGradient>')
        else:
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{v[1]:.1f}" '
                             f'cy="{v[2]:.1f}" r="{v[3]:.1f}">{stops}</radialGradient>')
        return f'{attr}="url(#{gid})"'

    def emit(self, items):
        out = []
        for it in items:
            if it[0] == "path":
                p = it[1]
                a = [f'd="{p["d"]}"']
                a.append(self.paint(p["fill"], "fill") if "fill" in p else 'fill="none"')
                if "fa" in p:
                    a.append(f'fill-opacity="{p["fa"]}"')
                if "stroke" in p:
                    a += [self.paint(p["stroke"], "stroke"), f'stroke-width="{p["sw"]}"', 'stroke-linejoin="round"']
                    if "sa" in p:
                        a.append(f'stroke-opacity="{p["sa"]}"')
                out.append(f'<path {" ".join(a)}/>')
            elif it[0] == "clip":
                self.n += 1
                self.defs.append(f'<clipPath id="c{self.n}"><path d="{it[1]}"/></clipPath>')
                out.append(f'<g clip-path="url(#c{self.n})">{"".join(self.emit(it[2]))}</g>')
            else:
                out.append(f'<g transform="translate({it[1]},{it[2]})">{"".join(self.emit(it[3]))}</g>')
        return out


def svg():
    s = Svg()
    body = "\n".join(s.emit(scene()))
    bg = (f'<linearGradient id="bg" x1="0" y1="108" x2="108" y2="0" gradientUnits="userSpaceOnUse">'
          f'<stop offset="0" stop-color="{BG_EDGE}"/><stop offset="0.5" stop-color="{BG_CENTER}"/>'
          f'<stop offset="1" stop-color="{BG_EDGE}"/></linearGradient>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="864" height="864">'
            f'<defs>{bg}{"".join(s.defs)}</defs><rect width="108" height="108" fill="url(#bg)"/>'
            f'<g transform="translate({TX:.4f},{TY:.4f}) scale({S:.6f})">{body}</g></svg>\n')


# --- VectorDrawable ------------------------------------------------------------------------
def vd_paint(v, attr):
    if isinstance(v, str):
        return f' android:{attr}="{v}"', ""
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in v[-1])
    if v[0] == "lin":
        g = (f'android:type="linear" android:startX="{v[1]:.1f}" android:startY="{v[2]:.1f}" '
             f'android:endX="{v[3]:.1f}" android:endY="{v[4]:.1f}"')
    else:
        g = (f'android:type="radial" android:centerX="{v[1]:.1f}" android:centerY="{v[2]:.1f}" '
             f'android:gradientRadius="{v[3]:.1f}"')
    return "", f'<aapt:attr name="android:{attr}"><gradient {g}>{items}</gradient></aapt:attr>'


def vd_emit(items, ind="    "):
    out = []
    for it in items:
        if it[0] == "path":
            p = it[1]
            attrs, inner = f' android:pathData="{p["d"]}"', ""
            if "fill" in p:
                a, i = vd_paint(p["fill"], "fillColor")
                attrs, inner = attrs + a, inner + i
            if "fa" in p:
                attrs += f' android:fillAlpha="{p["fa"]}"'
            if "stroke" in p:
                a, i = vd_paint(p["stroke"], "strokeColor")
                attrs += a + f' android:strokeWidth="{p["sw"]}" android:strokeLineJoin="round"'
                inner += i
                if "sa" in p:
                    attrs += f' android:strokeAlpha="{p["sa"]}"'
            out.append(f"{ind}<path{attrs}>{inner}</path>" if inner else f"{ind}<path{attrs}/>")
        elif it[0] == "clip":
            out += [f"{ind}<group>", f'{ind}  <clip-path android:pathData="{it[1]}"/>']
            out += vd_emit(it[2], ind + "  ")
            out.append(f"{ind}</group>")
        else:
            out.append(f'{ind}<group android:translateX="{it[1]}" android:translateY="{it[2]}">')
            out += vd_emit(it[3], ind + "  ")
            out.append(f"{ind}</group>")
    return out


def fg():
    o = ['<?xml version="1.0" encoding="utf-8"?>',
         "<!-- Icône Kodifin (avant-plan adaptatif). Générée par scripts/gen_icon.py, ne pas éditer à la main. -->",
         '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
         '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
         f'  <group android:translateX="{TX:.4f}" android:translateY="{TY:.4f}" android:scaleX="{S:.6f}" android:scaleY="{S:.6f}">']
    o += vd_emit(scene())
    o += ["  </group>", "</vector>"]
    return "\n".join(o) + "\n"


def mono():
    s = 36.0 / (900 - 85)
    tx, ty = 54 - (85 + 948) / 2 * s, 54 - (58 + 880) / 2 * s
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Icône Kodifin monochrome (icônes thématiques Android 13+). Générée par scripts/gen_icon.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
  <group android:translateX="{tx:.4f}" android:translateY="{ty:.4f}" android:scaleX="{s:.6f}" android:scaleY="{s:.6f}">
    <path android:pathData="{ALL_K}" android:fillColor="#FFFFFFFF"/>
  </group>
</vector>
'''


if __name__ == "__main__":
    print({"svg": svg, "fg": fg, "mono": mono}[sys.argv[1]](), end="")
