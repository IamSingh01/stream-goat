# "Rate Stream Goat" on the website

The website has a **Rate Stream Goat** box (1 to 5 stars, one vote per device, no sign-in). Votes go to a small
free counter (a Cloudflare Worker, [packaging/rating-worker/worker.js](../packaging/rating-worker/worker.js)), which
keeps the totals in a **private** GitHub repository: only you see the score. The box stays hidden until step 5 is done.

Nothing about visitors is stored. With the optional step 4, a scrambled (hashed) form of their internet address is
kept for a day so the same person can't vote many times; it can't be turned back into the address.

## 1. A private repository for the score

1. Open <https://github.com/new>.
2. Name: `stream-goat-ratings`, choose **Private**, tick **Add a README file**, press **Create repository**.

## 2. A token that can only write to that repository

1. Open <https://github.com/settings/personal-access-tokens/new> (fine-grained token).
2. Name: `stream-goat-ratings`; Expiration: the longest offered (renew it when GitHub emails you).
3. **Repository access → Only select repositories →** `stream-goat-ratings`.
4. **Permissions → Repository permissions → Contents → Read and write.** Nothing else.
5. **Generate token** and copy it (it's shown once). Don't paste it anywhere except step 3.

## 3. The counter (free Cloudflare account)

1. Sign up or log in at <https://dash.cloudflare.com>.
2. **Workers & Pages → Create → Create Worker** (the "Hello World" one). Name it `stream-goat-rating` and press
   **Deploy**.
3. **Edit code**: delete what's there, paste all of `packaging/rating-worker/worker.js`, press **Deploy**.
4. In the Worker: **Settings → Variables and Secrets → Add**:
   - `RATINGS_REPO`, type **Text**: `sherazi5110/stream-goat-ratings`
   - `GITHUB_TOKEN`, type **Secret**: the token from step 2
   - `SALT`, type **Secret**: any long random text (e.g. mash the keyboard)
5. Copy the Worker's address, shown at the top: `https://stream-goat-rating.<your-name>.workers.dev`.

## 4. One vote per person per day (optional, recommended)

1. **Storage & Databases → KV → Create** a namespace named `stream-goat-votes`.
2. Back in the Worker: **Settings → Bindings → Add → KV namespace**: variable name `VOTES`, namespace
   `stream-goat-votes`. **Deploy**.

## 5. Show the stars on the website

1. In **this** repository (stream-goat): **Settings → Secrets and variables → Actions → Variables** tab →
   **New repository variable**: name `RATING_URL`, value the Worker address from step 3.5.
2. **Actions → Web app (GitHub Pages) → Run workflow** (or wait for the next website change).

## Seeing the score

Open your private repository: **RATINGS.md** shows the average out of 5, the number of ratings and the votes for each
star (ratings.json has the raw numbers). Every vote appears in its history.
