한국어: [User_Manual.md](User_Manual.md)

# MRGQ PDF Viewer User Manual

> **As of v0.8** (October 2026). Names are written exactly as they appear in the app's English screens.
> In the app, open it from **Settings → About → User manual** — tablets and phones open a browser; a TV shows a QR code to scan with your phone.

---

## 1. Introduction

**MRGQ PDF Viewer** shows PDF scores on a TV, tablet or phone. Besides turning pages it has a metronome, accompaniment, listening to your playing to turn pages, annotations, voice commands, and an ensemble mode where several devices follow one another.

### 1.1 Devices

| Device | How you use it |
|---|---|
| **TV** (Android TV · Google TV) | Remote control. A large screen on the music stand, two pages side by side in landscape |
| **Tablet** | Touch · stylus · page-turn pedal. One page at a time in portrait |
| **Phone** | Touch. The score fits the screen width and moves line (system) by line |

It is the same app on every device; it adapts to each one. Where the controls differ, this manual shows them in a small table.

### 1.2 Three ways to use it

- **On your own** — put PDFs on the device and read them (→ 3.2 Uploading through the web server).
- **With ScoreMate** — the device downloads the scores and setlists you keep on the ScoreMate website (→ 3.1).
- **Ensemble** — one device (the conductor) chooses the piece, page and metronome; the other devices (performers) follow (→ chapter 9).

---

## 2. Installing · updates · language

### 2.1 Installing

Open the APK file you received to install it. The first time, Android asks whether to allow installing "unknown apps" — allow it.

### 2.2 Updates

The app checks for new versions by itself. While the score list or Settings is on screen it checks **every 10 minutes**, and only tells you when there is a new version.

- **Download and install** downloads and installs it. **Later** won't ask about that version again until the app restarts.
- To check right away: **Settings → About → Check for updates**.
- Turn off **Auto check** to stop automatic checks.
- Turn on **Get betas** to also receive pre-releases (beta). You get new features earlier, but they are less tested.
- No update notice appears during an ensemble or while another dialog is open.

> If "Install permission needed" appears on the first update, press **Open settings**, allow installs for this app, and come back. The install continues.

### 2.3 Language

In **Settings → 🔤 Language (언어)**, choose **System default · 한국어 · English**. The screen changes immediately and the choice is remembered.

- The section name is written in both languages ("Language (언어)"). If the app ever switches to a language you can't read, look for 🔤.
- Voice commands have their own language setting (→ 8.3).

---

## 3. Adding scores

### 3.1 Linking to ScoreMate

ScoreMate is a website for managing scores and setlists. A linked device downloads its scores from there.

**Linking**
1. **Settings → ☁️ ScoreMate → Link this TV** (on a tablet or phone: "Link this tablet" · "Link this phone").
2. A QR code and a link code appear.
3. Scan the QR code with your phone, or go to the address shown.
4. Sign in, check the code and tap **Link**.
5. When the device shows "Linked", you're done. The code is valid only for a few minutes — after that, press **New code**.

**Sync**
- The app syncs when it starts. To sync right away, press **🔄 Sync** in the score list (on a tablet or phone you can also pull the list down) or **Settings → ☁️ ScoreMate → Sync now**.
- **Setlists**: when you choose setlists to send to this device on the website, their names appear as a row above the score list. A linked device shows **only the pieces in its setlists**. Removing a piece from a setlist also removes it from the device — that's expected.
- Deleting a ScoreMate score on the device only hides it. To get it back: **Settings → ☁️ ScoreMate → Restore hidden scores**.
- If ScoreMate has MusicXML or analysis data for a piece, it is downloaded too. It is used by accompaniment (6.4), listen & turn pages (6.5) and the letter command (chapter 8).

**Membership**: the **Settings → ☁️ ScoreMate → Membership** row shows your plan (Lite · Pro · Maestro) and when it ends. Tap it to also see this month's score recognition usage. Coupons, trials and other membership changes are made **on the web** (a TV shows a QR code).
- If hidden scores take more than 500 MB, the ones not opened for the longest are removed first.
- On the **Lite** plan, ensemble scores from a Maestro leader appear in the list **only during ensemble mode** — they show up when an ensemble (chapter 9) starts and are hidden when it ends. Hidden scores come back right away at the next ensemble (even offline) and are removed if **not opened for 30 days** (downloaded again at the next ensemble). Notes you wrote on them are kept.

