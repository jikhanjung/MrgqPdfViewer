#!/usr/bin/env python3
"""
간단한 앱 아이콘 생성 스크립트
"""

from PIL import Image, ImageDraw, ImageFont
import os

def create_icon(size, text="MRGQ", bg_color="#007AFF", text_color="white"):
    """간단한 텍스트 기반 아이콘 생성"""
    img = Image.new('RGBA', (size, size), bg_color)
    draw = ImageDraw.Draw(img)
    
    # 폰트 크기 계산
    font_size = int(size * 0.25)
    try:
        font = ImageFont.truetype("arial.ttf", font_size)
    except:
        font = ImageFont.load_default()
    
    # 텍스트 위치 계산
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    
    x = (size - text_width) // 2
    y = (size - text_height) // 2
    
    # 텍스트 그리기
    draw.text((x, y), text, fill=text_color, font=font)
    
    return img

# 아이콘 크기 정의
sizes = {
    'mdpi': 48,
    'hdpi': 72, 
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

# 기본 경로
base_path = "/mnt/d/projects/MrgqPdfViewer/app/src/main/res"

# 각 해상도별 아이콘 생성
for density, size in sizes.items():
    folder = f"{base_path}/mipmap-{density}"
    os.makedirs(folder, exist_ok=True)
    
    # 일반 아이콘
    icon = create_icon(size)
    icon.save(f"{folder}/ic_launcher.png", "PNG")
    
    # 라운드 아이콘 (원형 마스크)
    round_icon = create_icon(size)
    mask = Image.new('L', (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    round_icon.putalpha(mask)
    round_icon.save(f"{folder}/ic_launcher_round.png", "PNG")

print("아이콘 생성 완료!")