# Android remote debugging

Workflow for testing aMazeGame on a real Android device while developing in WSL/Linux. The dev server runs on your laptop; Chrome on the phone connects to it, and you drive DevTools on the laptop.

## One-time setup

1. **Install `adb`** on the dev machine.
   ```bash
   sudo apt install android-tools-adb
   ```
2. **Enable Developer options** on the phone: *Settings → About phone → tap "Build number" 7 times*.
3. **Enable USB debugging**: *Settings → System → Developer options → USB debugging*.
4. Plug the phone in over USB. On the phone, accept the "Allow USB debugging?" prompt for this computer.
5. Verify the device is visible:
   ```bash
   adb devices
   # List of devices attached
   # XXXXXXXX    device
   ```

> **WSL note:** WSL2 doesn't see USB devices natively. Install `usbipd-win` on Windows and forward the Android device into WSL:
> ```powershell
> # in Windows PowerShell (admin)
> winget install usbipd
> usbipd list
> usbipd bind --busid <busid>
> usbipd attach --wsl --busid <busid>
> ```

## Running the game on the phone

Two options:

### A) Over Wi-Fi (simpler if phone + laptop share a network)

```bash
npm run dev
# Vite prints something like:
#   ➜  Local:   http://localhost:5173/
#   ➜  Network: http://192.168.1.42:5173/
```

Open the *Network* URL in Chrome on the phone.

### B) Over `adb reverse` (works on any USB-attached device)

This makes the phone's `localhost:5173` forward to the laptop's `5173`. Great when the phone is on a different network.

```bash
adb reverse tcp:5173 tcp:5173
npm run dev
```

Then open `http://localhost:5173/` in Chrome on the phone.

## Inspecting from the laptop

1. On the laptop, open `chrome://inspect/#devices` in Chrome.
2. Make sure **"Discover USB devices"** is checked.
3. Your phone shows up. Under the phone, every open Chrome tab is listed.
4. Click **inspect** next to the aMazeGame tab — a full DevTools window opens, mirroring the phone.

You now have:
- Console + Sources + Network panels for the live page.
- Element picker on the phone screen.
- Performance / Memory profiling against the real device.

## Common gotchas

- **`adb devices` shows `unauthorized`**: re-tap "Allow USB debugging" on the phone, possibly toggling USB mode (File transfer vs. Charging only).
- **No tabs appear under the phone in `chrome://inspect`**: make sure both Chromes are the same major version family (or close to it). Update one if there's a big gap.
- **`adb reverse` is reset after replugging**: re-run it. `adb` connections are not sticky across USB reconnects.
- **WASM (`sql.js`) fails to load over `http://`**: sql.js wants `SharedArrayBuffer` only for the multithreaded build; the bundled single-threaded build works fine over plain HTTP.

## Optional: Puppeteer hook

If you later want automated screenshots from the phone, point Puppeteer at the remote-debugging endpoint that `chrome://inspect` advertises (`adb forward tcp:9222 localabstract:chrome_devtools_remote`, then connect Puppeteer to `http://localhost:9222`). Not yet wired up — flagged for a later round.
