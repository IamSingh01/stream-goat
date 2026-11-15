# Google Drive backup: setting up Google sign-in

Stream Goat backs up to the user's own Google Drive, in a **Stream Goat backups** folder (scope
`https://www.googleapis.com/auth/drive.file`): the app sees only the files it made itself, never the rest of the
Drive. (Drive's hidden app folder, `drive.appdata`, can't be used: Google's code sign-in refuses it with
*"Invalid device flow scope"*.) Sign-in uses Google's code sign-in for TVs and other devices: the app shows a code, the user enters it
at google.com/device on a phone or computer. That works the same on phones, Android TV and Windows/Linux.

For this, the project needs its own Google sign-in client. It's free and takes about ten minutes, once.

## 1. Google Cloud project

1. Open <https://console.cloud.google.com/> and sign in with the Google account that will own the project.
2. Top bar → project picker → **New project** → name it `Stream Goat` → **Create**, and select it.
3. **APIs & Services → Library** → search **Google Drive API** → **Enable**.

## 2. Consent screen (what users see when they sign in)

1. **Google Auth Platform** (in older menus: *APIs & Services → OAuth consent screen*) → **Get started**.
2. App name `Stream Goat`, your email as support email → **Next**.
3. Audience: **External** → **Next**. Contact email: yours → **Next** → agree → **Create**.
4. **Data Access → Add or remove scopes** → filter for `drive.file` → tick
   `.../auth/drive.file` ("See, edit, create, and delete only the specific Google Drive files you use with this
   app") → **Update** → **Save**. It's a *non-sensitive* scope, so no review by Google is needed. (If
   `drive.appdata` is ticked from an earlier version of this guide, it can be removed.)
5. **Audience → Publish app → Confirm** (status *In production*). In *Testing* only the test users you list can
   sign in, and their sign-in expires after 7 days.

Don't upload a logo: a logo makes Google ask for a brand review first.

## 3. The client

1. **Clients** (older: *Credentials*) → **Create client** (*Create credentials → OAuth client ID*).
2. Application type: **TVs and Limited Input devices**. Name: `Stream Goat` → **Create**.
3. Copy the **Client ID** and the **Client secret**.

For an app that is installed on people's devices, Google doesn't treat this client secret as a secret: it is built
into the app. Still, keep it in GitHub secrets rather than in the code.

## 3b. The web version's client

The web version (https://sherazi5110.github.io/stream-goat/app/) signs in inside the browser, which needs a second
client in the **same project**: Drive lets a project see only the files that project made, so the web version and the
apps share the backups (the web version can bring the apps' Stremio add-ons over) only within one project.

1. **Clients → Create client**. Application type: **Web application**. Name: `Stream Goat web`.
2. **Authorized JavaScript origins → Add URI**: `https://sherazi5110.github.io` (to try a preview from a
   branch, also `https://raw.githack.com`). No redirect URIs. **Create**.
3. Copy its **Client ID** (a web client needs no secret) into the GitHub secret `GOOGLE_WEB_CLIENT_ID`.

In a browser, Google's access lasts an hour; after that the web version asks to *Connect again* (one tap). Its
own backups in the browser (IndexedDB, every 5 minutes) don't need an account.

## 4. GitHub secrets

Repository → **Settings → Secrets and variables → Actions → New repository secret**, twice:

| Name | Value |
| --- | --- |
| `GOOGLE_CLIENT_ID` | the Client ID (ends in `.apps.googleusercontent.com`) |
| `GOOGLE_CLIENT_SECRET` | the Client secret |
| `GOOGLE_WEB_CLIENT_ID` | the web client's Client ID (step 3b), for the web version |

The next build (test build or release) has Google Drive backup. Builds without the secrets show *"Google sign-in
isn't set up in this build"*.

For a local build, set the same two environment variables before running Gradle. The Windows/Linux app also reads
`STREAMGOAT_GOOGLE_CLIENT_ID` / `STREAMGOAT_GOOGLE_CLIENT_SECRET` at runtime.

## What users see

Settings → *Updates and backup* (Android) or *Storage* (Windows/Linux) → **Google Drive backup → Sign in with
Google**: a code and a QR code for google.com/device. Each device keeps its own backup file
(`stream-goat-backup-<device>.json`), so a phone and a TV on one account don't overwrite each other. After signing
in, the other devices' backups are offered for restoring (pick the phone's on the TV); otherwise this device's backup
is saved there. With *Back up automatically* on (default), it's uploaded whenever something changed (checked every 5
minutes, and on Android when the app is put away). **Restore** lists every device's backup; old ones can be
deleted there. On a fresh install, the first screen has **Restore from Google Drive**.

Users can delete the backups like any other file (the *Stream Goat backups* folder in My Drive), and remove Stream
Goat's access in their Google account → *Security → Third-party apps*.
