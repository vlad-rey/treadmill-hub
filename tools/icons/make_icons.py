"""Иконка приложения: крыша дома (хаб), молния (станции), зелёная полоса (лента дорожки).
Пишет favicon.svg и PNG 32/180/192/512 в assets/web. Запуск: python tools/icons/make_icons.py"""
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[2] / "android/app/src/main/assets/web"
BG, WHITE, BOLT, GREEN = "#101418", "#f2f5f7", "#f0b429", "#2fbf71"
ROOF = [(136, 236), (256, 124), (376, 236)]
_BOLT = [(282, 164), (216, 262), (254, 262), (232, 344), (302, 238), (264, 238), (292, 164)]
BOLT_PTS = [(round(258 + 0.82 * (x - 258)), round(254 + 0.82 * (y - 254) + 18)) for x, y in _BOLT]
BAR = (136, 364, 376, 404)

svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
<rect width="512" height="512" rx="112" fill="{BG}"/>
<polyline points="{' '.join(f'{x},{y}' for x, y in ROOF)}" fill="none" stroke="{WHITE}" stroke-width="44" stroke-linecap="round" stroke-linejoin="round"/>
<polygon points="{' '.join(f'{x},{y}' for x, y in BOLT_PTS)}" fill="{BOLT}" stroke="{BOLT}" stroke-width="8" stroke-linejoin="round"/>
<rect x="{BAR[0]}" y="{BAR[1]}" width="{BAR[2] - BAR[0]}" height="{BAR[3] - BAR[1]}" rx="20" fill="{GREEN}"/>
</svg>
"""
(OUT / "favicon.svg").write_text(svg, encoding="utf-8")


def render(size: int, rounded: bool) -> Image.Image:
    k = 4 * size / 512  # рисуем в 4× и уменьшаем — сглаживание
    big = 4 * size
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if rounded:
        d.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(112 * k), fill=BG)
    else:
        d.rectangle((0, 0, big, big), fill=BG)  # maskable: фон до края, система сама скруглит
    s = lambda pts: [(x * k, y * k) for x, y in pts]
    w = int(44 * k)
    d.line(s(ROOF), fill=WHITE, width=w, joint="curve")
    for x, y in ROOF[::2]:
        r = w / 2
        d.ellipse((x * k - r, y * k - r, x * k + r, y * k + r), fill=WHITE)
    d.polygon(s(BOLT_PTS), fill=BOLT)
    d.rounded_rectangle(tuple(v * k for v in BAR), radius=int(20 * k), fill=GREEN)
    return im.resize((size, size), Image.LANCZOS)


render(32, True).save(OUT / "favicon-32.png")
render(180, False).save(OUT / "apple-touch-icon.png")
render(192, False).save(OUT / "icon-192.png")
render(512, False).save(OUT / "icon-512.png")
print("ok:", OUT)
