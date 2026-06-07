# Android remote debugging

End-to-end recipe to run aMazeGame on a real Android device while developing in WSL/Linux, and to drive + inspect it programmatically from the laptop. Tested on Windows 11 + WSL2 Debian + a REDMAGIC 10 Pro (Android 15).

## TL;DR

1. **Once per workstation:** install `usbipd-win` on Windows and `adb`/`usbutils` in WSL.
2. **Each session:** plug the phone in → `usbipd attach --wsl --busid X` → `adb reverse tcp:5173 tcp:5173` → start dev server → open `http://localhost:5173/` on the phone.
3. **To inspect/automate:** `adb forward tcp:9222 localabstract:chrome_devtools_remote` → Playwright `connectOverCDP("http://localhost:9222")` from a Node script.

The rest of this doc fills in each step plus the gotchas we actually hit.

## 1. One-time install

### Windows side — `usbipd-win`

Easiest path is winget from inside WSL — it correctly elevates on the Windows host:

```bash
'/mnt/c/Users/$USER/AppData/Local/Microsoft/WindowsApps/winget.exe' \
  install --id dorssel.usbipd-win --silent \
  --accept-source-agreements --accept-package-agreements
```

A UAC prompt appears on Windows; accept it. After install you should see:

```bash
'/mnt/c/Program Files/usbipd-win/usbipd.exe' --version
# 5.x.x-…
```

Direct download (if winget is unavailable): https://github.com/dorssel/usbipd-win/releases

### WSL side — adb + USB tooling

```bash
sudo apt-get install -y android-tools-adb usbutils
sudo modprobe vhci-hcd   # so attached devices appear in /dev
```

`android-tools-adb` brings the `android-udev-rules` package along, which lets adb talk to Android devices without root.

## 2. Phone side — enable USB debugging

1. *Settings → About phone → tap "Build number" 7 times* to unlock Developer options.
2. *Settings → System → Developer options → enable **USB debugging**.*
3. When prompted on the phone after plugging in, accept *"Allow USB debugging from this computer?"* and tick **Always allow from this computer**.

If the phone never shows the prompt — see the cable troubleshooting below; we hit that exact case and the cable was the culprit.

## 3. Per-session: forward the device into WSL

```bash
# 1. Find the phone in the Windows USB device list.
'/mnt/c/Program Files/usbipd-win/usbipd.exe' list

# 2. Bind it (one-time, persists across reboots). Needs admin → UAC prompt.
'/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -Command \
  "Start-Process -FilePath 'C:\Program Files\usbipd-win\usbipd.exe' \
     -ArgumentList 'bind','--busid','<BUSID>' -Verb RunAs -Wait"

# 3. Attach to WSL (per-session; non-admin).
'/mnt/c/Program Files/usbipd-win/usbipd.exe' attach --wsl --busid <BUSID>

# 4. Confirm.
lsusb              # device should appear, e.g. "ZTE WCDMA Technologies MSM ..."
adb devices -l     # should list the device as "device" (not "unauthorized")
```

If `adb devices` shows the device as **`unauthorized`**, look at the phone — it's waiting for you to tap "Allow USB debugging".

> **Want it automatic?** Use `usbipd attach --wsl --busid <BUSID> --auto-attach` once in a long-running terminal — usbipd will silently re-attach whenever the device is plugged back in.

## 4. Per-session: point the phone at the dev server

```bash
adb reverse tcp:5173 tcp:5173
npm run dev    # already binds 0.0.0.0
```

Open Chrome on the phone and go to **`http://localhost:5173/`**. That's not the phone's localhost — `adb reverse` tunnels it back to WSL.

You can also launch the URL straight from the laptop:

```bash
adb shell am start -a android.intent.action.VIEW -d "http://localhost:5173/" \
  -n com.android.chrome/com.google.android.apps.chrome.Main
```

## 5. Inspecting / scripting the running game

### chrome://inspect (manual)

1. On the laptop, open `chrome://inspect/#devices` in Chrome.
2. Tick "Discover USB devices".
3. The phone appears; under it every open Chrome tab is listed.
4. Click **inspect** next to the aMazeGame tab → full DevTools mirrored over USB.

### Playwright over CDP (automation / screenshots / console capture)

Forward Chrome's DevTools port from the phone to WSL, then point Playwright at it:

```bash
adb forward tcp:9222 localabstract:chrome_devtools_remote
curl -s http://localhost:9222/json | jq '.[0]'     # sanity: a tab object with `webSocketDebuggerUrl`
```

```js
// scripts/drive-phone.mjs (also in this repo)
import { chromium } from "@playwright/test";
const browser = await chromium.connectOverCDP("http://localhost:9222");
const page = browser
  .contexts()
  .flatMap((c) => c.pages())
  .find((p) => p.url().startsWith("http://localhost:5173"));

page.on("console", (m) => console.log(`[console.${m.type()}] ${m.text()}`));
page.on("pageerror", (e) => console.log(`[pageerror] ${e.message}`));

await page.screenshot({ path: "test-results/phone.png" });
```

