#!/usr/bin/env python3
"""
안드로이드 앱 아이콘 생성 스크립트
주어진 이미지 파일을 사용하여 다양한 해상도의 아이콘과 적응형 아이콘을 생성합니다.
"""

from PIL import Image, ImageDraw
import os
import argparse
import sys

def resize_and_save(source_image_path, output_path, size):
    """레거시 아이콘용으로 이미지를 리사이즈하고 저장"""
    try:
        with Image.open(source_image_path) as img:
            img_copy = img.copy()
            img_copy = img_copy.resize((size, size), Image.Resampling.LANCZOS)
            
            # 일반 아이콘 저장
            img_copy.save(os.path.join(output_path, "ic_launcher.png"), "PNG")

            # 라운드 아이콘을 위한 마스크 생성
            mask = Image.new('L', (size, size), 0)
            draw = ImageDraw.Draw(mask)
            draw.ellipse((0, 0, size, size), fill=255)
            
            # 라운드 아이콘 저장
            img_copy.putalpha(mask)
            img_copy.save(os.path.join(output_path, "ic_launcher_round.png"), "PNG")

    except Exception as e:
        print(f"Error processing {source_image_path} for size {size}: {e}")

def create_adaptive_icon(source_path, base_res_path):
    """안전 영역을 준수하는 적응형 아이콘 생성"""
    try:
        # 적응형 아이콘 전체 크기 및 안전 영역 크기 (단위: dp, 1dp=1px로 가정)
        full_size = 108
        safe_zone_size = 72 # 콘텐츠가 잘리지 않고 표시될 안전 영역

        with Image.open(source_path).convert("RGBA") as source_img:
            # 원본 이미지를 안전 영역에 맞게 리사이즈 (비율 유지)
            source_img.thumbnail((safe_zone_size, safe_zone_size), Image.Resampling.LANCZOS)

            # 새로운 투명한 배경의 캔버스 생성
            foreground_canvas = Image.new('RGBA', (full_size, full_size), (0, 0, 0, 0))

            # 캔버스 중앙에 리사이즈된 이미지 붙여넣기
            paste_x = (full_size - source_img.width) // 2
            paste_y = (full_size - source_img.height) // 2
            foreground_canvas.paste(source_img, (paste_x, paste_y), source_img)

            # 포그라운드 이미지 저장
            drawable_folder = os.path.join(base_res_path, "drawable")
            os.makedirs(drawable_folder, exist_ok=True)
            foreground_path = os.path.join(drawable_folder, "ic_launcher_foreground.png")
            foreground_canvas.save(foreground_path, "PNG")
            print(f"적응형 아이콘 포그라운드 생성 완료 (Safe Zone 적용): {foreground_path}")

        # 배경색상 추출 및 설정 (원본 이미지의 중앙 색상 사용)
        with Image.open(source_path) as bg_img:
            # 검은색 테두리를 피해 원 안의 색상을 추출
            pixel_x = bg_img.width // 4
            pixel_y = bg_img.height // 4
            bg_color = bg_img.getpixel((pixel_x, pixel_y))
            bg_hex_color = '#%02x%02x%02x' % bg_color[:3]

        # colors.xml 파일 업데이트
        values_folder = os.path.join(base_res_path, "values")
        os.makedirs(values_folder, exist_ok=True)
        colors_xml_path = os.path.join(values_folder, "colors.xml")
        
        if os.path.exists(colors_xml_path):
            with open(colors_xml_path, "r", encoding="utf-8") as f:
                lines = f.readlines()
            
            new_lines = []
            found = False
            for line in lines:
                if 'name="ic_launcher_background"' in line:
                    new_lines.append(f'    <color name="ic_launcher_background">{bg_hex_color}</color>\n')
                    found = True
                else:
                    new_lines.append(line)
            if not found:
                for i, line in enumerate(new_lines):
                    if "</resources>" in line:
                        new_lines.insert(i, f'    <color name="ic_launcher_background">{bg_hex_color}</color>\n')
                        break
            
            with open(colors_xml_path, "w", encoding="utf-8") as f:
                f.writelines(new_lines)
        else:
            with open(colors_xml_path, "w", encoding="utf-8") as f:
                f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{bg_hex_color}</color>\n</resources>')
        print(f"배경색상 업데이트 완료: {bg_hex_color}")

    except Exception as e:
        print(f"적응형 아이콘 생성 중 오류 발생: {e}")

def main():
    parser = argparse.ArgumentParser(description="Android 앱 아이콘 생성기")
    parser.add_argument("--source", default="/mnt/d/projects/MrgqPdfViewer/MRGQPdf.png", help="아이콘으로 사용할 원본 이미지 파일 경로")
    args = parser.parse_args()

    if not os.path.exists(args.source):
        print(f"오류: 원본 이미지 파일을 찾을 수 없습니다 - {args.source}")
        return

    base_res_path = "/mnt/d/projects/MrgqPdfViewer/app/src/main/res"

    # 1. 레거시 아이콘 생성
    sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for density, size in sizes.items():
        folder = os.path.join(base_res_path, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        resize_and_save(args.source, folder, size)
    print("레거시 아이콘 생성 완료!")

    # 2. 적응형 아이콘 생성
    create_adaptive_icon(args.source, base_res_path)

if __name__ == "__main__":
    main()
