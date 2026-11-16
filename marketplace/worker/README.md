# KSL Marketplace API

Catalogue UI is hosted on GitHub Pages. This Worker provides GitHub OAuth, creator uploads, public catalogue queries, version history, downloads and moderator review.

## Infrastructure

- Cloudflare Worker: API runtime.
- Cloudflare D1: catalogue metadata, version history and short-lived OAuth state/tickets.
- Cloudflare R2: .kts/.jar artifacts and cover images.
- GitHub OAuth: creator identity. The GitHub access token is never sent to the browser.

New items and versions remain pending until a moderator approves them. The API checks extensions, upload sizes, basic JAR/image signatures and stores SHA-256 hashes. This is not malware scanning: JAR files and KSL scripts are executable code and must be reviewed before publication. KSL's in-process sandbox is not a security boundary for hostile code.

## Setup

1. Create a Cloudflare D1 database named ksl-marketplace and an R2 bucket named ksl-marketplace-files.
2. Put the D1 database ID into wrangler.toml.
3. Create a GitHub OAuth App. Set its callback URL to the Worker URL plus /auth/callback.
4. Keep PUBLIC_ORIGIN and PUBLIC_SITE_URL aligned with the published GitHub Pages site.
5. Configure secrets:

~~~sh
npx wrangler d1 migrations apply ksl-marketplace --remote
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET
npx wrangler secret put SESSION_SECRET
~~~

Use a random SESSION_SECRET of at least 32 characters. ADMIN_GITHUB_LOGINS lists moderator GitHub logins.

6. Deploy the Worker:

~~~sh
npx wrangler deploy
~~~

7. Set window.KSL_MARKETPLACE_API in website/assets/marketplace-config.js to the deployed Worker origin, commit that file and let the Pages workflow publish it.

## Routes

- GET /auth/login, GET /auth/callback, POST /auth/exchange
- GET /api/me, GET /api/catalog, GET /api/items/:slug
- POST /api/items, POST /api/items/:slug/versions
- GET /api/media/:slug, GET /api/download/:slug/:version
- GET /api/admin/pending, POST /api/admin/items/:id/review, POST /api/admin/versions/:id/review

## Current limits

- .kts: 1 MB; .jar: 50 MB; cover image: 3 MB (PNG/JPEG/WebP).
- First version and all later versions require moderation.
- No rating system, payments, automated malware scanning, sandboxed build farm or dependency resolver.
- Configure with your Cloudflare and GitHub OAuth accounts before enabling uploads.
