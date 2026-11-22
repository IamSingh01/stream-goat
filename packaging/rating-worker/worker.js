// Stream Goat ratings: the website's "Rate the app" stars send a vote here (1 to 5). The totals are kept in a
// PRIVATE GitHub repository of the owner (ratings.json and RATINGS.md), so only the owner sees them.
// Nothing about the visitor is stored: a salted hash of their address only stops the same person voting twice a day.
//
// Cloudflare Worker settings (docs/rating-setup.md):
//   GITHUB_TOKEN  secret: a fine-grained token with "Contents: read and write" on the private repository only
//   RATINGS_REPO  variable: e.g. sherazi5110/stream-goat-ratings
//   SALT          secret: any random text
//   VOTES         KV namespace binding (optional): one vote per visitor per day

const SITE = "https://sherazi5110.github.io";

const cors = {
  "Access-Control-Allow-Origin": SITE,
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

const reply = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64(text) {
  return btoa(String.fromCharCode(...new TextEncoder().encode(text)));
}

async function github(env, path, init = {}) {
  return fetch(`https://api.github.com/repos/${env.RATINGS_REPO}/contents/${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "stream-goat-ratings",
      ...(init.headers || {}),
    },
  });
}

async function save(env, stars) {
  // Read, add the vote, write back; if someone else's vote landed in between, try again.
  for (let attempt = 0; attempt < 4; attempt++) {
    const current = await github(env, "ratings.json");
    let data = { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0 };
    let sha;
    if (current.ok) {
      const file = await current.json();
      sha = file.sha;
      data = { ...data, ...JSON.parse(atob(file.content.replace(/\n/g, ""))) };
    }
    data[String(stars)] = (data[String(stars)] || 0) + 1;
    const total = [1, 2, 3, 4, 5].reduce((n, s) => n + data[s], 0);
    const average = total ? [1, 2, 3, 4, 5].reduce((n, s) => n + s * data[s], 0) / total : 0;
    const put = await github(env, "ratings.json", {
      method: "PUT",
      body: JSON.stringify({ message: `Rating: ${stars} stars`, content: b64(JSON.stringify(data, null, 2) + "\n"), sha }),
    });
    if (put.status === 409 || put.status === 422) continue;
    if (!put.ok) throw new Error(`GitHub ${put.status}`);
    // A readable summary next to it (best effort).
    const md = await github(env, "RATINGS.md");
    const mdSha = md.ok ? (await md.json()).sha : undefined;
    const lines = [5, 4, 3, 2, 1].map((s) => `| ${"★".repeat(s)}${"☆".repeat(5 - s)} | ${data[s]} |`).join("\n");
    await github(env, "RATINGS.md", {
      method: "PUT",
      body: JSON.stringify({
        message: "Update the summary",
        content: b64(`# Stream Goat ratings\n\n**${average.toFixed(2)} out of 5** from ${total} rating${total === 1 ? "" : "s"}.\n\n| Stars | Votes |\n|---|---|\n${lines}\n\nUpdated ${new Date().toISOString().slice(0, 16).replace("T", " ")} UTC.\n`),
        sha: mdSha,
      }),
    });
    return;
  }
  throw new Error("busy");
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    if (request.method !== "POST") return reply(405, { error: "POST a rating" });
    if (!(request.headers.get("Origin") || "").startsWith(SITE)) return reply(403, { error: "not from the website" });
    let stars;
    try { stars = Number((await request.json()).stars); } catch { stars = NaN; }
    if (!Number.isInteger(stars) || stars < 1 || stars > 5) return reply(400, { error: "stars must be 1 to 5" });
    if (env.VOTES) {
      const who = await sha256((env.SALT || "") + (request.headers.get("CF-Connecting-IP") || ""));
      if (await env.VOTES.get(who)) return reply(200, { ok: true, again: true });
      await env.VOTES.put(who, "1", { expirationTtl: 86400 });
    }
    try {
      await save(env, stars);
      return reply(200, { ok: true });
    } catch (e) {
      return reply(502, { error: String(e.message || e) });
    }
  },
};
