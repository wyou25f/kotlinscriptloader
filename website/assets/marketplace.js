window.KSLMarketPage = async function(app, esc, shell, GH) {
  const API=String(window.KSL_MARKETPLACE_API||"").replace(/\/+$/,"");
  const TOKEN_KEY="ksl-marketplace-session";
  let token=sessionStorage.getItem(TOKEN_KEY)||"", user=null, kind="all";
  shell("COMMUNITY MARKETPLACE",'Маркетплейс <span class="gradient">KSL</span>',
    "Скрипты и плагины сообщества: сборки, описания, обложки и история версий.",
    '<div class="marketplace"><div class="market-toolbar">'+
      '<div class="market-tabs"><button class="market-tab active" data-kind="all">Все</button><button class="market-tab" data-kind="script">Скрипты</button><button class="market-tab" data-kind="plugin">Плагины</button></div>'+
      '<label class="market-search"><span>Поиск</span><input id="market-search" type="search" placeholder="Название, описание или автор"></label>'+
      '<div class="market-account" id="market-account"></div></div>'+
      '<div id="market-notice" class="market-notice" role="status"></div><section id="market-upload-slot"></section><section id="market-admin-slot"></section>'+
      '<section id="market-detail"></section><section><div class="market-section-head"><div><span class="eyebrow">COMMUNITY CATALOGUE</span><h2>Каталог публикаций</h2></div><span id="market-count" class="market-count">—</span></div>'+
      '<div id="market-grid" class="market-grid"><div class="empty">Загружаем каталог…</div></div></section>'+
      '<p class="market-footnote">Проверяй совместимость и changelog перед установкой. Скрипты и JAR-файлы — исполняемый код: загружай их только от авторов, которым доверяешь.</p></div>');
  const notice=document.querySelector("#market-notice"),account=document.querySelector("#market-account");
  const uploadSlot=document.querySelector("#market-upload-slot"),adminSlot=document.querySelector("#market-admin-slot");
  const detail=document.querySelector("#market-detail"),grid=document.querySelector("#market-grid");
  const search=document.querySelector("#market-search"),count=document.querySelector("#market-count");
  function message(value,error){notice.textContent=value||"";notice.className="market-notice"+(error?" error":"")}
  async function api(path,options){
    if(!API)throw new Error("Marketplace API ещё не подключён");
    const headers=new Headers(options&&options.headers?options.headers:{});
    if(token)headers.set("Authorization","Bearer "+token);
    const response=await fetch(API+path,{...options,headers});
    const data=await response.json().catch(()=>({}));
    if(!response.ok)throw new Error(data.error||("HTTP "+response.status));
    return data;
  }
  function sizeLabel(value){const n=Number(value)||0;if(n<1024)return n+" B";if(n<1048576)return (n/1024).toFixed(1)+" KB";return (n/1048576).toFixed(1)+" MB"}
  function statusLabel(v){return v==="approved"?"Опубликовано":v==="rejected"?"Отклонено":"На модерации"}
  function renderAccount(){
    if(!API){account.innerHTML='<span class="market-config-warning">API не подключён</span>';return}
    if(user){
      account.innerHTML=(user.avatar_url?'<img class="market-avatar" src="'+esc(user.avatar_url)+'" alt="">':"")+
        '<b>'+esc(user.login)+'</b><button type="button" class="btn" id="market-logout">Выйти</button>';
      document.querySelector("#market-logout").addEventListener("click",()=>{token="";user=null;sessionStorage.removeItem(TOKEN_KEY);refresh()});
    }else account.innerHTML='<a class="btn primary" href="'+esc(API+"/auth/login")+'">Войти через GitHub</a>';
  }
  function renderUpload(){
    if(!user){uploadSlot.innerHTML="";return}
    uploadSlot.innerHTML='<details class="market-upload"><summary>＋ Опубликовать скрипт или плагин</summary><form id="market-upload-form" class="market-form">'+
      '<div class="market-form-grid"><label>Тип публикации<select name="kind" required><option value="script">Kotlin script (.kts)</option><option value="plugin">Плагин (.jar)</option></select></label>'+
      '<label>Название<input name="name" maxlength="80" required placeholder="Например, Advanced Homes"></label>'+
      '<label>Категория<input name="category" maxlength="48" required placeholder="Economy, Admin, Utility..."></label>'+
      '<label>Первая версия<input name="version" maxlength="40" required placeholder="1.0.0"></label>'+
      '<label>Минимальная версия KSL<input name="minKslVersion" maxlength="40" placeholder="1.2.0"></label>'+
      '<label>Версии Minecraft<input name="mcVersions" maxlength="160" placeholder="1.20.4, 1.21.1"></label></div>'+
      '<label>Описание<textarea name="description" minlength="20" maxlength="5000" rows="4" required placeholder="Что делает публикация, как её настроить и какие зависимости нужны."></textarea></label>'+
      '<label>Changelog первой версии<textarea name="changelog" maxlength="4000" rows="3" required placeholder="Что входит в эту версию"></textarea></label>'+
      '<div class="market-form-grid"><label>Файл сборки<input name="artifact" type="file" accept=".kts,.jar" required></label><label>Обложка (PNG/JPEG/WebP)<input name="cover" type="file" accept="image/png,image/jpeg,image/webp"></label></div>'+
      '<button class="btn primary" type="submit">Отправить на модерацию</button><p class="market-form-help">Скрипт: до 1 МБ. Плагин JAR: до 50 МБ. Обложка: до 3 МБ.</p><div id="market-upload-status" class="market-form-status"></div></form></details>';
    document.querySelector("#market-upload-form").addEventListener("submit",async event=>{
      event.preventDefault();const form=event.currentTarget,status=document.querySelector("#market-upload-status"),button=form.querySelector("button");
      button.disabled=true;status.textContent="Загружаем файлы…";status.className="market-form-status";
      try{const result=await api("/api/items",{method:"POST",body:new FormData(form)});form.reset();status.textContent=result.message||"Отправлено на модерацию";message("Публикация отправлена. После проверки она появится в каталоге.");await refresh()}
      catch(error){status.textContent=error.message;status.className="market-form-status error"}
      finally{button.disabled=false}
    });
  }
  function renderItems(items){
    count.textContent=items.length+" публикаций";
    if(!items.length){grid.innerHTML='<div class="market-empty-state"><b>Публикаций пока нет</b><p>Попробуй изменить запрос или опубликуй первую работу сообщества.</p></div>';return}
    grid.innerHTML=items.map(item=>{
      const cover=item.cover_url?API+item.cover_url:"";
      return '<a class="market-card" href="./marketplace.html?item='+encodeURIComponent(item.slug)+'">'+
        (cover?'<img class="market-cover" src="'+esc(cover)+'" alt="" loading="lazy">':'<div class="market-cover market-cover-empty"><span>KSL</span></div>')+
        '<div class="market-card-body"><div class="market-card-meta"><span class="pill">'+(item.kind==="plugin"?"ПЛАГИН":"СКРИПТ")+'</span><span>'+esc(item.category||"Без категории")+'</span></div>'+
        '<h3>'+esc(item.name)+'</h3><p>'+esc(item.description)+'</p><div class="market-card-footer"><span>@'+esc(item.author_login)+'</span><span>v'+esc(item.latest_version||"—")+' · '+Number(item.version_count||0)+' вер.</span></div></div></a>';
    }).join("");
  }
  async function openItem(slug){
    if(!slug){detail.innerHTML="";return}
    detail.innerHTML='<div class="empty">Загружаем публикацию…</div>';
    try{
      const data=await api("/api/items/"+encodeURIComponent(slug)),item=data.item,versions=data.versions||[];
      const cover=item.cover_url?API+item.cover_url:"";
      const versionHtml=versions.length?versions.map(v=>{
        const mc=JSON.parse(v.mc_versions_json||"[]").join(", ");
        const status=item.status==="approved"&&v.status==="approved"?'<span class="market-status published">Опубликовано</span>':'<span class="market-status">'+esc(statusLabel(v.status))+'</span>';
        const download=v.status==="approved"&&item.status==="approved"?'<a class="btn" href="'+esc(API+"/api/download/"+encodeURIComponent(item.slug)+"/"+encodeURIComponent(v.version))+'">Скачать '+esc(v.filename)+'</a>':"";
        return '<article class="market-version"><div class="market-version-top"><strong>v'+esc(v.version)+'</strong>'+status+'</div><p>'+esc(v.changelog)+'</p>'+
          '<div class="market-version-meta"><span>KSL: '+esc(v.min_ksl_version||"не указана")+'</span><span>Minecraft: '+esc(mc||"не указаны")+'</span><span>'+sizeLabel(v.size_bytes)+'</span><span>SHA-256: <code>'+esc(v.sha256||"")+'</code></span></div>'+download+'</article>';
      }).join(""):'<div class="empty">История версий пока пуста.</div>';
      detail.innerHTML='<article class="market-detail-card"><div class="market-detail-heading">'+
        (cover?'<img src="'+esc(cover)+'" alt="">':'<div class="market-cover-empty"><span>KSL</span></div>')+
        '<div><span class="pill">'+(item.kind==="plugin"?"ПЛАГИН":"СКРИПТ")+'</span><h2>'+esc(item.name)+'</h2><p>Автор: @'+esc(item.author_login)+' · '+esc(item.category)+'</p><span class="market-status">'+esc(statusLabel(item.status))+'</span></div></div>'+
        '<p class="market-detail-description">'+esc(item.description)+'</p><div class="market-version-list"><div class="market-section-head"><h3>История версий</h3><span>'+versions.length+' версий</span></div>'+versionHtml+'</div>'+
        (data.canUploadVersion?'<details class="market-upload market-version-upload"><summary>＋ Загрузить новую версию</summary><form id="market-version-form" class="market-form">'+
          '<div class="market-form-grid"><label>Версия<input name="version" maxlength="40" required placeholder="1.1.0"></label><label>Минимальная версия KSL<input name="minKslVersion" maxlength="40"></label>'+
          '<label>Версии Minecraft<input name="mcVersions" maxlength="160"></label><label>Файл сборки<input name="artifact" type="file" accept="'+(item.kind==="plugin"?".jar":".kts")+'" required></label></div>'+
          '<label>Changelog<textarea name="changelog" maxlength="4000" rows="3" required></textarea></label><button class="btn primary" type="submit">Отправить версию</button><div class="market-form-status" id="market-version-status"></div></form></details>':"")+
        '</article>';
      const form=document.querySelector("#market-version-form");
      if(form)form.addEventListener("submit",async event=>{
        event.preventDefault();const button=form.querySelector("button"),status=document.querySelector("#market-version-status");
        button.disabled=true;status.textContent="Загружаем версию…";
        try{const result=await api("/api/items/"+encodeURIComponent(item.slug)+"/versions",{method:"POST",body:new FormData(form)});form.reset();status.textContent=result.message||"Версия отправлена";await openItem(item.slug);await refresh()}
        catch(error){status.textContent=error.message;status.className="market-form-status error"}
        finally{button.disabled=false}
      });
    }catch(error){detail.innerHTML='<div class="notice">'+esc(error.message)+'</div>'}
  }
  async function loadAdmin(){
    if(!user||!user.isAdmin){adminSlot.innerHTML="";return}
    try{
      const data=await api("/api/admin/pending"),rows=data.pending||[];
      adminSlot.innerHTML='<details class="market-admin"><summary>Модерация · '+rows.length+'</summary>'+
        (rows.length?rows.map(row=>{
          const fresh=row.item_status==="pending";
          return '<article class="market-admin-row"><div><b>'+esc(row.name)+' · '+esc(row.version)+'</b><small>'+
            (fresh?"Новая публикация":"Новая версия")+' · @'+esc(row.author_login)+' · '+sizeLabel(row.size_bytes)+'</small></div>'+
            '<button class="btn primary" data-review="'+(fresh?"items":"versions")+'" data-id="'+esc(fresh?row.item_id:row.version_id)+'" data-decision="approve">Одобрить</button>'+
            '<button class="btn" data-review="'+(fresh?"items":"versions")+'" data-id="'+esc(fresh?row.item_id:row.version_id)+'" data-decision="reject">Отклонить</button></article>';
        }).join(""):'<p>Очередь модерации пуста.</p>')+'</details>';
      adminSlot.querySelectorAll("[data-review]").forEach(button=>button.addEventListener("click",async()=>{
        button.disabled=true;
        try{await api("/api/admin/"+button.dataset.review+"/"+encodeURIComponent(button.dataset.id)+"/review",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({decision:button.dataset.decision})});await loadAdmin();await refresh()}
        catch(error){message(error.message,true);button.disabled=false}
      }));
    }catch(error){adminSlot.innerHTML='<p class="error">'+esc(error.message)+'</p>'}
  }
  async function refresh(){
    if(!API){
      renderAccount();message("Для включения аккаунтов и загрузок нужно развернуть Cloudflare Worker, создать D1/R2 и настроить GitHub OAuth. Инструкция находится в marketplace/worker/README.md.");
      grid.innerHTML='<div class="market-empty-state"><b>Marketplace API ещё не подключён</b><p>Интерфейс каталога готов. GitHub Pages не принимает пользовательские файлы, поэтому для реальных загрузок нужен API и файловое хранилище.</p><a class="btn" href="'+GH+'/tree/main/marketplace/worker">Backend и инструкция ↗</a></div>';return;
    }
    try{const me=await api("/api/me");user=me.user||null}catch{user=null;token="";sessionStorage.removeItem(TOKEN_KEY)}
    renderAccount();renderUpload();await loadAdmin();
    const params=new URLSearchParams({limit:"48"});if(kind!=="all")params.set("kind",kind);if(search.value.trim())params.set("q",search.value.trim());
    try{const data=await api("/api/catalog?"+params.toString());renderItems(data.items||[]);const slug=new URLSearchParams(location.search).get("item");if(slug)await openItem(slug);else detail.innerHTML=""}
    catch(error){grid.innerHTML='<div class="notice">'+esc(error.message)+'</div>'}
  }
  document.querySelectorAll(".market-tab").forEach(button=>button.addEventListener("click",()=>{
    kind=button.dataset.kind||"all";document.querySelectorAll(".market-tab").forEach(x=>x.classList.toggle("active",x===button));refresh();
  }));
  let timer=null;search.addEventListener("input",()=>{clearTimeout(timer);timer=setTimeout(refresh,220)});
  const ticket=new URLSearchParams(location.hash.replace(/^#/,"")).get("ticket");
  if(ticket&&API){
    history.replaceState(null,"",location.pathname+location.search);message("Завершаем вход через GitHub…");
    try{const result=await api("/auth/exchange",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({ticket})});token=result.token;user=result.user;sessionStorage.setItem(TOKEN_KEY,token);message("Вход выполнен как @"+user.login)}
    catch(error){message(error.message,true)}
  }
  await refresh();
};
