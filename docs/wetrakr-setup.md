# WeTrakr sync: setting up the app key

Stream Goat syncs with [WeTrakr](https://wetrakr.com), a free movie, TV and anime tracker. People connect their own
WeTrakr account in the app (Settings → Metadata & integrations → WeTrakr sync) with a short code, like the Google
sign-in. For that, Stream Goat needs its own **app key** (`client_id`) from WeTrakr, given to the build as a GitHub
secret. Until the secret is set, the apps say "Not available in this build yet".

## 1. Register Stream Goat on WeTrakr (once)

1. Sign in (or sign up) at [wetrakr.com](https://wetrakr.com).
2. Open **Settings → API** and create a new app:
   - **Name:** Stream Goat
   - **Description:** Free movie, TV and anime player for Android, Android TV, Windows, macOS and Linux.
   - **Website:** `https://sherazi5110.github.io/stream-goat/`
   - **Redirect URI:** `urn:ietf:wg:oauth:2.0:oob` (the apps use the device code sign-in, not a redirect)
3. Copy the **Client ID**. You don't need the client secret: never put it in the app or in this repository.

WeTrakr's API is free for personal and non-commercial apps (terms: [api.wetrakr.com](https://api.wetrakr.com), Terms of use).

## 2. Add it to GitHub (once)

1. Open the repository on GitHub → **Settings → Secrets and variables → Actions → New repository secret**.
2. **Name:** `WETRAKR_CLIENT_ID`, **Secret:** the Client ID from step 1.

The next build (test build or release) has WeTrakr sync turned on, in every app. Copies of the repository don't get
the secret, so other people's builds don't use your app key.

## What the apps send

- When you play a movie or an episode: "now watching" with its progress (`/scrobble/start`, `/scrobble/pause`),
  unless *Show what I'm watching* is turned off.
- When you stop or finish: `/scrobble/stop`. At 80% or more WeTrakr marks it watched.
- Titles are identified by name, year, TMDB and IMDb ids (from the extension or Stream Goat's metadata), and for
  episodes the season and episode number.
- Each profile connects its own account. Only the sign-in tokens are kept on the device (renewed automatically),
  never a password. *Disconnect* ends the session on WeTrakr too.
- Live TV isn't sent.

The web version can't sync yet: WeTrakr's API doesn't accept requests from web pages (no CORS).
