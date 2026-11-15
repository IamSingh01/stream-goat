# WeTrakr sync: setting up the app key

Stream Goat syncs with [WeTrakr](https://wetrakr.com), a free movie, TV and anime tracker. People connect their own
WeTrakr account in the app (Settings → Metadata & integrations → WeTrakr sync) with a short code, like the Google
sign-in. For that, Stream Goat needs its own **app key** (`client_id`) from WeTrakr, given to the build as a GitHub
secret. Until the secret is set, the apps say "Not available in this build yet".

## 1. Register Stream Goat on WeTrakr (once)

While WeTrakr's API is in its testing phase, apps are registered on request (Settings → API on wetrakr.com says
"You haven't created an app yet" until then). Ask on [WeTrakr's Discord](https://discord.gg/XedDZYpFdh) with:

- **Name:** Stream Goat
- **Description:** Free media player for Android phones, Android TV, Windows, macOS and Linux. Marks the movies and
  episodes you watch as watched on WeTrakr and shows what you're playing now.
- **Redirect URI:** `urn:ietf:wg:oauth:2.0:oob` (the apps sign in with the device code flow, no browser callback)
- **Website:** `https://sherazi5110.github.io/stream-goat/`
- Your WeTrakr username (each account can register one app)

Once registered, the app appears at wetrakr.com → **Settings → API** with a **Client ID** and a client secret.
Copy only the Client ID: never put the client secret in the app or in this repository.

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
