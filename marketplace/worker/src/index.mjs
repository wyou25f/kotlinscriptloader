const LIMITS = { script: 1024 * 1024, plugin: 50 * 1024 * 1024, cover: 3 * 1024 * 1024 };
const SESSION_SECONDS = 21600;
const HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const enc = new TextEncoder();
const dec = new TextDecoder();
function json(data, status = 200, headers = {}) { return new Response(JSON.stringify(data), { status, headers: { ...HEADERS, ...headers } }); }
function fail(message, status = 400) { return json({ error: message }, status); }
function b64url(bytes) {
  let raw = "";
  for (const byte of bytes) raw += String.fromCharCode(byte);
  return btoa(raw).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function unb64url(value) {
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function randomToken(size = 32) { return b64url(crypto.getRandomValues(new Uint8Array(size))); }
async function hash(value) {
  const bytes = typeof value === "string" ? enc.encode(value) : value;
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, b => b.toString(16).padStart(2, "0")).join("");
}
async function key(env) {
  if (!env.SESSION_SECRET || env.SESSION_SECRET.length < 32) throw new Error("SESSION_SECRET must be 32 characters or longer");
  return crypto.subtle.importKey("raw", enc.encode(env.SESSION_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
function adminNames(env) { return (env.ADMIN_GITHUB_LOGINS || "").split(",").map(x => x.trim().toLowerCase()).filter(Boolean); }
async function sessionToken(user, env) {
  const h = b64url(enc.encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const p = b64url(enc.encode(JSON.stringify({ sub: String(user.id), login: user.login, avatar_url: user.avatar_url || "", exp: Math.floor(Date.now() / 1000) + SESSION_SECONDS })));
  const input = h + "." + p;
  const sig = await crypto.subtle.sign("HMAC", await key(env), enc.encode(input));
  return input + "." + b64url(new Uint8Array(sig));
}
async function getUser(request, env) {
  const match = (request.headers.get("Authorization") || "").match(/^Bearer\s+([A-Za-z0-9_.-]+)$/i);
  if (!match) return null;
  const parts = match[1].split(".");
  if (parts.length !== 3) return null;
  try {
    if (!await crypto.subtle.verify("HMAC", await key(env), unb64url(parts[2]), enc.encode(parts[0] + "." + parts[1]))) return null;
    const p = JSON.parse(dec.decode(unb64url(parts[1])));
    if (!p.sub || !p.login || p.exp <= Math.floor(Date.now() / 1000)) return null;
    return { id: String(p.sub), login: String(p.login), avatar_url: String(p.avatar_url || ""), isAdmin: adminNames(env).includes(String(p.login).toLowerCase()) };
  } catch { return null; }
}
function cors(request, env, response) {
  const h = new Headers(response.headers);
  const origin = request.headers.get("Origin");
  if (origin && origin === env.PUBLIC_ORIGIN) {
    h.set("Access-Control-Allow-Origin", origin);
    h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    h.set("Access-Control-Max-Age", "86400");
    h.append("Vary", "Origin");
  }
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: h });
}
function text(form, name, max, required = false) {
  const raw = form.get(name);
  const value = typeof raw === "string" ? raw.trim() : "";
  if (required && !value) throw new Error("Поле " + name + " обязательно");
  if (value.length > max) throw new Error("Поле " + name + " слишком длинное");
  return value;
}
function validVersion(v) { return /^\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?$/.test(v); }
function slugPart(v) { return v.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 48) || "ksl-item"; }
function filename(v) {
  const leaf = String(v || "artifact.bin").split(/[\\/]/).pop();
  return leaf.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120) || "artifact.bin";
}
async function artifact(file, kind) {
  if (!(file instanceof File)) throw new Error("Прикрепи файл сборки");
  const ext = kind === "plugin" ? ".jar" : ".kts";
  if (!file.name.toLowerCase().endsWith(ext)) throw new Error("Ожидается файл " + ext);
  if (!file.size || file.size > LIMITS[kind]) throw new Error("Недопустимый размер файла");
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (kind === "plugin" && (bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b)) throw new Error("Файл JAR не похож на ZIP-архив");
  if (kind === "script" && bytes.includes(0)) throw new Error("Kotlin script содержит нулевые байты");
  return { bytes, size: bytes.length, sha: await hash(bytes), filename: filename(file.name), contentType: kind === "plugin" ? "application/java-archive" : "text/plain; charset=utf-8" };
}
async function coverImage(file) {
  if (!(file instanceof File) || !file.size) return null;
  if (file.size > LIMITS.cover) throw new Error("Обложка должна быть меньше 3 МБ");
  const bytes = new Uint8Array(await file.arrayBuffer());
  let ext = "", type = "";
  if ([137,80,78,71,13,10,26,10].every((n, i) => bytes[i] === n)) { ext = "png"; type = "image/png"; }
  else if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) { ext = "jpg"; type = "image/jpeg"; }
  else if (dec.decode(bytes.slice(0,4)) === "RIFF" && dec.decode(bytes.slice(8,12)) === "WEBP") { ext = "webp"; type = "image/webp"; }
  else throw new Error("Обложка должна быть PNG, JPEG или WebP");
  return { bytes, ext, type };
}
function disposition(name) {
  const safe = filename(name);
  return 'attachment; filename="' + safe + '"; filename*=UTF-8\'\'' + encodeURIComponent(safe);
}
async function oauthLogin(env) {
  if (!env.GITHUB_CLIENT_ID || !env.GITHUB_REDIRECT_URI) return fail("GitHub OAuth не настроен", 503);
  const state = randomToken(24);
  await env.DB.prepare("INSERT INTO oauth_states(state_hash, expires_at) VALUES (?,?)")
    .bind(await hash(state), Math.floor(Date.now()/1000) + 600).run();
  const u = new URL("https://github.com/login/oauth/authorize");
  u.searchParams.set("client_id", env.GITHUB_CLIENT_ID);
  u.searchParams.set("redirect_uri", env.GITHUB_REDIRECT_URI);
  u.searchParams.set("scope", "read:user");
  u.searchParams.set("state", state);
  return Response.redirect(u.toString(), 302);
}
async function oauthCallback(request, env) {
  const u = new URL(request.url), code = u.searchParams.get("code"), state = u.searchParams.get("state");
  if (!code || !state) return fail("OAuth callback не содержит code/state", 400);
  const now = Math.floor(Date.now()/1000);
  const stateRow = await env.DB.prepare("DELETE FROM oauth_states WHERE state_hash=? AND expires_at>? RETURNING state_hash")
    .bind(await hash(state), now).first();
  if (!stateRow) return fail("OAuth state недействителен или устарел", 401);
  const tr = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ client_id: env.GITHUB_CLIENT_ID, client_secret: env.GITHUB_CLIENT_SECRET, code, redirect_uri: env.GITHUB_REDIRECT_URI })
  });
  const td = await tr.json();
  if (!tr.ok || !td.access_token) return fail("GitHub не выдал OAuth token", 401);
  const ur = await fetch("https://api.github.com/user", {
    headers: { "Authorization": "Bearer " + td.access_token, "Accept": "application/vnd.github+json", "User-Agent": "KSL-Marketplace" }
  });
  const profile = await ur.json();
  if (!ur.ok || !profile.id || !profile.login) return fail("Не удалось получить профиль GitHub", 401);
  const ticket = randomToken(32);
  await env.DB.prepare("INSERT INTO auth_codes(code_hash,user_json,expires_at) VALUES (?,?,?)")
    .bind(await hash(ticket), JSON.stringify({ id: profile.id, login: profile.login, avatar_url: profile.avatar_url || "" }), now + 180).run();
  const target = new URL(env.PUBLIC_SITE_URL);
  target.hash = "ticket=" + ticket;
  return Response.redirect(target.toString(), 302);
}
async function oauthExchange(request, env) {
  const body = await request.json().catch(() => null);
  const ticket = typeof body?.ticket === "string" ? body.ticket : "";
  if (!/^[A-Za-z0-9_-]{40,60}$/.test(ticket)) return fail("OAuth ticket invalid", 400);
  const row = await env.DB.prepare("DELETE FROM auth_codes WHERE code_hash=? AND expires_at>? RETURNING user_json")
    .bind(await hash(ticket), Math.floor(Date.now()/1000)).first();
  if (!row) return fail("OAuth ticket уже использован или устарел", 401);
  const user = JSON.parse(row.user_json);
  return json({ token: await sessionToken(user, env), user: { id: String(user.id), login: user.login, avatar_url: user.avatar_url || "", isAdmin: adminNames(env).includes(String(user.login).toLowerCase()) } });
}
async function catalogue(url, env) {
  const kind = url.searchParams.get("kind"), q = (url.searchParams.get("q") || "").trim().slice(0,80);
  const limit = Math.min(48, Math.max(1, Number(url.searchParams.get("limit")) || 24));
  const offset = Math.max(0, Number(url.searchParams.get("offset")) || 0);
  const where = ["i.status='approved'", "EXISTS (SELECT 1 FROM versions v WHERE v.item_id=i.id AND v.status='approved')"], args = [];
  if (kind === "script" || kind === "plugin") { where.push("i.kind=?"); args.push(kind); }
  if (q) {
    where.push("(i.name LIKE ? OR i.description LIKE ? OR i.author_login LIKE ? OR i.category LIKE ?)");
    const term = "%" + q.replace(/[\\%_]/g, "") + "%";
    args.push(term,term,term,term);
  }
  args.push(limit,offset);
  const rows = await env.DB.prepare(
    "SELECT i.id,i.slug,i.kind,i.name,i.description,i.category,i.cover_key,i.author_login,i.created_at,i.updated_at," +
    "(SELECT v.version FROM versions v WHERE v.item_id=i.id AND v.status='approved' ORDER BY v.created_at DESC LIMIT 1) latest_version," +
    "(SELECT COUNT(*) FROM versions v WHERE v.item_id=i.id AND v.status='approved') version_count " +
    "FROM items i WHERE " + where.join(" AND ") + " ORDER BY i.updated_at DESC LIMIT ? OFFSET ?"
  ).bind(...args).all();
  const items = (rows.results || []).map(x => ({
    id:x.id,slug:x.slug,kind:x.kind,name:x.name,description:x.description,category:x.category,
    author_login:x.author_login,created_at:x.created_at,updated_at:x.updated_at,
    latest_version:x.latest_version,version_count:x.version_count,
    cover_url:x.cover_key ? "/api/media/" + encodeURIComponent(x.slug) : null
  }));
  return json({ items, limit, offset });
}
async function itemDetails(request, env, slug) {
  const user = await getUser(request,env);
  const item = await env.DB.prepare("SELECT id,slug,kind,name,description,category,cover_key,author_github_id,author_login,status,created_at,updated_at FROM items WHERE slug=?").bind(slug).first();
  if (!item || (item.status !== "approved" && (!user || (user.id !== String(item.author_github_id) && !user.isAdmin)))) return fail("Публикация не найдена",404);
  const owner = Boolean(user && (user.id === String(item.author_github_id) || user.isAdmin));
  const versions = await env.DB.prepare(
    "SELECT id,version,changelog,min_ksl_version,mc_versions_json,filename,sha256,size_bytes,status,created_at FROM versions WHERE item_id=? " +
    (owner ? "" : "AND status='approved' ") + "ORDER BY created_at DESC"
  ).bind(item.id).all();
  return json({
    item: { id:item.id,slug:item.slug,kind:item.kind,name:item.name,description:item.description,category:item.category,author_login:item.author_login,status:item.status,created_at:item.created_at,cover_url:item.cover_key ? "/api/media/"+encodeURIComponent(item.slug) : null },
    versions:versions.results || [], canUploadVersion:owner
  });
}
async function getMedia(env, slug) {
  const row = await env.DB.prepare("SELECT cover_key FROM items WHERE slug=? AND status='approved'").bind(slug).first();
  if (!row?.cover_key) return fail("Обложка не найдена",404);
  const object = await env.FILES.get(row.cover_key);
  if (!object) return fail("Обложка не найдена",404);
  return new Response(object.body,{headers:{"Content-Type":object.httpMetadata?.contentType || "application/octet-stream","Cache-Control":"public,max-age=3600","ETag":object.httpEtag,"X-Content-Type-Options":"nosniff"}});
}
async function getDownload(env, slug, version) {
  const row = await env.DB.prepare(
    "SELECT v.asset_key,v.filename,v.sha256,v.size_bytes,i.kind FROM versions v JOIN items i ON i.id=v.item_id " +
    "WHERE i.slug=? AND v.version=? AND i.status='approved' AND v.status='approved'"
  ).bind(slug,version).first();
  if (!row) return fail("Версия не найдена или ещё не опубликована",404);
  const object = await env.FILES.get(row.asset_key);
  if (!object) return fail("Файл сборки не найден",404);
  return new Response(object.body,{headers:{
    "Content-Type":row.kind==="plugin" ? "application/java-archive" : "text/plain; charset=utf-8",
    "Content-Length":String(row.size_bytes),"Content-Disposition":disposition(row.filename),
    "X-Content-SHA256":row.sha256,"X-Content-Type-Options":"nosniff","Cache-Control":"public,max-age=300"
  }});
}
async function createItem(request, env, user) {
  if (Number(request.headers.get("Content-Length") || 0) > 55*1024*1024) return fail("Слишком большой запрос",413);
  const form = await request.formData();
  const kind = text(form,"kind",16,true);
  if (kind !== "script" && kind !== "plugin") return fail("kind должен быть script или plugin");
  const name=text(form,"name",80,true), description=text(form,"description",5000,true);
  const category=text(form,"category",48,true), version=text(form,"version",40,true);
  const changelog=text(form,"changelog",4000,true), minKsl=text(form,"minKslVersion",40);
  const mc=text(form,"mcVersions",160).split(",").map(x=>x.trim()).filter(Boolean).slice(0,12);
  if (!validVersion(version)) return fail("Версия должна быть в формате 1.0.0");
  if (description.length < 20) return fail("Описание слишком короткое");
  const file=await artifact(form.get("artifact"),kind), image=await coverImage(form.get("cover"));
  const id=crypto.randomUUID(), versionId=crypto.randomUUID(), slug=slugPart(name)+"-"+id.slice(0,6);
  const assetKey="items/"+id+"/versions/"+versionId+"/"+file.filename;
  const coverKey=image ? "items/"+id+"/cover."+image.ext : null;
  if (image) await env.FILES.put(coverKey,image.bytes,{httpMetadata:{contentType:image.type}});
  await env.FILES.put(assetKey,file.bytes,{httpMetadata:{contentType:file.contentType},customMetadata:{sha256:file.sha}});
  const now=new Date().toISOString();
  try {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO items(id,slug,kind,name,description,category,cover_key,author_github_id,author_login,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'pending',?,?)")
        .bind(id,slug,kind,name,description,category,coverKey,user.id,user.login,now,now),
      env.DB.prepare("INSERT INTO versions(id,item_id,version,changelog,min_ksl_version,mc_versions_json,asset_key,filename,sha256,size_bytes,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'pending',?)")
        .bind(versionId,id,version,changelog,minKsl,JSON.stringify(mc),assetKey,file.filename,file.sha,file.size,now)
    ]);
  } catch(error) {
    await env.FILES.delete(assetKey);
    if (coverKey) await env.FILES.delete(coverKey);
    throw error;
  }
  return json({ok:true,slug,status:"pending",message:"Публикация отправлена на модерацию"},201);
}
async function addVersion(request, env, user, slug) {
  const item=await env.DB.prepare("SELECT id,kind,author_github_id,status FROM items WHERE slug=?").bind(slug).first();
  if (!item || String(item.author_github_id)!==user.id || item.status!=="approved") return fail("Публикация не найдена, не опубликована или принадлежит другому автору",404);
  const form=await request.formData();
  const version=text(form,"version",40,true), changelog=text(form,"changelog",4000,true);
  const minKsl=text(form,"minKslVersion",40), mc=text(form,"mcVersions",160).split(",").map(x=>x.trim()).filter(Boolean).slice(0,12);
  if (!validVersion(version)) return fail("Неверный формат версии");
  const file=await artifact(form.get("artifact"),item.kind), versionId=crypto.randomUUID();
  const assetKey="items/"+item.id+"/versions/"+versionId+"/"+file.filename, now=new Date().toISOString();
  await env.FILES.put(assetKey,file.bytes,{httpMetadata:{contentType:file.contentType},customMetadata:{sha256:file.sha}});
  try {
    await env.DB.prepare("INSERT INTO versions(id,item_id,version,changelog,min_ksl_version,mc_versions_json,asset_key,filename,sha256,size_bytes,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'pending',?)")
      .bind(versionId,item.id,version,changelog,minKsl,JSON.stringify(mc),assetKey,file.filename,file.sha,file.size,now).run();
  } catch(error) {
    await env.FILES.delete(assetKey);
    if (String(error).includes("UNIQUE")) return fail("Такая версия уже существует",409);
    throw error;
  }
  await env.DB.prepare("UPDATE items SET updated_at=? WHERE id=?").bind(now,item.id).run();
  return json({ok:true,status:"pending",message:"Версия отправлена на модерацию"},201);
}
async function listPending(env) {
  const rows=await env.DB.prepare(
    "SELECT i.id item_id,i.slug,i.kind,i.name,i.author_login,i.status item_status,v.id version_id,v.version,v.filename,v.sha256,v.size_bytes,v.status version_status,v.created_at " +
    "FROM versions v JOIN items i ON i.id=v.item_id WHERE v.status='pending' OR i.status='pending' ORDER BY v.created_at ASC LIMIT 100"
  ).all();
  return json({pending:rows.results || []});
}
async function review(request,env,itemId,versionId) {
  const body=await request.json().catch(()=>null), decision=body?.decision;
  if (decision!=="approve" && decision!=="reject") return fail("decision должен быть approve или reject");
  const status=decision==="approve"?"approved":"rejected", now=new Date().toISOString();
  if (itemId) {
    const item=await env.DB.prepare("SELECT id FROM items WHERE id=?").bind(itemId).first();
    if (!item) return fail("Публикация не найдена",404);
    await env.DB.batch([
      env.DB.prepare("UPDATE items SET status=?,updated_at=? WHERE id=?").bind(status,now,itemId),
      env.DB.prepare("UPDATE versions SET status=? WHERE item_id=? AND status='pending'").bind(status,itemId)
    ]);
  } else {
    const row=await env.DB.prepare("SELECT id FROM versions WHERE id=?").bind(versionId).first();
    if (!row) return fail("Версия не найдена",404);
    await env.DB.prepare("UPDATE versions SET status=? WHERE id=?").bind(status,versionId).run();
  }
  return json({ok:true,status});
}
async function route(request,env) {
  const u=new URL(request.url), path=u.pathname;
  if (request.method==="OPTIONS") return new Response(null,{status:204});
  if (request.method==="GET" && path==="/auth/login") return oauthLogin(env);
  if (request.method==="GET" && path==="/auth/callback") return oauthCallback(request,env);
  if (request.method==="POST" && path==="/auth/exchange") return oauthExchange(request,env);
  if (request.method==="GET" && path==="/api/me") return json({user:await getUser(request,env)});
  if (request.method==="GET" && path==="/api/catalog") return catalogue(u,env);
  if (request.method==="GET" && path==="/api/admin/pending") {
    const user=await getUser(request,env);
    if (!user?.isAdmin) return fail("Недостаточно прав",403);
    return listPending(env);
  }
  const itemReview=path.match(/^\/api\/admin\/items\/([a-f0-9-]+)\/review$/);
  if (request.method==="POST" && itemReview) {
    const user=await getUser(request,env);
    if (!user?.isAdmin) return fail("Недостаточно прав",403);
    return review(request,env,itemReview[1],null);
  }
  const versionReview=path.match(/^\/api\/admin\/versions\/([a-f0-9-]+)\/review$/);
  if (request.method==="POST" && versionReview) {
    const user=await getUser(request,env);
    if (!user?.isAdmin) return fail("Недостаточно прав",403);
    return review(request,env,null,versionReview[1]);
  }
  const media=path.match(/^\/api\/media\/([a-z0-9-]+)$/);
  if (request.method==="GET" && media) return getMedia(env,media[1]);
  const download=path.match(/^\/api\/download\/([a-z0-9-]+)\/([0-9A-Za-z.+-]+)$/);
  if (request.method==="GET" && download) return getDownload(env,download[1],download[2]);
  if (request.method==="POST" && path==="/api/items") {
    const user=await getUser(request,env);
    if (!user) return fail("Нужно войти через GitHub",401);
    return createItem(request,env,user);
  }
  const version=path.match(/^\/api\/items\/([a-z0-9-]+)\/versions$/);
  if (request.method==="POST" && version) {
    const user=await getUser(request,env);
    if (!user) return fail("Нужно войти через GitHub",401);
    return addVersion(request,env,user,version[1]);
  }
  const detail=path.match(/^\/api\/items\/([a-z0-9-]+)$/);
  if (request.method==="GET" && detail) return itemDetails(request,env,detail[1]);
  return fail("Маршрут не найден",404);
}
export default {
  async fetch(request,env) {
    try { return cors(request,env,await route(request,env)); }
    catch(error) { return cors(request,env,fail("Внутренняя ошибка API: "+(error?.message || "unknown"),500)); }
  }
};
