# Install RoarStream

Everything is on the [Releases page](https://github.com/IamSingh01/stream-goat/releases). Pick the file for
your device:

| Your device | File | Section |
|---|---|---|
| Windows 10 or 11 | `roarstream-windows-<version>.msi` (or the portable `.zip`) | [Windows](#windows-10-and-11) |
| Ubuntu, Debian, Mint, Pop!_OS, Zorin, elementary, ChromeOS (Linux) | `roarstream-linux_<version>_amd64.deb` | [.deb](#ubuntu-debian-linux-mint-popos-zorin-elementary-os-chromeos-linux) |
| Any Linux distro with Flatpak (Fedora, Arch, openSUSE, Steam Deck…) | `roarstream-linux.flatpak` | [Flatpak](#flatpak-any-distro) |
| Other x86-64 Linux without Flatpak | the `.deb` (unpacked by hand) | [Other Linux](#fedora-arch-manjaro-opensuse-and-other-x86-64-linux-without-flatpak) |
| Linux, if you already have Java 21 | `roarstream-linux-<tag>.tar` | [.tar](#the-tar-file-java-21-already-installed) |
| Android phone or tablet | `roarstream-android-arm64-v8a.apk` | [Android](#android-phones-tablets-and-android-tv) |
| Android TV, Google TV, Fire TV, TV boxes | `roarstream-android-armeabi-v7a.apk` (or arm64) | [Android TV](#android-tv-google-tv-fire-tv) |
| Not sure which Android file | `roarstream-android-universal.apk` (bigger, runs on all) | [Android](#android-phones-tablets-and-android-tv) |

The Linux `.deb` and `.flatpak` include their own Java, so you don't need to install Java.

Requirements for the Linux app:
- a 64-bit PC (x86-64 / amd64);
- **VLC** (the built-in player uses VLC's engine);
- a desktop session (GNOME, KDE, Xfce, Cinnamon, …).

---

## Windows 10 and 11

The Windows app is the same app as on Linux (same look, profiles, extensions, Stremio add-ons, Live TV, downloads),
with its own Java and **VLC included**: nothing else to install. 64-bit Windows 10 or 11.

**Installer (recommended)**
1. From the [Releases page](https://github.com/sherazi5110/stream-goat/releases/latest), download
   `roarstream-windows-<version>.msi` and open it.
2. If Windows shows **"Windows protected your PC"** (the installer isn't code-signed yet), click **More info →
   Run anyway**.
3. It installs for your user (no admin password) and adds **RoarStream** to the Start menu and the desktop.

**Portable:** download `roarstream-windows-<version>-portable.zip`, unzip it anywhere (e.g. a USB stick) and run
`RoarStream\RoarStream.exe`.

- **Update:** install the new `.msi`; your data is kept.
- **Remove:** Settings → Apps → Installed apps → RoarStream → Uninstall. Your data stays in `%APPDATA%\RoarStream`
  (delete that folder too to remove everything).
- **Data:** `%APPDATA%\RoarStream` (extensions, profiles, library, settings). Downloads go to your Downloads folder,
  in `RoarStream`.
- **Command line:** `roarstream-cli.exe` next to `RoarStream.exe` runs the same commands as on Linux
  (`roarstream-cli doctor`, `repo add`, `install`, `backup`, `restore`).
- `cloudstreamrepo://` repository links on websites open in RoarStream after you've started it once.

---

## macOS (Apple Silicon and Intel)

For Apple Silicon Macs (M1 or newer), download the `arm64` image. For Intel Macs, download the `x86_64` image. macOS 12 or newer.

1. Download `roarstream-macos-<arm64-or-x86_64>-<version>.dmg` from the
   [latest release](https://github.com/sherazi5110/stream-goat/releases/latest).
2. Open it and drag **RoarStream** onto **Applications**.
3. Open RoarStream from Applications or Launchpad. The first time, macOS says *"Apple could not verify RoarStream is
   free of malware"*, because the app isn't registered with Apple (that costs $99 a year). Click **Done**.
4. Open **System Settings → Privacy & Security**, scroll down to *"RoarStream was blocked…"* and click
   **Open Anyway**, then confirm with your password or Touch ID. From then on it opens normally.

Java and VLC are inside the app; nothing else to install. Your data (settings, extensions, library) is in
`~/Library/Application Support/RoarStream`, and downloads go to `~/Downloads/RoarStream`. To update, drag the new
version into Applications and choose **Replace**; your data stays.

Shortcuts: **⌘F** or **/** search, **Esc** or **⌘[** back, **⌃⌘F** or **F11** full screen, **⌘Q** quit.
The `x86_64` build is for Intel Macs; the `arm64` build is for Apple Silicon.

## Ubuntu, Debian, Linux Mint, Pop!_OS, Zorin, elementary OS, ChromeOS (Linux)

### Install

**Option A: in the terminal (recommended)**
```bash
wget -O /tmp/cloudstream-linux.deb https://github.com/sherazi5110/stream-goat/releases/download/V11.0.0/roarstream-linux_11.0.0_amd64.deb
sudo apt install /tmp/cloudstream-linux.deb
```

**Option B: with a file you downloaded in the browser**
```bash
cd ~/Downloads
sudo apt install ./roarstream-linux_11.0.0_amd64.deb
```
Keep the `./`, which tells apt it's a file, not a package name.
apt installs VLC's libraries automatically.

On ChromeOS, first move the downloaded file from **Downloads** to **Linux files** in the
Files app, then use `cd ~` instead of `cd ~/Downloads`.

Then open **RoarStream** from the app menu, or run `cloudstream-linux`.

> Use `apt`, not `dpkg -i` or double-clicking the file in some file managers:
> `apt` also installs the libraries the app needs.
> A message about `_apt` and "permission denied" when installing from `/tmp` is harmless.

### Update
Download the new `.deb` and install it the same way. Your extensions, history and
settings are kept.

### Remove
```bash
sudo apt remove cloudstream-linux
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```
Remove libraries nothing else uses any more (optional; check the list before typing `Y`):
```bash
sudo apt autoremove
```

---

## Flatpak (any distro)

Flatpak runs the same way on every distro and includes its own copy of VLC, so you don't
need to install VLC. Every release has it as `roarstream-linux.flatpak`.

```bash
# Once per computer, if Flathub isn't set up yet (it provides the runtime the app needs):
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo

wget -O /tmp/roarstream-linux.flatpak https://github.com/sherazi5110/stream-goat/releases/latest/download/roarstream-linux.flatpak
flatpak install --user /tmp/roarstream-linux.flatpak
flatpak run io.github.sherazi5110.CloudStreamLinux
```
It also appears in your app menu as **RoarStream**.

- **Update:** download the new `.flatpak` and run the same `flatpak install` line.
- **Remove:** `flatpak uninstall --user io.github.sherazi5110.CloudStreamLinux`
  (add `--delete-data` to also delete extensions, history and settings).

Inside the Flatpak, the app can only open files from your **Downloads** folder (for
subtitle files and side-loaded extensions), and **downloaded videos** are saved in `~/Downloads/RoarStream`.

From **7.0.0** the Flatpak uses the Freedesktop **25.08** runtime (24.08 is end of life). Installing or updating
downloads it from Flathub once, so the Flathub line above must have been run. Afterwards you can remove the old one
with `flatpak uninstall --user --unused`.

---

## Fedora, Arch, Manjaro, openSUSE and other x86-64 Linux (without Flatpak)

These systems don't install `.deb` packages, but the app inside the `.deb` runs on them.
These steps unpack it into your home folder; no root access is needed except to install VLC.

### 1. Install VLC and the desktop libraries
```bash
# Fedora (if vlc isn't found, enable RPM Fusion first: https://rpmfusion.org/Configuration)
sudo dnf install vlc gtk3 libXtst mesa-libGL binutils

# Arch / Manjaro / EndeavourOS
sudo pacman -S --needed vlc gtk3 libxtst mesa binutils

# openSUSE (the Packman repository gives VLC all video codecs)
sudo zypper install vlc gtk3 libXtst6 Mesa-libGL1 binutils
```

### 2. Unpack the app
```bash
cd /tmp
wget -O cloudstream-linux.deb https://github.com/sherazi5110/stream-goat/releases/download/V11.0.0/roarstream-linux_11.0.0_amd64.deb
mkdir -p cs-unpack && cd cs-unpack
ar x ../cloudstream-linux.deb
tar -xJf data.tar.xz
mkdir -p ~/.local/opt
rm -rf ~/.local/opt/cloudstream-linux
mv opt/cloudstream-linux ~/.local/opt/
cd /tmp && rm -rf cs-unpack cloudstream-linux.deb
```

### 3. Add the `cloudstream-linux` command and the app-menu entry
```bash
mkdir -p ~/.local/bin ~/.local/share/applications ~/.local/share/icons
ln -sf ~/.local/opt/cloudstream-linux/bin/cloudstream-linux ~/.local/bin/cloudstream-linux
cp ~/.local/opt/cloudstream-linux/lib/cloudstream-linux.png ~/.local/share/icons/cloudstream-linux.png
cat > ~/.local/share/applications/cloudstream-linux.desktop <<EOF
[Desktop Entry]
Type=Application
Name=RoarStream
GenericName=Media Center
Comment=Run CloudStream extensions on Linux
Exec=$HOME/.local/opt/cloudstream-linux/bin/cloudstream-linux %u
Icon=$HOME/.local/share/icons/cloudstream-linux.png
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=x-scheme-handler/cloudstreamrepo;
StartupWMClass=com.lagradost.cloudstream3.linux.ui.DesktopApp
EOF
update-desktop-database ~/.local/share/applications 2>/dev/null || true
```
Open **RoarStream** from the app menu. If the menu entry or the command doesn't show up
straight away, log out and back in once.

### Update
Repeat step 2 with the new `.deb`. Steps 1 and 3 only need to be done once.

### Remove
```bash
rm -rf ~/.local/opt/cloudstream-linux ~/.local/bin/cloudstream-linux \
       ~/.local/share/applications/cloudstream-linux.desktop ~/.local/share/icons/cloudstream-linux.png
```
Also delete your extensions, history and settings (optional):
```bash
rm -rf ~/.local/share/cloudstream-linux
```

---

## The `.tar` file (Java 21 already installed)

`roarstream-linux-<tag>.tar` is the plain Java build, without its own Java. Use it if you already have Java 21 and
VLC, or want to run the app without installing anything:
```bash
sudo apt install openjdk-21-jre libvlc5 vlc-plugin-base   # or your distro's Java 21 and VLC packages
tar -xf cloudstream-linux-*.tar
./cloudstream-linux/bin/cloudstream-linux
```
To update, unpack the new `.tar` over the old folder. To remove, delete the folder (your data is in
`~/.local/share/cloudstream-linux`).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "The built-in player needs VLC's libraries" | Install VLC (see above), then restart the app. |
| `E: Unsupported file … given on commandline` | You typed the file name without `./` in front; use `sudo apt install ./roarstream-linux_11.0.0_amd64.deb`. |
| `ar: command not found` | Install `binutils` (included in the step 1 commands). |
| A site doesn't load | Settings → Network & DNS → pick a DNS-over-HTTPS provider. |
| An extension misbehaves | Settings → Developer & logs → **Test** next to it, then copy the log into a bug report. |

ARM computers (Raspberry Pi, ARM Chromebooks, Apple Silicon Linux) aren't supported by this
release.

---

## Android: phones, tablets and Android TV

The Android app has the same look and features as the Linux app (Home, Search, Live TV, profiles, Stremio add-ons,
TMDB and the VLC player), with a touch layout for phones and a remote layout for TV. It's **in testing**: expect
rough edges, and please report what you find.

It installs **next to** the official CloudStream app (it has its own app ID), so you can keep both. Extensions are the
same ones: add your repositories under Extensions.

### Which file?

| File | For |
|---|---|
| `roarstream-android-arm64-v8a.apk` | most phones and tablets from the last ~8 years, newer TVs (Shield, Chromecast with Google TV, Fire TV 4K Max) |
| `roarstream-android-armeabi-v7a.apk` | older or cheaper TV boxes and sticks (most Fire TV Sticks, many Android TV boxes) |
| `roarstream-android-universal.apk` | any device; about twice the size. Use it when you're not sure |
| `roarstream-android-x86_64.apk`, `-x86.apk` | emulators and a few Chromebooks |

If Android says "App not installed", the file doesn't match your device: try the universal one.

### Google Play Protect warning or block

On some devices Play Protect says RoarStream "can install harmful apps",
blocks the install or its updates, or later disables or removes the app by itself (it "disappears" from the TV). That's
because RoarStream isn't on the Play Store, installs its own updates (Android's "install unknown apps" permission) and
downloads extensions, like CloudStream; Play Protect flags apps that work this way. The app is open source: its code is
here for anyone to read. To install it anyway:
1. At the warning, press **More details → Install anyway**.
2. Still blocked, or the app keeps disappearing? Turn off Play Protect's scanning, then install again:
   **phone/tablet:** Play Store → profile picture → Play Protect → ⚙ → turn off *Scan apps with Play Protect*;
   **Google TV / Android TV:** Play Store → profile → Settings → Google Play Protect → turn off scanning.
3. App gone from the TV? Settings → Apps → See all apps: if it's there but disabled, press **Enable**; otherwise install it again.

Turning Play Protect off is your choice (it also stops checking other apps); turned back on, it may disable or remove
RoarStream again. Only download RoarStream from this GitHub page or [the website](https://sherazi5110.github.io/stream-goat/).

Needs Android 6.0 or newer.

### Phone or tablet

1. Open the [Releases page](https://github.com/sherazi5110/stream-goat/releases) on the phone and download the `.apk`.
2. Open it from the notification or the **Downloads** app.
3. When Android asks, allow **Install unknown apps** for your browser or file manager, go back and tap **Install**.
4. Open **RoarStream**, go to **More → Extensions**, add a repository and install extensions.

### Android TV, Google TV, Fire TV

**With the Downloader app (easiest):**
1. Install **Downloader** (by AFTVnews) from the TV's app store.
2. Allow it to install apps:
   - **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On.
     (If Developer options is missing: Settings → My Fire TV → About → click the device name 7 times.)
   - **Android TV / Google TV:** Settings → Apps → Security & restrictions → Unknown sources → Downloader → On.
3. Open Downloader, type the code **`4564305`** (or the address **`sherazi5110.github.io/stream-goat/tv`**) in the box and press **Go**. It downloads the
   newest version for TVs (`armeabi-v7a`).
   - If the TV says *App not installed*, uninstall the old RoarStream first (back up first: Settings → Updates and
     backup), or type the code **`2193283`** instead (the universal file, for every device; or the address
     `sherazi5110.github.io/stream-goat/apk`). Both codes always get the newest version.
   - The full link works too: `https://github.com/sherazi5110/stream-goat/releases/latest/download/roarstream-android-armeabi-v7a.apk`.
4. Choose **Install**, then **Open**. Downloader offers to delete the downloaded file: you can, it isn't needed any more.

**Other ways:** copy the `.apk` to a USB stick and open it with a file manager on the TV, or send it with the
*Send files to TV* app.

The app shows the TV layout automatically on TVs. If a box picks the wrong one, change it under
**Settings → Appearance → Layout**.

### From a computer (adb)

With USB or network debugging turned on in the device's developer options:
```bash
adb install -r roarstream-android-arm64-v8a.apk
```

### Downloads on Android

Downloaded movies and episodes are saved in the app's own folder
(`Android/data/io.github.sherazi5110.cloudstream/files/Movies/RoarStream`); no storage permission is needed.
On Android 13+ the app asks once to show notifications, for the download progress (downloads work without it).
**Uninstalling the app deletes the downloads**; updating keeps them.

### Update

Download the new `.apk` and install it the same way; your extensions, profiles, history and downloads are kept.
If Android refuses with "App not installed" or "package conflicts", the new file was signed with a different key:
uninstall the old app first (this deletes its data), then install the new one.

### Remove

Settings → Apps → RoarStream → Uninstall (or long-press the icon → Uninstall).