**Unlinking**: **Settings → ☁️ ScoreMate → Unlink**. Choose whether to **keep or delete** the downloaded scores. Kept scores become ordinary files on this device.

> A linked device **doesn't use the web server.** While linked, the score list only shows ScoreMate scores, so files uploaded through the web server would not appear.

### 3.2 Uploading through the web server (unlinked devices)

Upload PDFs from a browser on a computer or phone on the same Wi-Fi.

1. In **⚙️ Settings → 🌐 Web server**, turn on the **Web server** switch.
2. The address appears on the right of the row (e.g. `192.168.0.10:8080`).
3. In a browser on the same Wi-Fi, open that address with `http://` in front.
4. Choose files and upload. You can upload several at once and see the progress. You can also delete uploaded files from the browser.
5. When you're done, leave the web server section. **The web server only runs while you are in that section** — it stops by itself when you go to another section or leave Settings.

- The **Web server activity log** in the same section shows requests and uploads.
- Change the port under **Port** in the same section (default 8080).
- The browser page follows the browser's language (Korean · English).

---

## 4. Score list

The first screen when the app starts.

### 4.1 Header

- **× ScoreMate ☁️** next to (or below) the app name — the device is linked to ScoreMate.
- **Ensemble button** at the top right (two people) — start or stop an ensemble (→ chapter 9). Its color shows the ensemble status:
  - White — no ensemble
  - **Green** — in an ensemble (conductor mode, or a performer connected to the conductor). For the conductor, the number of connected performers is shown next to the icon
  - **Blinking green** — the performer is looking for or connecting to the conductor
  - **Red** — performer mode, but not connected to the conductor (not found, or gave up reconnecting; → 9.1 Enter manually)
- **⚙️** at the top right — Settings.

### 4.2 Score cards

| | What you see |
|---|---|
| ScoreMate score | First line: title + part. Second line: composer · arranger. On the right: size · pages · date |
| File on the device | File name, pages · size · date |
| ✏️ Needs sync | Annotations not yet uploaded to ScoreMate (they go up on the next sync) |

- **Setlist row**: on a linked device, setlist names appear in a row above the list, and the chosen setlist's pieces are shown **in order, numbered**. If no setlist has been received, a hint tells you to choose one for this device on the website and press 🔄 Sync.
- Unlinked devices have **Sort** (Name · Date) and **Manage files** (delete) buttons.

### 4.3 Opening a score

| TV | Tablet · phone |
|---|---|
| Choose with ↑↓ and press OK | Tap the card once |

---

## 5. Viewing a score

### 5.1 Turning pages

| | TV (remote) | Tablet · phone |
|---|---|---|
| Next · previous page | → · ← | Tap the right · left side, or swipe |
| Page-turn pedal | — | Bluetooth pedal (PageUp/Down · media keys) |
| Back to the list | Back | Back, or ← on the toolbar |

- Turning past the **last page** shows "Next file"; turn once more to open the next piece. Turning back from the **first page** shows "Previous file". When you are viewing a setlist, the setlist order is used.
- On a **phone** the page fits the screen width. Drag up or down; when you let go it settles on the nearest line (system). Dragging past the end of the page goes to the next page.
- Page-turn animation and sound are set in **Settings → 🎵 Animation & sound**.

### 5.2 Toolbar (tablet · phone)

**Tap the middle** of the score once to show the toolbar; tap again to hide it.

- Top: **←** list · title · page
- Bottom: **▶ Play** (chapter 6) · **👁 View** (5.3) · **✏️ Mark** (chapter 7)

The toolbar folds away when playing starts.

### 5.3 View menu

| TV | Tablet · phone |
|---|---|
| **Hold OK** (about 0.8 s) | **👁 View** on the toolbar, or long-press the score |

| Row | What it does | Shown on |
|---|---|---|
| **Part view** | Shows only the chosen parts (staves), cut out and joined — see 5.4 | when not the conductor |
| **Measure boxes** | Draws a numbered box on each measure | all |
| **Show annotations** | Shows or hides annotations | all |
| **✏️ Mark** | Annotate mode (chapter 7) | tablet · phone |
| **Two-page view** | Two pages · One page | TV in landscape |
| **Two-device spread** | Two tablets · phones as the left and right page — see 5.6 below | tablet · phone |
| **Top/bottom crop…** | Crops 0–15% of empty space at the top and bottom to make the score bigger. In two-page view it becomes **Crop · gap…** and also sets the center gap. Previews as you move, saved for this file | all |
| **Rotation** | Device rotation · Landscape · Portrait | phone |

