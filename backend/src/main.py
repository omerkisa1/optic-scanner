from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse, StreamingResponse
import io
import re
import time
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import pytesseract
import easyocr

try:
    easy_reader = easyocr.Reader(["tr", "en"], gpu=False)
    print("[OCR] EasyOCR reader initialized")
except Exception as e:
    easy_reader = None
    print(f"[OCR] EasyOCR reader init failed: {e}")


# ──────────────────────────────────────────────────────────────────────────────
# Yardımcı: Türkçe destekli TrueType font
# ──────────────────────────────────────────────────────────────────────────────

def _get_font(size: int):
    """Türkçe karakterleri (Ö Ğ Ş İ Ü Ç) destekleyen sistem fontu döndürür."""
    paths = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
        "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/Library/Fonts/Arial.ttf",
    ]
    for p in paths:
        try:
            return ImageFont.truetype(p, size)
        except (IOError, OSError):
            continue
    return ImageFont.load_default()


def _text_wh(font, text: str):
    """Metnin (genişlik, yükseklik) piksel boyutunu döndürür."""
    try:
        b = font.getbbox(text)
        return b[2] - b[0], b[3] - b[1]
    except AttributeError:
        return font.getsize(text)


# ──────────────────────────────────────────────────────────────────────────────
# Yardımcı: OCR dil seçimi ve alan okuma
# ──────────────────────────────────────────────────────────────────────────────

def _ocr_lang() -> str:
    try:
        return "tur+eng" if "tur" in pytesseract.get_languages(config="") else "eng"
    except Exception:
        return "eng"


