"""OnTime Pickup 시연 영상 빌드: 녹화 클립 + 자막 띠 + TTS(Yuna 내레이션, Reed 운전자) → 1280x720 mp4"""
import subprocess, json, glob, sys, os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

R = Path(sys.argv[1]); PROJ = Path(sys.argv[2]); OUT = Path(sys.argv[3])
W, H, BAND = 1280, 720, 84
NAVY, MINT = (14, 26, 43), (60, 224, 180)
FONT = str(PROJ / "server/static/fonts/AstaSans.ttf")
NARR = "Yuna"; DRIVER = "Reed (한국어(한국))"; TEMPO = 1.2
work = R / "build"; work.mkdir(exist_ok=True)
A = str(R / "a.mp4"); DA = glob.glob(str(R / "dashA/*.webm"))[0]; DB = glob.glob(str(R / "dashB/*.webm"))[0]

def font(size, wght):
    f = ImageFont.truetype(FONT, size); f.set_variation_by_axes([wght]); return f
def run(*a): subprocess.run(a, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
def dur(p): return float(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration","-of","csv=p=0",p]).decode())

logo = Image.open(work / "logo.png").convert("RGBA") if (work / "logo.png").exists() else None
wordmark = Image.open(work / "wordmark.png").convert("RGBA")

def band_png(text, path):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, H - BAND, W, H], fill=NAVY + (255,))
    d.rectangle([0, H - BAND, W, H - BAND + 3], fill=MINT + (255,))
    if logo: im.alpha_composite(logo.resize((44, 44), Image.LANCZOS), (24, H - BAND + 22))
    f = font(27, 700)
    tw = d.textlength(text, font=f)
    if tw > W - 200: f = font(23, 700); tw = d.textlength(text, font=f)
    d.text(((W - tw) / 2, H - BAND / 2 + 2), text, font=f, fill=(255, 255, 255), anchor="lm")
    im.save(path)