On a tablet or phone this menu slides up from the bottom, and on/off rows are switches. Swipe it down or tap outside to close it.

### 5.4 Part view

Pick your part (staff) from a full score and read it as a part cut out and joined together.

- In **View → Part view**, pick one or more staves and press **Show**. To go back, choose **Full score**.
- Each line shows the original measure and page numbers; with several parts, a part number appears before each staff.
- Measure boxes and the score-linked metronome also work in part view.
- The chosen parts are remembered per file.
- **Supported scores**: **vector scores** exported to PDF from notation software (Sibelius · MuseScore). Scanned scores (images) have no measures or staves the app can find, so part view and measure boxes don't work on them.

### 5.5 Two-page view (TV)

When you first open a portrait score on a landscape screen, the app asks **Two pages at a time · One page at a time**. With **Remember this choice** on, it won't ask again for that file. Change it later with **View → Two-page view**, or reset everything with **Settings → 🔧 Display → Reset all**.

### 5.6 Two-device spread (tablet · phone)

Stand two tablets or phones side by side and read like the TV two-page view — one shows the left pages (1 · 3 · 5 …), the other the right pages (2 · 4 · 6 …).

1. Put both devices on the **same Wi-Fi** and open the same score.
2. On one device: **View → Two-device spread → This device on the left (or right) — wait for a partner**.
3. On the other: **View → Two-device spread → Find a partner**, and pick the waiting device. It takes the other side.

- Turn on either device and both move two pages at a time. With an odd page count, the last right page is blank.
- When the waiting device opens another piece, the other device finds and opens the same score. If it doesn't have it, it offers to **download** it from the waiting device.
- Both devices use the waiting device's top/bottom cropping (the other device's own setting comes back when the pair ends).
- **Playing** (score-linked metronome · 🎤 listen & turn · accompaniment) runs on the device that waited. The other device's ▶ · 🎤 can also start and stop it **from the first measure of this spread** (picking a measure to start from is done on the waiting device). Like the TV two-page view, **only the finished page** changes (1 | 2 → 3 | 2 → 3 | 4), and the other device shows the current measure in yellow too.
- **Ensemble**: when the waiting device joins an ensemble, the pair conducts or follows as one TV two-page device. The other device doesn't take ensemble signals itself.
- If the connection drops, both go back to showing alone, and line up with the current spread again when it reconnects. The pair is remembered even if the app is closed and reopened. If the other device is on the score list, it opens the score the waiting device opened.
- To end it: **View → Two-device spread → Unpair** on either device — both are unpaired.
- Part view is chosen on each device — pick the same part on both so the pages match.
- Phones stay in portrait while paired.

---

## 6. Playing

### 6.1 Play menu

| TV | Tablet · phone |
|---|---|
| **↑** | **▶ Play** on the toolbar |

| Row | What it does |
|---|---|
| 🎤 Listen & turn pages | Listens to you play and turns pages (6.5, tablet · phone) |
| Start — pick a measure on the score | Pick a start measure, then start the metronome (6.3) |
| Metronome settings… | Time signature · tempo · sound (6.2) |
| Accompaniment (MusicXML) | Accompaniment on · volume · accompaniment parts (6.4). Only for pieces with MusicXML |

While the metronome runs you get **Stop**; while paused, **Resume · Pick a measure and restart · Stop**.

### 6.2 Metronome settings

| Row | What it does |
|---|---|
| **Time signature** | 2/4 · 3/4 · 4/4 · 6/8. **Details…** for other signatures (numerator · denominator), and for compound meters like 6/8 the **counting unit** (6 eighths · 2 dotted quarters) |
| **Tempo** | BPM. **Details…** for fine control |
| **Volume** | Click volume. In **Details…**, turning the click sound off leaves only the beat display |
| **Count-in** | **1 bar · 2 bars** — counted before starting from a measure picked on the score |