def _ocr_field(crop_gray: np.ndarray, lang: str) -> str:
    # Daha geniş kenar — bağlamı korur
    bordered = cv2.copyMakeBorder(crop_gray, 30, 30, 30, 30,
                                  cv2.BORDER_CONSTANT, value=255)

    # 4× büyütme: daha yüksek efektif DPI
    up = cv2.resize(bordered, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC)

    # Koyu zemin → çevir
    if np.mean(up) < 127:
        up = cv2.bitwise_not(up)

    # Kenar korumalı gürültü azaltma
    denoised = cv2.bilateralFilter(up, 9, 75, 75)

    # Kontrast iyileştirme (CLAHE) — eşit olmayan aydınlatmayı dengeler
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(denoised)

    # Otsu eşikleme — baskılı / blok harfler için güçlü
    _, otsu = cv2.threshold(enhanced, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # Adaptif eşikleme — el yazısı ve eşit olmayan aydınlatma için güçlü
    adaptive = cv2.adaptiveThreshold(
        enhanced, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY,
        51, 10
    )

    # İnce vuruşları kalınlaştır (adaptif varyant)
    kernel = np.ones((2, 2), np.uint8)
    inv = cv2.bitwise_not(adaptive)
    adaptive_dilated = cv2.bitwise_not(cv2.dilate(inv, kernel, iterations=1))

    best, best_score = "", 0

    def _try(img: np.ndarray, oem: int):
        nonlocal best, best_score
        pil = Image.fromarray(img)
        for psm in (7, 13):
            try:
                raw = pytesseract.image_to_string(
                    pil, lang=lang,
                    config=f"--psm {psm} --oem {oem} --dpi 300"
                )
            except Exception:
                continue
            # İsim alanı: yalnızca harf ve boşluk
            cleaned = re.sub(r"[^a-zA-ZğüşıöçĞÜŞİÖÇ\s]", "", raw)
            cleaned = re.sub(r"\s+", " ", cleaned).strip()
            score = sum(1 for c in cleaned if c.isalpha())
            if score > best_score:
                best_score, best = score, cleaned

    # Birincil: LSTM motoru (OEM 1) — tüm varyantlarla
    for variant in (adaptive, adaptive_dilated, otsu, enhanced, up):
        _try(variant, oem=1)

    # Geri dönüş: varsayılan motor (OEM 3) — yeterli sonuç yoksa
    if best_score < 2:
        for variant in (adaptive, otsu):
            _try(variant, oem=3)

    return best if best_score >= 1 else "Okunamadı"


def _ocr_name_easyocr(roi_bgr: np.ndarray) -> str:
    if easy_reader is None:
        return ""
    if roi_bgr is None or roi_bgr.size == 0:
        return ""

    working = roi_bgr
    h, w = working.shape[:2]
    if w > 0 and w < 800:
        scale = 800.0 / float(w)
        working = cv2.resize(working, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)

    gray = cv2.cvtColor(working, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)
    padded = cv2.copyMakeBorder(enhanced, 20, 20, 20, 20, cv2.BORDER_CONSTANT, value=255)

    results = easy_reader.readtext(padded, detail=1, paragraph=False)
    if not results:
        results = easy_reader.readtext(working, detail=1, paragraph=False)
    if not results:
        return ""

    texts = []
    for item in results:
        if len(item) < 3:
            continue
        _, text, conf = item
        stripped = text.strip()
        if conf > 0.2 and len(stripped) > 0:
            texts.append(stripped)

    raw = " ".join(texts)
    cleaned = re.sub(r"[^a-zA-ZğüşıöçĞÜŞİÖÇ\s]", "", raw)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()

    if cleaned:
        words = []
        for token in cleaned.split():
            low = token.lower()
            if not low:
                continue
            if low[0] == "i":
                words.append("İ" + low[1:])
            elif low[0] == "ı":
                words.append("I" + low[1:])
            else:
                words.append(low[0].upper() + low[1:])
        cleaned = " ".join(words)

    return cleaned


def _ocr_name_hybrid(roi_bgr: np.ndarray, lang: str):
    easy_result = _ocr_name_easyocr(roi_bgr)

    tess_result = ""
    if roi_bgr is not None and roi_bgr.size > 0:
        gray = cv2.cvtColor(roi_bgr, cv2.COLOR_BGR2GRAY)
        tess_result = _ocr_field(gray, lang)
        if tess_result == "Okunamadı":
            tess_result = ""

    candidates = []
    if easy_result and len(easy_result) >= 2:
        alpha_score = sum(1 for c in easy_result if c.isalpha())
        candidates.append((easy_result, alpha_score, "easyocr"))

    if tess_result and len(tess_result) >= 2:
        alpha_score = sum(1 for c in tess_result if c.isalpha())
        candidates.append((tess_result, alpha_score, "tesseract"))

    if not candidates:
        debug = {
            "easyocr_result": easy_result,
            "tesseract_result": tess_result,
            "selected": "none",
            "final": "",
        }
        return "", debug

    best = max(candidates, key=lambda x: x[1])
    print(f"[OCR] EasyOCR: '{easy_result}' | Tesseract: '{tess_result}' | Secilen: {best[2]} -> '{best[0]}'")

    debug = {
        "easyocr_result": easy_result,
        "tesseract_result": tess_result,
        "selected": best[2],
        "final": best[0],
    }
    return best[0], debug


# ──────────────────────────────────────────────────────────────────────────────
app = FastAPI(title="OMR Backend API", description="SaaS tabanlı Optik Okuyucu Backend'i")


# ──────────────────────────────────────────────────────────────────────────────
# 1. Şema Endpoint'i
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/schema")
async def get_schema(question_count: int = 20):
    """
    Form düzenini tanımlar.
    Tüm koordinatlar 0.0–1.0 arası oransal değerlerdir.

    Üst bölüm düzeni (1000×1400 px formda):
      Sol  → İsim (OCR kutusu)          x: 0.14–0.50,  y: ~0.08
      Sağ  → Öğrenci Numarası (9×OMR)   x: 0.530–0.908, y: ~0.069–0.228
      ─────────────────────── ayırıcı çizgi ──────────────  y: 0.250
      Sorular                            y: 0.28 →
    """
    questions = []
    options_labels = ["A", "B", "C", "D", "E"]

    start_y    = 0.28
    y_step     = 0.03
    base_x     = 0.15
    opt_x_step = 0.05

    current_y = start_y
    for i in range(1, question_count + 1):
        if current_y > 0.88:
            current_y = start_y
            base_x   += 0.40
        options = [
            {"val": val, "x": round(base_x + j * opt_x_step, 3), "y": round(current_y, 3)}
            for j, val in enumerate(options_labels)
        ]
        questions.append({"q_no": i, "options": options})
        current_y += y_step

    return {
        "template_id": "omr_v2",
        "base_aspect_ratio": 0.71,
        "anchors": [
            {"id": "top_left",     "x": 0.05, "y": 0.05},
            {"id": "middle_left",  "x": 0.05, "y": 0.50},
            {"id": "bottom_left",  "x": 0.05, "y": 0.95},
            {"id": "top_right",    "x": 0.95, "y": 0.05},
            {"id": "middle_right", "x": 0.95, "y": 0.50},
            {"id": "bottom_right", "x": 0.95, "y": 0.95},
        ],
        # Sadece isim OCR ile okunur; öğrenci numarası artık OMR ile alınır
        "fields": [
            {"name": "student_name", "label": "İsim", "x": 0.14, "y": 0.083, "w": 0.36, "h": 0.042}
        ],
        # 9 basamaklı öğrenci numarası OMR grid'i — üst sağ bölge
        # Her sütun = 1 mini-grid (üstte el yazısı kutusu + altta 0-9 daireler)
        # Sütun merkezleri: x_start + col * x_step
        # Köşe markerlarına değmez: sağ marker x=0.95 ± 0.02 → 0.930..0.970
        #                           son sütun sağ kenarı = (0.551+8×0.042)+0.021 = 0.908 ✓
        "student_number_grid": {
            "label":          "Öğrenci Numarası",
            "digit_count":    9,
            "x_start":        0.551,   # ilk sütun merkezi
            "x_step":         0.042,   # sütunlar arası mesafe
            "col_half_w":     0.021,   # mini-grid yarı genişliği (x_step/2)
            "y_label":        0.069,   # başlık metni y
            "y_grid_top":     0.078,   # dikdörtgen üst kenarı
            "y_box_bottom":   0.108,   # el yazısı kutu alt kenarı / ayırıcı çizgi
            "y_circle_start": 0.117,   # satır-0 daire merkezi
            "y_circle_step":  0.0114,  # satırlar arası (≈16 px)
            "y_grid_bottom":  0.228,   # dikdörtgen alt kenarı
            "bubble_radius":  0.010,   # daire yarıçapı oranı
        },
        "separator_y": 0.250,          # öğrenci bilgisi / soru bölgesi ayırıcısı
        "questions": questions,
        "metadata": {
            "total_questions": question_count,
            "bubble_radius": 0.012,
        },
    }


# ──────────────────────────────────────────────────────────────────────────────
# Yardımcı: perspektif düzeltme için nokta sıralaması
# ──────────────────────────────────────────────────────────────────────────────

def order_points(pts):
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


# ──────────────────────────────────────────────────────────────────────────────
# 2. Form Üretme Endpoint'i
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/generate_form")
async def generate_form(question_count: int = 20):
    """Şemaya göre optik form çizer ve PNG olarak döndürür."""
    schema = await get_schema(question_count)
    W, H   = 1000, 1400
    img    = np.ones((H, W, 3), dtype="uint8") * 255

    # ── A. Anchor marker kareleri ─────────────────────────────────────────────
    anchor_size = 20
    for a in schema["anchors"]:
        cx, cy = int(a["x"] * W), int(a["y"] * H)
        cv2.rectangle(img,
                      (cx - anchor_size, cy - anchor_size),
                      (cx + anchor_size, cy + anchor_size),
                      (0, 0, 0), -1)

    # ── B. İsim OCR kutusu ────────────────────────────────────────────────────
    for field in schema["fields"]:
        fx = int(field["x"] * W);  fy = int(field["y"] * H)
        fw = int(field["w"] * W);  fh = int(field["h"] * H)
        cv2.rectangle(img, (fx, fy), (fx + fw, fy + fh), (0, 0, 0), 2)

    # ── C. Öğrenci Numarası mini-grid dikdörtgenleri + daireler ──────────────
    sn           = schema["student_number_grid"]
    sn_dc        = sn["digit_count"]
    sn_xs        = sn["x_start"]
    sn_xstp      = sn["x_step"]
    sn_chw       = sn["col_half_w"]
    sn_y_gt      = int(sn["y_grid_top"]     * H)
    sn_y_bd      = int(sn["y_box_bottom"]   * H)
    sn_y_gb      = int(sn["y_grid_bottom"]  * H)
    sn_y_cs      = sn["y_circle_start"]
    sn_y_cst     = sn["y_circle_step"]
    sn_br        = int(W * sn["bubble_radius"])

    for col in range(sn_dc):
        cx      = int((sn_xs + col * sn_xstp) * W)
        col_l   = cx - int(sn_chw * W)
        col_r   = cx + int(sn_chw * W)

        # Sütun dış dikdörtgeni
        cv2.rectangle(img, (col_l, sn_y_gt), (col_r, sn_y_gb), (0, 0, 0), 1)
        # El yazısı kutusu alt çizgisi
        cv2.line(img, (col_l, sn_y_bd), (col_r, sn_y_bd), (0, 0, 0), 1)

        # 0–9 daireleri
        for row in range(10):
            cy = int((sn_y_cs + row * sn_y_cst) * H)
            cv2.circle(img, (cx, cy), sn_br, (0, 0, 0), 1)

    # ── D. Öğrenci bilgisi / soru bölgesi ayırıcı çizgisi ───────────────────
    sep_y = int(schema["separator_y"] * H)
    cv2.line(img, (int(0.05 * W), sep_y), (int(0.95 * W), sep_y), (0, 0, 0), 1)

    # ── E. Soru başlıkları (A B C D E) ve balonlar ───────────────────────────
    bub_r = int(W * schema["metadata"]["bubble_radius"])

    col_header_drawn: set = set()
    for q in schema["questions"]:
        fo_x = int(q["options"][0]["x"] * W)
        if fo_x not in col_header_drawn:
            col_header_drawn.add(fo_x)
            for opt in q["options"]:
                ox = int(opt["x"] * W)
                oy = int(q["options"][0]["y"] * H) - int(bub_r * 1.8)
                ts = cv2.getTextSize(opt["val"], cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2)[0]
                cv2.putText(img, opt["val"], (ox - ts[0] // 2, oy),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2)

    for q in schema["questions"]:
        fo_x = int(q["options"][0]["x"] * W)
        fo_y = int(q["options"][0]["y"] * H)
        qt   = f"{q['q_no']}."
        qts  = cv2.getTextSize(qt, cv2.FONT_HERSHEY_SIMPLEX, 1, 2)[0]
        cv2.putText(img, qt,
                    (fo_x - bub_r - qts[0] - 15, fo_y + qts[1] // 2),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
        for opt in q["options"]:
            cv2.circle(img, (int(opt["x"] * W), int(opt["y"] * H)), bub_r, (0, 0, 0), 2)

    # ── F. Türkçe metin katmanı (PIL) ─────────────────────────────────────────
    img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    pil     = Image.fromarray(img_rgb)
    draw    = ImageDraw.Draw(pil)

    f_lbl   = _get_font(22)   # alan etiketi (İsim)
    f_title = _get_font(17)   # bölüm başlığı (Öğrenci Numarası)
    f_pos   = _get_font(12)   # sütun pozisyon numaraları (gri)
    f_row   = _get_font(12)   # satır rakam etiketleri (0-9)

    # İsim etiketi — kutunun soluna yaslanır
    for field in schema["fields"]:
        fx = int(field["x"] * W)
        fy = int(field["y"] * H)
        fh = int(field["h"] * H)
        lbl        = field.get("label", field["name"])
        lbl_w, lbl_h = _text_wh(f_lbl, lbl)
        draw.text((fx - lbl_w - 12, fy + (fh - lbl_h) // 2),
                  lbl, font=f_lbl, fill=(0, 0, 0))

    # "Öğrenci Numarası" başlığı — grid üzerinde ortalanır
    grid_l = int((sn_xs - sn_chw) * W)
    grid_r = int((sn_xs + (sn_dc - 1) * sn_xstp + sn_chw) * W)
    ttl_w, ttl_h = _text_wh(f_title, sn["label"])
    ttl_x  = grid_l + (grid_r - grid_l - ttl_w) // 2
    ttl_y  = int(sn["y_label"] * H)
    draw.text((ttl_x, ttl_y), sn["label"], font=f_title, fill=(0, 0, 0))

    # Sütun pozisyon numaraları (1–9) — el yazısı kutusu içinde, gri
    for col in range(sn_dc):
        cx   = int((sn_xs + col * sn_xstp) * W)
        lbl  = str(col + 1)
        lw, lh = _text_wh(f_pos, lbl)
        box_h = sn_y_bd - sn_y_gt
        draw.text((cx - lw // 2, sn_y_gt + (box_h - lh) // 2),
                  lbl, font=f_pos, fill=(160, 160, 160))

    # Satır rakam etiketleri (0–9) — ilk sütunun soluna
    first_col_l = int((sn_xs - sn_chw) * W)
    for row in range(10):
        cy   = int((sn_y_cs + row * sn_y_cst) * H)
        lbl  = str(row)
        rw, rh = _text_wh(f_row, lbl)
        draw.text((first_col_l - rw - 5, cy - rh // 2),
                  lbl, font=f_row, fill=(0, 0, 0))

    img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)

    # ── G. PNG olarak aktar ───────────────────────────────────────────────────
    ok, buf = cv2.imencode(".png", img)
    return StreamingResponse(io.BytesIO(buf), media_type="image/png")


# ──────────────────────────────────────────────────────────────────────────────
# 3. İşleme Endpoint'i
# ──────────────────────────────────────────────────────────────────────────────

@app.post("/process")
async def process_form(
    file: UploadFile = File(...),
    question_count: int = Form(20),
):
    try:
        contents = await file.read()
        nparr    = np.frombuffer(contents, np.uint8)
        img      = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if img is None:
            return JSONResponse(status_code=400, content={"error": "Geçersiz resim formatı."})

        schema     = await get_schema(question_count)
        maxW, maxH = 1000, 1400

        # ── A. Ön işleme & Anchor tespiti ─────────────────────────────────────
        gray     = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        img_h, img_w = img.shape[:2]
        img_area = img_h * img_w

        blurred      = cv2.GaussianBlur(gray, (5, 5), 0)
        _, thresh_ot = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        kernel       = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
        thresh       = cv2.morphologyEx(thresh_ot, cv2.MORPH_CLOSE, kernel, iterations=2)
        contours, _  = cv2.findContours(thresh, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)

        raw_cands = []
        for c in contours:
            area = cv2.contourArea(c)
            if img_area * 0.00015 < area < img_area * 0.025:
                bx, by, bw, bh = cv2.boundingRect(c)
                aspect   = float(bw) / bh
                solidity = area / float(bw * bh)
                if 0.45 <= aspect <= 2.2 and solidity > 0.60:
                    raw_cands.append((c, area))

        raw_cands.sort(key=lambda x: x[1], reverse=True)
        raw_cands = raw_cands[:20]

        if raw_cands:
            areas    = [a for _, a in raw_cands]
            median_a = float(np.median(areas[:min(8, len(areas))]))
            raw_cands = [(c, a) for c, a in raw_cands
                         if median_a * 0.15 < a < median_a * 6.0]

        anchor_cands = [c for c, _ in raw_cands]

        if len(anchor_cands) < 4:
            return JSONResponse(status_code=400, content={
                "error": (f"Yeterli referans noktası ({len(anchor_cands)} adet) bulunamadı. "
                          "Formu iyi aydınlatılmış, düz bir zeminde çekin ve tüm köşe "
                          "karelerinin görünür olduğundan emin olun.")
            })

        centers = []
        for c in anchor_cands:
            M = cv2.moments(c)
            if M["m00"] != 0:
                centers.append([int(M["m10"] / M["m00"]), int(M["m01"] / M["m00"])])
            else:
                bx, by, bw, bh = cv2.boundingRect(c)
                centers.append([bx + bw // 2, by + bh // 2])

        med_x     = float(np.median([p[0] for p in centers]))
        left_pts  = sorted([p for p in centers if p[0] <  med_x], key=lambda p: p[1])
        right_pts = sorted([p for p in centers if p[0] >= med_x], key=lambda p: p[1])

        if len(left_pts) < 2 or len(right_pts) < 2:
            return JSONResponse(status_code=400, content={
                "error": "Formun sol veya sağ tarafında yeterli marker bulunamadı."
            })

        def pick_col(pts, n=3):
            if len(pts) <= n:
                return pts[:n]
            top, bottom = pts[0], pts[-1]
            if n == 2:
                return [top, bottom]
            mid_y  = (top[1] + bottom[1]) / 2.0
            middle = min(pts[1:-1], key=lambda p: abs(p[1] - mid_y))
            return sorted([top, middle, bottom], key=lambda p: p[1])

        am = {a["id"]: a for a in schema["anchors"]}
        use6 = len(left_pts) >= 3 and len(right_pts) >= 3

        if use6:
            tl, ml_p, bl = pick_col(left_pts,  3)
            tr, mr_p, br = pick_col(right_pts, 3)
            src = np.array([tl, tr, mr_p, br, bl, ml_p], dtype="float32")
            dst = np.array([
                [am["top_left"]["x"]     * maxW, am["top_left"]["y"]     * maxH],
                [am["top_right"]["x"]    * maxW, am["top_right"]["y"]    * maxH],
                [am["middle_right"]["x"] * maxW, am["middle_right"]["y"] * maxH],
                [am["bottom_right"]["x"] * maxW, am["bottom_right"]["y"] * maxH],
                [am["bottom_left"]["x"]  * maxW, am["bottom_left"]["y"]  * maxH],
                [am["middle_left"]["x"]  * maxW, am["middle_left"]["y"]  * maxH],
            ], dtype="float32")
            H_mat, _ = cv2.findHomography(src, dst, cv2.RANSAC, 5.0)
            if H_mat is None:
                return JSONResponse(status_code=400,
                                    content={"error": "Perspektif matrisi hesaplanamadı."})
        else:
            tl, bl = left_pts[0],  left_pts[-1]
            tr, br = right_pts[0], right_pts[-1]
            src = np.array([tl, tr, br, bl], dtype="float32")
            dst = np.array([
                [am["top_left"]["x"]     * maxW, am["top_left"]["y"]     * maxH],
                [am["top_right"]["x"]    * maxW, am["top_right"]["y"]    * maxH],
                [am["bottom_right"]["x"] * maxW, am["bottom_right"]["y"] * maxH],
                [am["bottom_left"]["x"]  * maxW, am["bottom_left"]["y"]  * maxH],
            ], dtype="float32")
            H_mat = cv2.getPerspectiveTransform(src, dst)

        # ── B. Warp ───────────────────────────────────────────────────────────
        warped      = cv2.warpPerspective(img, H_mat, (maxW, maxH),
                                          borderValue=(255, 255, 255))
        warped_gray = cv2.cvtColor(warped, cv2.COLOR_BGR2GRAY)
        omr_thresh  = cv2.adaptiveThreshold(
            warped_gray, 255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 51, 15
        )
        debug_img = warped.copy()

        for a in schema["anchors"]:
            ax, ay = int(a["x"] * maxW), int(a["y"] * maxH)
            cv2.circle(debug_img, (ax, ay), 18, (0, 200, 0), 3)

        # ── C. OCR — sadece isim ──────────────────────────────────────────────
        ocr_lang    = _ocr_lang()
        student_info = {}
        ocr_debug = {
            "easyocr_result": "",
            "tesseract_result": "",
            "selected": "",
            "final": "",
        }
        for field in schema["fields"]:
            fx = int(field["x"] * maxW);  fy = int(field["y"] * maxH)
            fw = int(field["w"] * maxW);  fh = int(field["h"] * maxH)
            inset      = 3
            field_crop_gray = warped_gray[fy + inset:fy + fh - inset,
                                          fx + inset:fx + fw - inset]
            field_crop_bgr  = warped[fy + inset:fy + fh - inset,
                                     fx + inset:fx + fw - inset]

            text = _ocr_field(field_crop_gray, ocr_lang)
            if field["name"] == "student_name":
                debug_path = ""
                try:
                    debug_path = f"/tmp/name_roi_debug_{int(time.time() * 1000)}.png"
                    cv2.imwrite(debug_path, field_crop_bgr)
                    print(f"[DEBUG] Name ROI saved: {debug_path}")
                except Exception as debug_err:
                    print(f"[DEBUG] Name ROI save failed: {debug_err}")

                hybrid_text, hybrid_debug = _ocr_name_hybrid(field_crop_bgr, ocr_lang)
                if hybrid_text and len(hybrid_text) >= 2:
                    text = hybrid_text
                elif not text or text == "Okunamadı":
                    text = "Okunamadı"

                ocr_debug = {
                    "easyocr_result": hybrid_debug.get("easyocr_result", ""),
                    "tesseract_result": hybrid_debug.get("tesseract_result", text),
                    "selected": hybrid_debug.get("selected", "tesseract"),
                    "final": text,
                    "debug_roi_path": debug_path,
                }

            student_info[field["name"]] = text
            cv2.rectangle(debug_img, (fx, fy), (fx + fw, fy + fh), (255, 0, 0), 2)

        # ── D. OMR — öğrenci numarası grid okuma ─────────────────────────────
        sn       = schema["student_number_grid"]
        sn_xs    = sn["x_start"]
        sn_xstp  = sn["x_step"]
        sn_y_cs  = sn["y_circle_start"]
        sn_y_cst = sn["y_circle_step"]
        sn_dc    = sn["digit_count"]
        sn_br    = int(maxW * sn["bubble_radius"])
        sn_ir    = max(int(sn_br * 0.8), 1)

        digits = []
        for col in range(sn_dc):
            bx          = int((sn_xs + col * sn_xstp) * maxW)
            best_d      = None
            best_ratio  = 0.30

            for row in range(10):
                by     = int((sn_y_cs + row * sn_y_cst) * maxH)
                mask   = np.zeros(omr_thresh.shape, dtype="uint8")
                cv2.circle(mask, (bx, by), sn_ir, 255, -1)
                total  = cv2.countNonZero(mask)
                filled = cv2.countNonZero(cv2.bitwise_and(omr_thresh, omr_thresh, mask=mask))
                if total > 0:
                    r = filled / total
                    if r > best_ratio:
                        best_ratio, best_d = r, str(row)

            digits.append(best_d if best_d is not None else "_")

        student_info["student_number"] = "".join(digits)

        # Debug: öğrenci numarası grid görselleştirme
        for col in range(sn_dc):
            bx = int((sn_xs + col * sn_xstp) * maxW)
            for row in range(10):
                by  = int((sn_y_cs + row * sn_y_cst) * maxH)
                sel = (digits[col] == str(row))
                cv2.circle(debug_img, (bx, by), sn_br,
                           (0, 255, 0) if sel else (200, 200, 200),
                           2 if sel else 1)

        # ── E. OMR — soru balonları ───────────────────────────────────────────
        bub_r   = int(maxW * schema["metadata"]["bubble_radius"])
        answers = {}

        for q in schema["questions"]:
            marked = []
            for opt in q["options"]:
                bx = int(opt["x"] * maxW);  by = int(opt["y"] * maxH)
                ir = max(int(bub_r * 0.8), 1)
                mask   = np.zeros(omr_thresh.shape, dtype="uint8")
                cv2.circle(mask, (bx, by), ir, 255, -1)
                total  = cv2.countNonZero(mask)
                filled = cv2.countNonZero(cv2.bitwise_and(omr_thresh, omr_thresh, mask=mask))
                if total > 0:
                    ratio = filled / total
                    if ratio >= 0.48:
                        marked.append(opt["val"])
                    color = (0, 255, 0) if ratio >= 0.48 else (0, 0, 255)
                    cv2.circle(debug_img, (bx, by), bub_r, color, 2)
                    cv2.putText(debug_img, f"{ratio:.2f}",
                                (bx - 15, by - bub_r - 5),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.4, color, 1)
            answers[str(q["q_no"])] = ",".join(marked)

        # cv2.imwrite("debug_omr_output.jpg", debug_img)

        return {
            "status": "success",
            "student_info": student_info,
            "answers": answers,
            "ocr_debug": ocr_debug,
            "metadata": {"processed_width": maxW, "processed_height": maxH},
        }

    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


# Uygulamayı çalıştırmak için:
# uvicorn src.main:app --reload