def card_png(lines, path):
    im = Image.new("RGB", (W, H), NAVY); d = ImageDraw.Draw(im)
    wm = wordmark.copy(); wm.thumbnail((760, 300), Image.LANCZOS)
    y = 200 if len(lines) else 270
    im.paste(wm, ((W - wm.width) // 2, y), wm)
    yy = y + wm.height + 46
    for txt, size, wght, col in lines:
        f = font(size, wght); tw = d.textlength(txt, font=f)
        d.text(((W - tw) / 2, yy), txt, font=f, fill=col); yy += size + 22
    im.save(path)

def tts(parts, path):
    """parts: [(voice, text)] → 1.2배속 wav. 운전자 대사 뒤엔 짧은 쉼."""
    segs = []
    for i, (v, t) in enumerate(parts):
        a = work / f"{path.stem}_{i}.aiff"; run("say", "-v", v, "-o", str(a), t)
        segs.append(str(a))
    lst = work / f"{path.stem}.txt"
    gap = work / "gap.wav"
    if not gap.exists(): run("ffmpeg","-y","-f","lavfi","-i","anullsrc=r=44100:cl=mono","-t","0.35",str(gap))
    with open(lst, "w") as f:
        for i, s in enumerate(segs):
            w = work / f"{path.stem}_{i}.wav"; run("ffmpeg","-y","-i",s,"-ar","44100","-ac","1",str(w))
            f.write(f"file '{w}'\n")
            if i < len(segs) - 1: f.write(f"file '{gap}'\n")
    raw = work / f"{path.stem}_raw.wav"
    run("ffmpeg","-y","-f","concat","-safe","0","-i",str(lst),"-c","copy",str(raw))
    run("ffmpeg","-y","-i",str(raw),"-filter:a",f"atempo={TEMPO}","-ar","44100","-ac","1",str(path))
    return dur(str(path))

# (src, in, out, caption, [(voice,text)], max_speed)
Y = NARR
SCENES = [
  ("card", 0, 0, None, [(Y, "온타임 픽업. 도착 시간에 맞춰 조리되는, 차량용 포장 주문 앱입니다.")], None),
  (A, 0.6, 7.6, "주차 중 · 메뉴 옆 ☆로 '늘 먹던 거' 저장",
     [(Y, "주차 중에 메뉴 옆 별을 눌러, 늘 먹던 메뉴를 저장해 둡니다.")], 1.4),
  (A, 7.6, 10.4, "주행 중엔 \"글레오, 늘 먹던 거 포장해줘\" 한 마디로 주문 (에뮬레이터에선 버튼으로 실행)",
     [(DRIVER, "글레오, 늘 먹던 거 포장해줘."), (Y, "주행 중에는 이 한 마디면 주문이 끝납니다.")], 1.2),
  (A, 10.4, 21.0, "NaviHelper가 매장을 경로에 넣고, 매장까지 남은 시간을 받아 옴",
     [(Y, "주문하면 매장을 내비게이션 경로에 넣고, 매장까지 남은 시간을 받아옵니다.")], 1.6),
  (A, 21.0, 34.0, "실제 내비 도착 시간(8분)으로 조리 시작 · 차량 번호와 사전결제가 매장에 전달됨",
     [(Y, "실제 내비 도착 시간에 맞춰 조리 시작 시각이 정해지고, 차량 번호와 사전 결제 정보가 매장에 전달됩니다.")], 1.6),
  (DA, 0.8, 9.0, "같은 주문이 매장 태블릿에 바로 표시 · 링이 조리 완료까지 남은 시간",
     [(Y, "같은 주문이 매장 태블릿에 바로 뜹니다.")], 1.6),
  (DB, 3.5, 11.0, "시뮬레이션 · 30분 거리에서 주문 (30배속) → 조리는 도착에 맞춰 대기",
     [(Y, "이번엔 30분 거리에서 주문해 봅니다. 조리는 바로 시작하지 않고, 도착 시간에 맞춰 기다립니다.")], 1.5),
  (DB, 11.0, 30.0, "사고 정체로 도착 6분 지연 → 조리 시작도 자동으로 연기 (점주 조작 없음)",
     [(Y, "길이 막혀 도착이 6분 늦어지면, 조리 시작도 자동으로 함께 미뤄집니다. 점주는 아무것도 하지 않아도 됩니다.")], 1.6),
  (DB, 30.0, 70.0, "정체 일부 해소 → 조리 시작 다시 당김 → 도착 시각에 맞춰 조리",
     [(Y, "정체가 풀리면 다시 당겨지고, 도착 직전에 음식이 완성되도록 조리가 시작됩니다.")], 2.6),
  (DB, 70.0, 88.0, "차량이 매장 경유지에 도착 → '12가 3456 도착' 알림, 직원이 차 앞으로 전달",
     [(Y, "차가 매장에 도착하면 차량 번호와 함께 알림이 뜨고, 직원이 차 앞으로 음식을 가져다줍니다.")], 1.6),
  ("card", 0, 0, "end", [(Y, "기다림 없이, 식지 않은 음식을 차 앞에서. 온타임 픽업입니다.")], None),
]

clips = []
for i, (src, a, b, cap, voice, maxsp) in enumerate(SCENES):
    wav = work / f"s{i:02d}.wav"; vl = tts(voice, wav)
    out = work / f"s{i:02d}.mp4"
    if src == "card":
        png = work / f"s{i:02d}.png"
        if cap == "end":
            card_png([("기다림 없이, 식지 않은 음식을 차 앞에서", 34, 700, (255, 255, 255)),
                      ("github.com/Koooon-and-meemee/ontime-pickup", 22, 500, (154, 167, 184))], png)
        else:
            card_png([("현대자동차그룹 차량용 앱 개발 공모전 · Pleos Playground", 24, 500, (154, 167, 184))], png)
        T = vl + 1.2
        run("ffmpeg","-y","-loop","1","-framerate","30","-i",str(png),"-i",str(wav),
            "-filter_complex",f"[0:v]fade=t=in:st=0:d=0.4,fade=t=out:st={T-0.4:.2f}:d=0.4,format=yuv420p[v];[1:a]adelay=500|500,apad[a]",
            "-map","[v]","-map","[a]","-t",f"{T:.2f}","-r","30","-c:v","libx264","-preset","medium","-crf","18","-c:a","aac","-b:a","160k","-ar","44100","-ac","2",str(out))
    else:
        src_len = b - a
        target = vl + 0.7
        sp = min(maxsp, max(TEMPO, src_len / target))
        vid_len = src_len / sp
        T = max(vid_len, target)
        hold = T - vid_len
        band = work / f"s{i:02d}_band.png"; band_png(cap, band)
        ch = H - BAND; cw = round(ch * 16 / 9 / 2) * 2
        vf = (f"[0:v]trim=start={a}:end={b},setpts=(PTS-STARTPTS)/{sp},fps=30,scale={cw}:{ch}:flags=lanczos,"
              f"tpad=stop_mode=clone:stop_duration={hold+0.1:.2f},pad={W}:{H}:{(W-cw)//2}:0:color=0x0E1A2B[c];"
              f"[c][1:v]overlay=0:0,format=yuv420p[v];[2:a]adelay=250|250,apad[a]")
        run("ffmpeg","-y","-i",src,"-loop","1","-i",str(band),"-i",str(wav),"-filter_complex",vf,
            "-map","[v]","-map","[a]","-t",f"{T:.2f}","-r","30","-c:v","libx264","-preset","medium","-crf","18",
            "-c:a","aac","-b:a","160k","-ar","44100","-ac","2",str(out))
    clips.append(out); print(f"scene {i}: {dur(str(out)):.1f}s", flush=True)

lst = work / "clips.txt"; lst.write_text("".join(f"file '{c}'\n" for c in clips))
run("ffmpeg","-y","-f","concat","-safe","0","-i",str(lst),"-c","copy","-movflags","+faststart",str(OUT))
print("total", round(dur(str(OUT)), 1), OUT)