- Time signature and tempo are saved **per file**; sound and count-in are **shared by all files**.
- If the time signature can be read from the score, it is filled in at first.
- The beat is shown at the top left of the screen.
- **Tempo sections**: when the time signature changes during a piece, each change starts a **section**, listed in the dialog. For each section, choose how to carry the tempo over from the previous one (**Same note length · Same beat length · Set manually**) and the counting unit.

### 6.3 Score-linked metronome (picking a start measure)

If the app can read the time signature and measures of a piece, the metronome follows the score, **highlights the current measure in yellow**, and turns the page 2 beats before the end.

In two-page view on a TV it turns **only the page you have finished**, the way a person would. About 5 seconds after you start the right page, the left page becomes the next page (1 | 2 → 3 | 2 → 3 | 4). If you start from a measure on the right page, the left page shows the next page from the start.

1. **Play → Start — pick a measure on the score**.
2. Pick the start measure.

| TV | Tablet · phone |
|---|---|
| ←→ measure, ↑↓ line, **OK** start, Back cancel | Tap a measure, then tap it again or press **▶ Start from measure N** on the bottom bar |

3. After the count-in (1 or 2 bars) playing starts. The remaining count-in bars are shown in the middle of the screen.

**While playing**
- Opening the Play menu (↑ · ▶ Play) or the View menu **pauses**. Then choose **Resume** (from the measure where you stopped, after the count-in) · **Pick a measure and restart** · **Stop**.
- Closing the Play menu without choosing anything stops.
- On a TV, Back stops only the metronome.

Pieces whose time signature can't be read run as an ordinary metronome without following the score.

### 6.4 Accompaniment (MusicXML)

If there is **MusicXML** for the same piece (downloaded from ScoreMate, or a `.musicxml` with the same name next to the PDF), the score-linked metronome **plays the other parts with a simple sound**.

- In **Play → Accompaniment (MusicXML)**: **Accompaniment on**, and the accompaniment volume (separate from the metronome click).
- **Accompaniment parts**: chosen per piece.
  - **Auto — all but my part** (default): if you picked your part in **Part view**, it is left out; otherwise every part plays.
  - **All — including my part**: every part plays, yours too.
  - **Choose parts**: pick the parts to play one by one. Changing Part view keeps your choice.
- **Follow expression marks**: dynamics (pp–ff) · crescendo · diminuendo · staccato · accents shape the accompaniment. When off, it plays at the chosen volume.
- Only used when the MusicXML's measure count and time signatures match the score; otherwise the app tells you why.
- Accompaniment is off during an ensemble.

### 6.5 🎤 Listen & turn pages (tablet · phone)

The device listens through its microphone, follows where you are in the score, and turns pages.

- **You need**: MusicXML for the piece, and microphone permission.
- In **Part view** it listens for **your part only** — for practicing your part alone. While your part rests, staying quiet is understood as the rest.
- **Play → 🎤 Listen & turn pages — from this page**. While picking a measure, use **🎤 Listen from measure N** on the bottom bar.
- "Start playing" appears. It starts following when it hears **music that matches the beginning of the score**, not just any first sound.
- The current measure and line are shown. When you reach the bottom half, only the top half changes to the next page (**half-page turn**; not on phones).
- If it loses its place, **long-press the measure you are playing** — it re-anchors there. Turning a page by hand also re-anchors.
- The reference tempo is the metronome tempo. To stop: **Play → 🎤 Stop listening**.
- What it hears is saved as a recording (**Settings → 🎤 Recordings**, chapter 10).
- Not available on a performer's device in an ensemble — the conductor's device listens and turns.

---

## 7. Annotations (tablet · phone)

Write on the score with a finger or stylus. Annotations travel with the score file (a companion file with the same name), and on a linked device your annotations sync with ScoreMate.

### 7.1 Writing

- A **stylus** writes on the score at any time.
- To write with a **finger**, turn on annotate mode with **✏️ Mark** (toolbar) or **View → ✏️ Mark**. In annotate mode, use two fingers to zoom and move.

The annotate toolbar:

| Button | What it does |
|---|---|
| ✏️ | Pen |
| 🧽 | Eraser — erases annotations you drag over |
| 🅰 | Text — tap the score and type (e.g. rit. · breath · 4 · bow change). Tap existing text to edit or delete it |
| ✋ | Move — drag an annotation to move it |
| ● | Color |
| Width | Pen width (text size with the text tool) |
| 👤 Mine / 🎼 Conductor | Which annotations to write (only when you can write conductor annotations) |
| ↶ ↷ | Undo · redo |
| **OK** / **Done** | Group the strokes into one annotation / leave annotate mode |