> **Heads up:** the Chrome DevTools socket isn't bound until Chrome has at least one tab in a foreground state. If `curl http://localhost:9222/json/version` returns nothing, open *any* tab on the phone first.

The repo already ships two such scripts:

- `scripts/drive-phone.mjs` — fills the name dialog, screenshots, sends a few directional inputs.
- `scripts/inspect-phone.mjs` — wipes IndexedDB, reloads, then captures every console message, pageerror, request failure, and dumps the renderer's runtime state (canvas size, sampled pixels, viewport, DPR). This is the recipe to reach for first when something behaves differently on the phone than on desktop Chrome.

## 6. Troubleshooting we actually hit

### Phone doesn't appear in `usbipd list` at all

This is the case where Windows isn't even seeing voltage on the data lines. The "Allow USB debugging" prompt will never show because Windows can't enumerate the device.

Causes in order of frequency:

1. **The cable is charge-only.** This is by far the #1 cause. Many phone cables only carry the power pins. Try the cable that came in the phone box, or one explicitly labelled "data" / "sync". To diagnose without trial-and-error, poll `usbipd list` in a tight loop while you swap cables/ports:
   ```bash
   baseline=$('/mnt/c/Program Files/usbipd-win/usbipd.exe' list | grep -E '^[0-9]+-[0-9]+' | wc -l)
   for i in $(seq 1 20); do
     current=$('/mnt/c/Program Files/usbipd-win/usbipd.exe' list | grep -E '^[0-9]+-[0-9]+' | wc -l)
     [ "$current" -ne "$baseline" ] && { echo "CHANGE at $i"; break; }
     printf "."; sleep 3
   done
   '/mnt/c/Program Files/usbipd-win/usbipd.exe' list
   ```
   The instant the phone is wired up correctly, a new line appears (e.g. `19d2:1352 REDMAGIC 10 Pro`).
2. **USB port issue.** Try a different port, preferably USB-A 2.0 directly on the laptop, no hub.
3. **Phone's USB port is dirty.** Lint in the connector kills contact. Compressed air or a wooden toothpick (gently) fixes it.
4. **Phone is locked.** Unlock the screen before plugging in — some Android versions don't power data lines while locked.

### Phone appears as "Unknown USB Device (Port Reset Failed)"

Same symptom family — Windows sees voltage but never gets a valid USB descriptor. Same fixes as above (cable then port).

### `adb devices` shows the phone as "unauthorized"

Tap "Allow USB debugging" on the phone. If you missed the prompt: `adb kill-server && adb start-server` to re-trigger the handshake.

### `chrome://inspect` shows no tabs under the phone

Make sure both Chromes are on roughly the same major version family. If you're 3+ majors apart, update one of them.

### Sticky-state gotchas

- **`adb reverse` resets on USB disconnect.** Re-run it after replugging.
- **`usbipd attach` resets on WSL shutdown.** Re-run the attach command. Pair with `--auto-attach` if you replug often.
- **Chrome DevTools port (9222) is the laptop port `adb forward` allocated.** It's per-adb-session — restart adb, re-run the forward.

### The "everything looks blank" bug

We hit a real bug on the phone where the maze appeared not to render at all — paper background visible, no walls, no dot. The console-driven debug script (`scripts/inspect-phone.mjs`) immediately surfaced the root cause: the canvas's CSS box was growing on every call to `fitMetrics`, because the canvas had no CSS width/height and was getting its CSS size from its `width`/`height` attributes (which the renderer was updating to backing-store size). Each call multiplied by `devicePixelRatio` (3.25 on this phone), so within a few frames the canvas was 8100×4050 CSS px — Chrome OOM'd the backing store and the tab crashed.

The pattern to remember: **when something works on desktop Chrome and looks broken on the phone, dump `getBoundingClientRect()` and the canvas backing-store size**. Anything wildly bigger than the viewport means a feedback loop in your sizing logic, not a rendering bug. The fix in this case was a single CSS rule pinning `#maze` to `position:absolute; inset:0; width:100%; height:100%` so the backing store no longer leaks back into CSS layout.

## 7. Wireless fallback

If USB is acting up and you can't fix the cable today, Android's **Wireless debugging** path is one cable-free alternative:

1. Phone: *Developer options → Wireless debugging → Pair device with pairing code*.
2. Note the IP, port, and 6-digit code.
3. In WSL: `adb pair <ip>:<port>` (enter the code), then `adb connect <ip>:<adb-port>`.

This bypasses `usbipd-win` entirely. WSL2 in default NAT mode can reach LAN devices Windows can reach, so it works without networking surgery.
