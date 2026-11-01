import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
SRC = sys.argv[1]; OUT = sys.argv[2]
S = 1024                                    # adaptive-icon master (108dp = 1024px)
SAFE = int(S * 66 / 108)                    # 66dp safe zone
FONT = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"

def hex_art(size):
    im = Image.open(SRC).convert("RGBA")
    return im.resize((size, size), Image.LANCZOS)

def badge(text, h, pad, radius, fill, fg, angle):
    f = ImageFont.truetype(FONT, int(h * 0.68))
    l, t, r, b = f.getbbox(text)
    w = r - l + pad * 2
    im = Image.new("RGBA", (w + 8, h + 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((4, 4, 4 + w, 4 + h), radius=radius, fill=fill)
    d.text((4 + pad - l, 4 + (h - (b - t)) // 2 - t), text, font=f, fill=fg)
    return im.rotate(angle, resample=Image.BICUBIC, expand=True)

# --- background: deep charcoal with a soft radial glow in the brand gradient
bg = Image.new("RGBA", (S, S), (22, 24, 30, 255))
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse((S*0.18, S*0.18, S*0.82, S*0.82), fill=(96, 110, 150, 110))
glow = glow.filter(ImageFilter.GaussianBlur(S * 0.12))
bg.alpha_composite(glow)

# --- foreground: the hexagon, centred in the safe zone, with a drop shadow
fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
art_size = int(SAFE * 0.92)
art = hex_art(art_size)
shadow = Image.new("RGBA", art.size, (0, 0, 0, 0))
shadow.paste((0, 0, 0, 160), (0, 0), art.split()[3])
shadow = shadow.filter(ImageFilter.GaussianBlur(art_size * 0.035))
ax = (S - art_size) // 2; ay = (S - art_size) // 2 - int(S * 0.02)
fg.alpha_composite(shadow, (ax + int(art_size*0.01), ay + int(art_size*0.03)))
fg.alpha_composite(art, (ax, ay))

# --- "NEXT" ribbon: tilted, tucked into the hexagon's open lower-right edge
bh = int(SAFE * 0.19)
rb = badge("NEXT", bh, pad=int(bh*0.45), radius=int(bh*0.5), fill=(255, 255, 255, 255), fg=(24, 26, 34, 255), angle=-14)
bx = ax + int(art_size * 0.50); by = ay + int(art_size * 0.72)
# keep the ribbon inside the safe zone
bx = min(bx, (S + SAFE)//2 - rb.width); by = min(by, (S + SAFE)//2 - rb.height)
rsh = Image.new("RGBA", rb.size, (0,0,0,0)); rsh.paste((0,0,0,150),(0,0),rb.split()[3])
rsh = rsh.filter(ImageFilter.GaussianBlur(bh*0.12))
fg.alpha_composite(rsh, (bx, by + int(bh*0.08)))
fg.alpha_composite(rb, (bx, by))

bg.save(f"{OUT}/bg.png"); fg.save(f"{OUT}/fg.png")
# composed previews: full square and the circle/squircle masks launchers apply
full = bg.copy(); full.alpha_composite(fg)
full.save(f"{OUT}/full.png")
def masked(shape):
    m = Image.new("L", (S, S), 0); d = ImageDraw.Draw(m)
    inset = (S - int(S*72/108)) // 2
    box = (inset, inset, S - inset, S - inset)
    if shape == "circle": d.ellipse(box, fill=255)
    else: d.rounded_rectangle(box, radius=int(S*0.19), fill=255)
    out = full.copy(); out.putalpha(m)
    return out.crop(box)
masked("circle").save(f"{OUT}/preview_circle.png")
masked("squircle").save(f"{OUT}/preview_squircle.png")
print("ok")