- After drawing several strokes, press **OK** to group them into one annotation. It attaches to the nearest staff.
- Annotations can't be written in part view yet — use the full score.
- To hide annotations, turn off **View → Show annotations**.

### 7.2 Conductor annotations

The owner and leaders of a ScoreMate ensemble can write **conductor annotations**, which everyone in the ensemble sees. In annotate mode, press **👤 Mine ↔ 🎼 Conductor** to switch. Selecting a conductor annotation briefly shows who wrote it.

### 7.3 Sync

- A linked device exchanges your annotations and conductor annotations with ScoreMate when it syncs, merging annotations from several devices.
- The score list checks for annotation changes about every 10 minutes, and syncs when you return from a score.
- Annotations not yet uploaded show **✏️ Needs sync** on the score card.
- When a new edition of a score arrives from ScoreMate, the app warns that "annotations may be out of place".

---

## 8. 🎙 Voice commands (tablet · phone)

Change measures, pages, the metronome and parts by speaking.

### 8.1 Turning it on

1. Turn on **Settings → 🎙 Voice commands → Voice commands**.
2. A **🎙** button appears at the bottom right of the score. Allow microphone access the first time you press it.

It uses the device's speech recognition (usually Google, needs internet). If you see "Not installed", → 11.1.

### 8.2 Speaking

- **Hold 🎙 while you speak**, then let go. It keeps listening for a moment after you let go.
- A short tap on 🎙 just stops playback, without speaking.
- If you say nothing, the app shows "**Say "help" to see what you can say**".
- Say **"help"** or **"how to use"** to see common commands and examples (close with OK).

### 8.3 Voice language

**Settings → 🎙 Voice commands → Voice language** — Same as app language · Korean · English. You can speak English with Korean screens, or the other way round. Staves named after people are recognized in Korean only.

### 8.4 Common commands

