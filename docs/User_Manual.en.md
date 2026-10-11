한국어: [User_Manual.md](User_Manual.md)

# MrgqPdfViewer User Manual

> This is the English edition of the Korean user manual. The app's English screens are still being translated, so
> the first time a menu or button is named, its Korean screen name follows in parentheses — e.g. "Play menu (연주)" —
> so you can find it on a Korean screen too.

## 🎵 1. About MrgqPdfViewer

**MrgqPdfViewer** is a PDF score viewer built for Android TV. Beyond simply showing PDFs, its core feature is a powerful **ensemble mode (합주 모드)** that lets several people keep their scores in sync in real time.

This manual is here to help you get the most out of every feature of the app.

---

## 🖥️ 2. Getting started: adding PDF files

For security and convenience, the app does not browse folders on the device. Instead, you add files through its built-in web server.

**Step 1: Turn on the web server**
1.  From the app's main screen, go to **Settings (설정)**.
2.  Choose the **Web server (웹서버)** menu.
3.  Choose **Turn on web server (웹서버 켜기)** to start the server.
4.  Note the **IP address and port** shown on screen (e.g. `http://192.168.1.10:8080`).

**Step 2: Upload files**
1.  On a computer or smartphone **connected to the same Wi-Fi network** as the TV, open a web browser.
2.  Type the IP address you noted above (e.g. `http://192.168.1.10:8080`) into the address bar.
3.  When the page opens, press the "Choose file" button, pick the PDF scores to upload, and upload them.
4.  As soon as the upload finishes, the files appear on the app's main screen.

---

## 📄 3. Main screen and basic controls

### 3.1. File list
*   The main screen lists every uploaded PDF file.
*   Each entry shows the file name, total page count, file size, and other details.
*   Use the remote's **arrow keys (D-pad)** to move through the files.

### 3.2. Opening a file
*   Select a file and press **OK** on the remote to open that score in the viewer.

---

## 🎼 4. The PDF viewer: reading a score

### 4.1. Turning pages
*   **Right arrow key**: go to the next page.
*   **Left arrow key**: go to the previous page.

### 4.2. Moving between files
*   On the **last page**, pressing the right arrow key shows a prompt asking whether to go to the next file.
*   On the **first page**, pressing the left arrow key shows a prompt asking whether to go to the previous file.

### 4.3. View menu · Play menu
*   **Press and hold OK on the remote (about 0.8 seconds)** (on tablets and phones, press and hold the screen) to open the **View menu (보기)**.
    *   **Part view (파트 보기):** shows only the parts you choose, cut out of the score.
    *   **Measure boxes (마디 박스) · Show annotations (메모 보이기):** turn these overlays on the score on or off. On tablets and phones, **✏️ Annotate (메모 쓰기)** is here too.
    *   **Two-page view (두 쪽 보기):** (TV, landscape) show two pages side by side on one screen, or a single page larger.
    *   **Top/bottom crop (위/아래 자르기):** trim unneeded margins at the top and bottom of the score so it displays larger. In two-page view, the **center gap (가운데 여백)** — the space between the two pages — is adjusted here as well.
    *   **Rotation (회전):** (phone) Device rotation (기기 회전) · Landscape (가로) · Portrait (세로).
*   Press the **up arrow key** (on tablets and phones, tap the middle of the screen and choose **▶ Play (▶ 연주)** on the bottom bar) to open the **Play menu (연주)** — Start · Stop, Metronome settings (메트로놈 설정), Accompaniment (반주), and (tablets and phones) 🎤 Listen & turn pages (연주 듣고 넘기기).
*   For the full list of menu items, see [`Viewer_Menus.md`](Viewer_Menus.md) (Korean).

> **Voice commands — Korean only for now.** On tablets and phones, voice commands work **only in Korean**; English voice
> commands are in preparation. Say the Korean phrase as written, for example:
> *   "57마디부터" — start from measure 57
> *   "템포 72" — set the tempo to 72
> *   "멈춰" — stop
>
> The full list is in [`Voice_Commands.md`](Voice_Commands.md) (Korean).

### 4.4. Page info
*   The current file name and page number are shown faintly at the bottom of the screen.
*   You can hide this completely with the **Settings > System > Show page info (설정 > 시스템 > 페이지 정보 표시)** option.

---

## 🎻 5. Ensemble mode

Ensemble mode consists of one **conductor (지휘자)** and several **performers (연주자)**. When the conductor opens a file or turns a page, every performer's screen follows in real time.

### 5.1. Becoming the conductor
1.  Go to **Settings > Ensemble mode (설정 > 합주 모드)**.
2.  Choose **Enable conductor mode (지휘자 모드 활성화)**.
3.  Your device is now the conductor. Go back to the main screen and open a score; other performers can now connect.

### 5.2. Becoming a performer
1.  Go to **Settings > Ensemble mode (설정 > 합주 모드)**.
2.  Choose **Enable performer mode (연주자 모드 활성화)**.
3.  Choose **Find conductor (지휘자 찾기)** to search automatically for conductor devices on the same network.
4.  Select the conductor that was found to connect.
5.  Once connected, your screen follows along automatically whenever the conductor opens a file or turns a page.

### 5.3. What is synced
*   **Opening/closing files:** when the conductor opens a file or goes back to the list, every performer follows.
*   **Page turns:** when the conductor turns a page, every performer's page turns with it.

---

## ⚙️ 6. Settings in detail

### 6.1. File management
*   See how many PDF files are stored and manage them.

### 6.2. Web server
*   Turn the built-in web server for uploading PDF files on or off.

### 6.3. Ensemble mode
*   Enable the conductor or performer role, or turn ensemble mode off completely.

### 6.4. Animation & sound
*   **Page turn animation:** turn the smooth slide effect for page turns on or off.
*   **Page turn sound:** turn the sound effect for page turns on or off.
*   **Sound volume:** adjust the volume of the sound effect with a slider.
*   **Animation speed:** set the speed of the animation to "Instant", "Fast", "Normal", "Slow", and so on. (If animations stutter because the TV is underpowered, changing the speed can help.)

### 6.5. System
*   **Show page info:** turn the file/page info at the bottom of the viewer on or off.

---

## ❓ 7. Troubleshooting (FAQ)

*   **Q: In ensemble mode, the connection becomes unstable when several people connect.**
    *   A: This has been fixed. Each device is now identified by its IP address, so the connection stays stable even when several devices of the same model connect at once.

*   **Q: The conductor opens a second file, but my screen doesn't change.**
    *   A: This bug has been fixed. Callbacks are now re-registered following the `MainActivity` lifecycle, so syncing works reliably no matter how many files the conductor opens.

*   **Q: With animation turned on, page turns don't sync.**
    *   A: This has also been fixed. The sync signal that was missing from the animation path has been added, and state updates and broadcasts now happen at consistent points, so syncing always works regardless of the animation setting.

---
We hope this manual helps you get the most out of MrgqPdfViewer.