| Say | What it does |
|---|---|
| measure 57 | Selects measure 57 (doesn't start yet) |
| from measure 57 | Starts at measure 57 right away |
| start · from the top · again · resume | From the selected measure · the first measure · the last start · where you stopped |
| stop | Stops |
| letter B | The measure with rehearsal mark B (with MusicXML) |
| next page · previous page · page 5 · last page | Turns pages |
| tempo 72 · count-in one bar · metronome | Metronome |
| cello part · violin 2 part · staff 2 · full score | Part view |
| listen · listen from measure 57 · resume listening | 🎤 Listen & turn pages |

Several at once work too: "tempo 80 from measure 57", "cello part letter C", "count-in one bar from the top".
All commands: [Voice command list](Voice_Commands.md).

- Phrases that rule something out ("not measure 57"), or conversation mentioning instruments or names ("the cello is a bit loud here"), are not run.
- On a performer's device in an ensemble, the conductor sets tempo, start and measure, so those commands only show a notice.

---

## 9. Ensemble

Several devices read together. One device is the **conductor**; the others are **performers**. All must be on the same Wi-Fi.

### 9.1 Starting · stopping

1. On the conductor's device: **Ensemble button** at the top right of the score list **→ Start conductor mode**.
2. On each performer's device: **Ensemble button → Start performer mode**. It finds and connects to the conductor by itself. If it can't, choose **Enter manually** and type the conductor's IP address.
3. To finish: **Ensemble button → Stop conductor mode** (performers: **Stop performer mode**). When the conductor stops, the performers stop too.

### 9.2 What stays in step

| | |
|---|---|
| Piece | Performers open the piece the conductor opens. If a performer doesn't have it, the app asks whether to download it from the conductor |
| Page | When the conductor turns, performers turn |
| Metronome | Performers follow the conductor's tempo, time signature, start measure and count-in, on the same beat |
| 🎤 Listen & turn pages | When the conductor's device listens, performers also see the current line and turn pages |

- **Performer metronome sound**: by default a performer's device makes no sound and only follows the beat display, current measure and page turns. Change it in the performer's **Play menu → Metronome sound**, or **Settings → Ensemble → Performer metronome**.
- A performer can step out with **Play menu → Leave on this device only** and come back with **Rejoin the conductor**.
- **The conductor always sees the full score.** Performers can use part view for their own part; pages stay in step with the original.
- If devices have different ensemble versions, the app says which device to update when they connect.
- Pieces received from the conductor (or from the main device in a two-device spread) are kept apart from the score list — the same piece isn't downloaded again next time, and it is removed if **not opened for 30 days** (when space runs short, the ones not opened for the longest go first).
- On the ScoreMate **Lite** plan, ensemble scores from a Maestro leader appear only during an ensemble (3.1 Membership).

---

## 10. Settings at a glance

**⚙️** at the top right of the score list. On a TV or wide tablet, sections are on the left and the selected section's rows on the right (on a TV, → enters and ← leaves). On a phone, tap a section to see its rows.

| Section | Rows | What they do |
|---|---|---|
| 📂 **Files** | Delete all PDFs | Deletes every PDF on this device (can't be undone) |
| 🌐 **Web server** | Web server · Port | 3.2. Not available on a device linked to ScoreMate |
| **Ensemble** | Performer metronome | 9.2 |
| | Experimental: Synced turns · Turn delay | chapter 12 |
| | Advanced: Input block · Message queue | e.g. how long a performer can't turn right after the conductor turns — usually leave as is |
| ☁️ **ScoreMate** | Link: Linked/Not linked · Check link · Membership · Unlink · Link this device · Server | 3.1. The server address normally doesn't need changing |
| | Scores: Sync now · Restore hidden scores | 3.1 |
| 🎵 **Animation & sound** | Turn animation · Turn sound · Volume · Page info | Page-turn effects, page number on screen |
| 🎙 **Voice commands** | Voice commands · Voice language | chapter 8 (tablet · phone) |
| | Always listening (experimental) · Wake word | chapter 12 |
| 🎤 **Recordings** | List of recordings · Delete all | Recordings from 6.5 — play · share · delete (tablet · phone) |
| 🔧 **Display** | Rotation | Phone score screen: Device rotation · Landscape · Portrait |
| | Per-file settings · Reset all | The two-page · one-page choices remembered in 5.5 |
| 🔤 **Language (언어)** | Language | 2.3 |
| 📊 **About** | Version · Check for updates · Auto check · Get betas · User manual | 2.2 · 1 |

---

## 11. Troubleshooting

### 11.1 🎙 Voice commands don't work

- **"Not installed"** or "No speech recognition service": install Google **Speech Recognition & Synthesis**, allow its **microphone** permission in device Settings → Apps → Speech Recognition & Synthesis → Permissions, then turn voice commands on again in this app. Some tablets (especially China-market ROMs) need this app installed separately.
- **Error 12**: the speech recognition app has no microphone permission. Check the permission above.
- Recognition usually needs internet.

### 11.2 The web server page won't open

- Make sure the browser device and this device are on **the same Wi-Fi**.
- Put `http://` in front, and type the address and port exactly as shown on the right of the row.
- **The web server stops when you leave its section.** Stay in that section while uploading.
- Devices linked to ScoreMate can't use the web server (3.1).

### 11.3 An update won't install

- "Install permission needed": use **Open settings** to allow "Install unknown apps" for this app. On TVs where that screen won't open, allow it under device Settings → Apps → Security & restrictions → Unknown sources.
- "The downloaded file is corrupt": try again.

### 11.4 Performers can't find the conductor

- Check that every device is on the same Wi-Fi. Some routers block the signal devices use to find each other — use **Enter manually** with the conductor's IP address.
- If a version notice appears, update the device it names.

### 11.5 Recordings play with no sound

Media volume is 0. Turn it up with the volume key (up).

### 11.6 Measure boxes or part view don't work

Scanned scores (image PDFs) have no measures or staves the app can find. Use PDFs exported from notation software (Sibelius · MuseScore).

### 11.7 The score looks soft on a 4K screen

Some TV devices draw apps at 1080p and stretch them to 4K. This is a device limit the app can't change.

---

## 12. Experimental features

Still being tested; off by default.

- **Synced turns** (Settings → Ensemble): when the conductor turns, every device turns at the same moment after the **Turn delay**. Turns always lag by that delay, which makes it hard to react to surprises.
- **Always listening · Wake word** (Settings → 🎙 Voice commands): runs only what you say after the wake word ("Mate"), without pressing a button. Currently turned off.
