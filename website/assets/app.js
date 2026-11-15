(()=>{"use strict";const O="wyou25f",R="kotlinscriptloader",GH="https://github.com/"+O+"/"+R,API="https://api.github.com/repos/"+O+"/"+R,$=s=>document.querySelector(s),app=$("#app"),esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c])),dt=s=>s?new Date(s).toLocaleString("ru-RU",{dateStyle:"medium",timeStyle:"short"}):"",sh=s=>(s||"").slice(0,7);let SITE_DATA=null;async function loadStatic(name){let r=await fetch("./"+name,{cache:"no-store"});if(!r.ok)throw Error(name+" HTTP "+r.status);return r.json()}async function get(p){if(!SITE_DATA){let r=await fetch("./data.json",{cache:"no-store"});if(!r.ok)throw Error("Site data HTTP "+r.status);SITE_DATA=await r.json()}if(p==="")return SITE_DATA.repo;if(p.startsWith("/branches"))return SITE_DATA.branches;if(p.startsWith("/releases/latest"))return SITE_DATA.release;if(p.startsWith("/commits?"))return SITE_DATA.commits;throw Error("Unknown site data request: "+p)}function ext(u,t){return '<a class="small-link" href="'+u+'" target="_blank" rel="noreferrer">'+t+' ↗</a>'}function feat(i,t,d){return '<article class="card"><div class="icon">'+i+'</div><h3>'+t+'</h3><p>'+d+'</p></article>'}function shell(k,t,d,b){app.innerHTML='<div class="container"><section class="page-hero"><span class="eyebrow">'+k+'</span><h1 class="page-title">'+t+'</h1><p>'+d+'</p></section><section class="content">'+b+'</section></div>'}
async function home(){let bs=[],repo=null,rel=null;try{[bs,repo,rel]=await Promise.all([get("/branches"),get(""),get("/releases/latest").catch(()=>null)])}catch(e){}app.innerHTML='<div class="container"><section class="hero"><div><span class="eyebrow">KOTLIN SCRIPTING FOR PAPER</span><h1>Build more.<br><span class="gradient">Restart less.</span></h1><p class="lead">KotlinScriptLoader — платформа для создания серверных возможностей Minecraft на Kotlin. Пиши .kts-скрипты, расширяй сервер и управляй кодом без постоянных перезапусков.</p><div class="actions"><a class="btn primary" href="./download.html">↓ Скачать KSL</a><a class="btn" href="./docs.html">Документация →</a><a class="btn" href="'+GH+'">Исходный код ↗</a></div><div class="stats"><div class="stat"><strong>'+bs.length+'</strong><span>публичные ветки</span></div><div class="stat"><strong>'+(repo?repo.stargazers_count:"—")+'</strong><span>GitHub stars</span></div><div class="stat"><strong>'+(rel?esc(rel.tag_name):"нет")+'</strong><span>последний релиз</span></div></div></div><div class="terminal"><div class="eyebrow">HELLO.KTS</div><pre><span class="muted">// Your server. Your logic. Kotlin.</span>\n<span style="color:#b9acff">onEvent</span>&lt;PlayerJoinEvent&gt; {\n  player.sendRichMessage(\n    <span class="ok">"&lt;green&gt;Welcome!&lt;/green&gt;"</span>\n  )\n}\n\n<span class="muted">› /ksl reload</span>\n<span class="ok">✓ Script loaded successfully</span></pre></div></section><section class="section"><span class="eyebrow">WHY KSL</span><h2>Инструменты для твоего сервера</h2><div class="grid">'+feat("⌘","Kotlin .kts","Серверная логика на Kotlin DSL.")+feat("↻","Hot reload","Перезагрузка скриптов без полного рестарта.")+feat("◈","Lifecycle","Управление listeners, tasks, commands и GUI.")+feat("▤","Данные и базы","Конфигурации, SQLite, MySQL и другие форматы.")+feat("⌁","Расширяемость","Публичный API, аддоны и интеграции.")+feat("⚙","Диагностика","Ошибки, диагностика и runtime-инструменты.")+'</div></section><section class="section"><span class="eyebrow">GET STARTED</span><h2>С чего начать</h2><div class="grid"><article class="card"><h3>01 · Выбери ветку</h3><p>Сравни основную и compatibility-ветки.</p><a class="card-link" href="./branches.html">Все ветки →</a></article><article class="card"><h3>02 · Установи плагин</h3><p>Скачай релиз и проверь требования.</p><a class="card-link" href="./download.html">Загрузки →</a></article><article class="card"><h3>03 · Напиши скрипт</h3><p>Открой документацию и создай .kts-файл.</p><a class="card-link" href="./docs.html">Документация →</a></article></div></section></div>'}
async function about(){shell("ABOUT KSL",'Что такое <span class="gradient">KotlinScriptLoader?</span>',"KSL — Kotlin scripting plugin и runtime-платформа для Paper Minecraft серверов.",'<div class="grid">'+feat("⌘","Скрипты на Kotlin","Серверная логика в .kts-файлах с Bukkit/Paper DSL.")+feat("↻","Быстрая итерация","Компиляция и перезагрузка скриптов без полного рестарта.")+feat("◈","Lifecycle management","Управление listeners, tasks, commands, placeholders и GUI.")+feat("▤","Data APIs","Конфигурации, SQL, SQLite, MySQL, YAML, TOML и JSON.")+feat("⌁","Addon API","Публичные точки расширения и интеграции.")+feat("⚙","Runtime tools","Ошибки, диагностика и runtime-инструменты.")+'</div><div class="notice">KSL выполняет скрипты внутри JVM процесса Minecraft-сервера. Sandbox не является полной изоляцией недоверенного кода. Разрешай доступ к scripts/ только доверенным людям.</div><h2>Требования основной ветки</h2><div class="card"><b>Paper 1.21.1</b> · <b>Java 21</b> · PowerShell 7+ для build-скриптов. <p class="muted">У compatibility-веток требования отличаются.</p></div>')}
async function branches(){shell("BRANCH DIRECTORY","Ветки и <span class=\"gradient\">совместимость</span>","Список веток и их последние коммиты обновляются во время публикации сайта.",'<div id="branch-list" class="loading">Получаем ветки…</div>');try{let bs=await get("/branches?per_page=100"),info={"main":["MAIN","Основная разработка KSL, новые функции и главный релизный поток.","Paper 1.21.1 · Java 21"],"compat/mc-1.16.5-1.21":["COMPAT","Совместимость Minecraft 1.16.5–1.21 со своим build и workflow.","JVM 16 bytecode"],"compat/mc-1.18-plus":["COMPAT","Совместимость Minecraft 1.18+ со своим build и workflow.","JVM 17 bytecode"]},cards=bs.map(b=>{let d=info[b.name]||["BRANCH","Дополнительная ветка репозитория.","См. файлы ветки"],sha=b.commit?.sha||"";return '<article class="card branch"><div><span class="pill">'+d[0]+'</span><h3 class="branch-name">'+esc(b.name)+'</h3><p>'+d[1]+'</p><div class="meta">'+d[2]+'</div><div class="commit">'+(sha?'<a href="'+GH+'/commit/'+sha+'">Последний коммит '+sh(sha)+'</a>':'Коммит недоступен')+'</div></div><a class="btn" href="'+GH+'/tree/'+b.name+'">Открыть ветку ↗</a></article>'});$("#branch-list").outerHTML='<div class="grid">'+cards.join("")+'</div><div class="notice">Данные загружены из снимка репозитория, подготовленного при публикации сайта.</div>'}catch(e){$("#branch-list").innerHTML='Не удалось загрузить данные сайта. '+ext(GH+"/branches","Открыть список веток на GitHub")}}
async function download(){shell("DOWNLOAD CENTER",'Загрузки <span class="gradient">KSL</span>',"Стабильный релиз для сервера, самая свежая успешная сборка main и отдельные сборки compatibility-веток.",'<div id="download-content" class="loading">Подготавливаем список сборок…</div>');try{await get("");let release=SITE_DATA.release,builds=SITE_DATA.builds||{},formatSize=n=>n?((n/1048576).toFixed(1)+" MB"):"",buildCard=(key,title,target)=>{let b=builds[key];if(!b)return '<article class="download-card"><div class="download-topline"><span class="pill">BUILD</span></div><h3>'+title+'</h3><p>'+target+'</p><p class="download-muted">Успешная сборка пока не найдена.</p><a class="btn" href="'+GH+'/actions">Открыть GitHub Actions ↗</a></article>';let a=b.artifact,download=a&&a.site_download_url?'<a class="btn primary" href="'+esc(a.site_download_url)+'">↓ Скачать JAR</a>':'<a class="btn" href="'+esc(b.html_url)+'">Открыть сборку ↗</a>',expiry=a&&a.sha256?'<div class="download-expiry">SHA-256: <code>'+esc(a.sha256.slice(0,16))+'…</code> · <a href="'+esc(a.checksum_url)+'">полная сумма</a></div>':'<div class="download-expiry">Лог сборки и артефакты доступны на GitHub Actions</div>';return '<article class="download-card"><div class="download-topline"><span class="pill">SUCCESS</span><span class="download-kind">'+esc(target)+'</span></div><h3>'+esc(title)+' · #'+esc(b.run_number)+'</h3><p class="download-muted">Коммит <code>'+esc(sh(b.sha))+'</code> · '+dt(b.created_at)+'</p><div class="download-facts"><span>'+ (a?formatSize(a.jar_size_in_bytes||a.size_in_bytes):"Артефакт не найден") +'</span><span>GitHub Actions</span></div><div class="download-buttons">'+download+'<a class="btn" href="'+esc(b.html_url)+'">Лог сборки ↗</a></div>'+expiry+'</article>'};let stable=release?'<article class="download-card download-card-featured"><div class="download-topline"><span class="pill">STABLE RELEASE</span><span class="download-kind">Рекомендуется для сервера</span></div><div class="download-version">'+esc(release.tag_name)+'</div><h3>'+esc(release.name||release.tag_name)+'</h3><p class="download-muted">Опубликован '+dt(release.published_at)+'</p><div class="download-buttons">'+((release.assets||[]).filter(a=>/\.jar$/i.test(a.name)).map(a=>'<a class="btn primary" href="'+esc(a.browser_download_url)+'">↓ Скачать '+esc(a.name)+'</a>').join("")||'<a class="btn primary" href="'+esc(release.html_url)+'">Открыть релиз ↗</a>')+'<a class="btn" href="'+esc(release.html_url)+'">Описание релиза ↗</a></div><div class="download-expiry">Постоянная ссылка из GitHub Releases · не временный CI-артефакт</div></article>':'<article class="download-card download-card-featured"><div class="download-topline"><span class="pill">STABLE RELEASE</span></div><h3>Стабильного релиза пока нет</h3><p class="download-muted">Проверь страницу Releases или используй успешную сборку ниже.</p><a class="btn" href="'+GH+'/releases">Все релизы ↗</a></article>';let html='<div class="download-page"><div class="download-main-grid">'+stable+buildCard("main","Последняя сборка main","Основная ветка · самая свежая успешная сборка")+'</div><section class="download-section"><div class="download-section-heading"><div><span class="eyebrow">COMPATIBILITY BUILDS</span><h2>Сборки других веток</h2><p class="download-muted">Выбирай сборку под диапазон версий Minecraft. Каждая собирается своим workflow.</p></div></div><div class="download-build-grid">'+buildCard("compat/mc-1.16.5-1.21","Minecraft 1.16.5–1.21","compat/mc-1.16.5-1.21")+buildCard("compat/mc-1.18-plus","Minecraft 1.18+","compat/mc-1.18-plus")+'</div></section><section class="download-section download-source"><div><span class="eyebrow">SOURCE ARCHIVES</span><h2>Исходники</h2><p class="download-muted">ZIP-архив содержит исходный код выбранной ветки, а не готовый JAR-плагин.</p></div><div class="download-select"><select class="select" id="branch"><option value="main">main — основная ветка</option><option value="compat/mc-1.16.5-1.21">compat/mc-1.16.5-1.21</option><option value="compat/mc-1.18-plus">compat/mc-1.18-plus</option></select><a class="btn" id="zip" href="'+GH+'/archive/refs/heads/main.zip">↓ Скачать исходники</a></div></section><div class="notice">JAR-файлы последних успешных сборок копируются на этот сайт автоматически. Для каждой ветки показывается своя последняя успешная сборка; стабильные версии хранятся отдельно в GitHub Releases.</div><div class="download-footer-links">'+ext(GH+"/actions/workflows/build.yml","Все сборки")+' · '+ext(GH+"/releases","Все стабильные релизы")+' · '+ext(GH+"/branches","Все ветки")+'</div></div>';$("#download-content").outerHTML=html;$("#branch").onchange=e=>$("#zip").href=GH+"/archive/refs/heads/"+e.target.value+".zip"}catch(e){$("#download-content").innerHTML='<p>Не удалось загрузить каталог сборок. '+ext(GH+"/actions","Открыть GitHub Actions")+'</p>'}}
async function docs(){shell("DOCUMENTATION",'Документация <span class="gradient">KSL</span>',"Руководства, API-справочники и практические рецепты проекта.",'<div class="grid">'+[['BOOK I','Разработка скриптов','Kotlin, .kts, события, команды, scheduler, lifecycle, STD и runtime.','01-scripts.md'],['BOOK II','Плагины, аддоны и библиотеки','Gradle, KSLAPI, сервисы, imports, classloader, hooks и modules.','02-addons.md'],['BOOK III','Данные и базы данных','SQL, SQLite, MySQL, MariaDB, YAML, TOML, JSON и миграции.','03-data-and-databases.md'],['BOOK IV','Практические рецепты','Команды, игровые механики, инвентари, БД и интеграции.','04-cookbook.md'],['API','NBT API','Данные предметов, сущностей и блоков.','05-nbt-api.md'],['API','Предметы и зачарования','Item builder и KSL Custom Enchantments.','06-items-and-enchantments.md'],['API','Звук, частицы и мобы','Звуковые последовательности, формы частиц и мобы.','07-sound-particles-and-mobs.md'],['API','STD, сообщения и Mixins','Цветные сообщения, короткие методы и KSL Mixins.','08-standard-library-mixins-and-messages.md']].map(x=>'<article class="card"><span class="pill">'+x[0]+'</span><h3>'+x[1]+'</h3><p>'+x[2]+'</p><a class="card-link" href="'+GH+'/blob/main/docs/'+x[3]+'">Открыть →</a></article>').join("")+'</div><div class="actions"><a class="btn primary" href="'+GH+'/tree/main/docs/site">Офлайн-библиотека</a><a class="btn" href="'+GH+'/blob/main/docs/README.md">Индекс документации ↗</a></div>')}
async function source(){
shell("SOURCE CODE",'Обозреватель <span class="gradient">исходников</span>',"Выбирай ветку, открывай папки и переходи к отдельным файлам. Дерево обновляется при публикации сайта.",'<div id="source-browser" class="loading">Загружаем дерево исходников…</div>');
try{
  const data=await loadStatic("source-tree.json");
  const branches=Object.keys(data.branches||{});
  if(!branches.length)throw Error("В снимке нет веток");
  const holder=$("#source-browser");
  holder.innerHTML='<div class="source-explorer"><div class="source-toolbar"><label class="history-field"><span>Ветка</span><select class="select" id="source-branch">'+branches.map(function(b){return '<option value="'+esc(b)+'">'+esc(b)+'</option>'}).join("")+'</select></label><label class="history-field source-search"><span>Поиск по файлам</span><input class="history-input" id="source-search" type="search" placeholder="Например, KSLWorkScheduler.kt"></label><a class="btn" id="source-open-branch" href="'+GH+'/tree/'+encodeURI(branches[0])+'" target="_blank" rel="noreferrer">Открыть на GitHub ↗</a></div><div class="source-meta" id="source-meta"></div><nav class="source-breadcrumb" id="source-breadcrumb" aria-label="Путь к папке"></nav><div class="source-list" id="source-list"></div><p class="source-footnote" id="source-footnote"></p></div>';
  const branchSelect=$("#source-branch"),search=$("#source-search"),meta=$("#source-meta"),breadcrumb=$("#source-breadcrumb"),list=$("#source-list"),foot=$("#source-footnote"),openBranch=$("#source-open-branch");
  const params=new URLSearchParams(location.search);
  const requested=params.get("branch");
  if(requested&&branches.includes(requested))branchSelect.value=requested;
  let currentPath="";
  function sizeLabel(n){
    if(!n)return "";
    if(n<1024)return n+" B";
    if(n<1048576)return (n/1024).toFixed(1)+" KB";
    return (n/1048576).toFixed(1)+" MB";
  }
  function branchPath(branch){return branch.split("/").map(encodeURIComponent).join("/")}
  function render(){
    const branch=branchSelect.value;
    const branchData=data.branches[branch]||{entries:[],truncated:false};
    const entries=branchData.entries||[];
    const query=search.value.trim().toLowerCase();
    openBranch.href=GH+"/tree/"+branchPath(branch)+(currentPath?"/"+currentPath.split("/").map(encodeURIComponent).join("/"):"");
    const base=GH+"/tree/"+branchPath(branch);
    meta.innerHTML='<span><b>'+esc(branch)+'</b></span><span>'+entries.filter(function(x){return x.type==="blob"}).length+' файлов</span><span>'+entries.filter(function(x){return x.type==="tree"}).length+' папок в дереве</span>';
    const parts=currentPath?currentPath.split("/").filter(Boolean):[];
    let crumbs='<button type="button" data-breadcrumb="">⌂ Корень</button>';
    let prefix="";
    parts.forEach(function(part){
      prefix=prefix?prefix+"/"+part:part;
      crumbs+='<span class="crumb-separator">/</span><button type="button" data-breadcrumb="'+esc(prefix)+'">'+esc(part)+'</button>';
    });
    breadcrumb.innerHTML=crumbs;
    if(query){
      const matches=entries.filter(function(item){return item.type==="blob"&&item.path.toLowerCase().includes(query)}).slice(0,150);
      list.innerHTML=matches.length?matches.map(function(item){
        const path=item.path.split("/").map(encodeURIComponent).join("/");
        const href=base+"/"+path;
        const raw="https://raw.githubusercontent.com/"+O+"/"+R+"/"+branchPath(branch)+"/"+path;
        return '<article class="source-row"><div class="source-row-main"><span class="source-file-icon">FILE</span><div><a class="source-file-name" href="'+href+'" target="_blank" rel="noreferrer">'+esc(item.path)+'</a><small>'+sizeLabel(item.size)+'</small></div></div><div class="source-row-actions"><a href="'+href+'" target="_blank" rel="noreferrer">Просмотр ↗</a><a href="'+raw+'" target="_blank" rel="noreferrer">Raw ↗</a></div></article>';
      }).join(""):'<div class="empty">Файлы по этому запросу не найдены.</div>';
      foot.textContent=matches.length>0?"Показаны совпадения по всему дереву выбранной ветки.":"Попробуй другое название или часть пути.";
      return;
    }
    const prefixPath=currentPath?currentPath+"/":"";
    const folders=new Map(),directFiles=[];
    entries.forEach(function(item){
      if(!item.path.startsWith(prefixPath))return;
      const rest=item.path.slice(prefixPath.length);
      if(!rest)return;
      const slash=rest.indexOf("/");
      if(slash>=0){
        const name=rest.slice(0,slash);
        folders.set(name,prefixPath+name);
      }else if(item.type==="tree"){
        folders.set(rest,item.path);
      }else{
        directFiles.push(item);
      }
    });
    const folderRows=Array.from(folders.entries()).sort(function(a,b){return a[0].localeCompare(b[0])}).map(function(entry){
      return '<button type="button" class="source-row source-folder" data-open-path="'+esc(entry[1])+'"><span class="source-row-main"><span class="source-folder-icon">DIR</span><span class="source-file-name">'+esc(entry[0])+'/</span></span><span class="source-row-hint">Открыть папку →</span></button>';
    });
    directFiles.sort(function(a,b){return a.path.localeCompare(b.path)});
    const fileRows=directFiles.map(function(item){
      const path=item.path.split("/").map(encodeURIComponent).join("/");
      const href=base+"/"+path;
      const raw="https://raw.githubusercontent.com/"+O+"/"+R+"/"+branchPath(branch)+"/"+path;
      return '<article class="source-row"><div class="source-row-main"><span class="source-file-icon">FILE</span><div><a class="source-file-name" href="'+href+'" target="_blank" rel="noreferrer">'+esc(item.path.split("/").pop())+'</a><small>'+sizeLabel(item.size)+'</small></div></div><div class="source-row-actions"><a href="'+href+'" target="_blank" rel="noreferrer">Просмотр ↗</a><a href="'+raw+'" target="_blank" rel="noreferrer">Raw ↗</a></div></article>';
    });
    const rows=folderRows.concat(fileRows);
    list.innerHTML=rows.length?rows.join(""):'<div class="empty">В этой папке нет файлов.</div>';
    foot.textContent=(branchData.truncated?"GitHub вернул неполное дерево; часть файлов может быть не показана. ":"")+(currentPath?"Текущая папка: "+currentPath:"Корень репозитория · выбери папку или файл.");
  }
  list.addEventListener("click",function(event){
    const button=event.target.closest("[data-open-path]");
    if(button){currentPath=button.getAttribute("data-open-path")||"";render()}
  });
  breadcrumb.addEventListener("click",function(event){
    const button=event.target.closest("[data-breadcrumb]");
    if(button){currentPath=button.getAttribute("data-breadcrumb")||"";search.value="";render()}
  });
  branchSelect.addEventListener("change",function(){currentPath="";search.value="";render()});
  search.addEventListener("input",render);
  render();
}catch(e){
  $("#source-browser").innerHTML='<p class="error">Не удалось загрузить дерево исходников. '+ext(GH+"/tree/main","Открыть репозиторий на GitHub")+'</p>';
}
}
async function changelog(){
shell("CHANGELOG",'История <span class="gradient">изменений</span>',"Ищи коммиты по веткам и датам, сравнивай добавленные и удалённые строки.",'<div id="changelog-app" class="loading">Готовим историю коммитов…</div>');
try{
  const data=await loadStatic("history.json");
  const branches=Object.keys(data.branches||{});
  if(!branches.length)throw Error("В снимке нет истории веток");
  const root=$("#changelog-app");
  root.innerHTML='<div class="changelog"><div class="history-filters"><label class="history-field"><span>Ветка</span><select class="select" id="history-branch">'+branches.map(function(b){return '<option value="'+esc(b)+'">'+esc(b)+'</option>'}).join("")+'</select></label><label class="history-field"><span>С даты</span><input class="history-input" id="history-from" type="date"></label><label class="history-field"><span>По дату</span><input class="history-input" id="history-to" type="date"></label><label class="history-field history-search"><span>Поиск по коммиту или автору</span><input class="history-input" id="history-query" type="search" placeholder="Сообщение, автор или SHA"></label><label class="history-field"><span>На странице</span><select class="select" id="history-limit"><option value="10">10</option><option value="20">20</option><option value="40">40</option></select></label><button type="button" class="btn history-reset" id="history-reset">Сбросить фильтры</button></div><div class="history-summary" id="history-summary"></div><div class="history-result-line" id="history-result-line"></div><div class="history-commits list" id="history-commits"></div><div class="history-pagination" id="history-pagination"></div><p class="history-footnote">В снимке сайта хранится до '+esc(data.limit_per_branch||40)+' последних коммитов каждой ветки. Для более старой истории открой полный список на GitHub.</p><div class="actions"><a class="btn" id="history-github-link" href="'+GH+'/commits/main" target="_blank" rel="noreferrer">Полная история на GitHub ↗</a></div></div>';
  const branch=$("#history-branch"),from=$("#history-from"),to=$("#history-to"),query=$("#history-query"),limit=$("#history-limit"),summary=$("#history-summary"),resultLine=$("#history-result-line"),commits=$("#history-commits"),pagination=$("#history-pagination"),github=$("#history-github-link");
  const params=new URLSearchParams(location.search);
  const requested=params.get("branch");
  if(requested&&branches.includes(requested))branch.value=requested;
  let page=1;
  function render(){
    const branchName=branch.value;
    const phrase=query.value.trim().toLowerCase();
    const start=from.value,end=to.value;
    let filtered=(data.branches[branchName]||[]).filter(function(c){
      const day=(c.date||"").slice(0,10);
      if(start&&day<start)return false;
      if(end&&day>end)return false;
      if(phrase&&!(String(c.message||"")+" "+String(c.author_name||"")+" "+String(c.author_login||"")+" "+String(c.sha||"")).toLowerCase().includes(phrase))return false;
      return true;
    });
    filtered.sort(function(a,b){return String(b.date||"").localeCompare(String(a.date||""))});
    const known=filtered.filter(function(c){return Number.isFinite(c.additions)&&Number.isFinite(c.deletions)});
    const added=known.reduce(function(n,c){return n+c.additions},0);
    const removed=known.reduce(function(n,c){return n+c.deletions},0);
    const perPage=Number(limit.value)||10;
    const pages=Math.max(1,Math.ceil(filtered.length/perPage));
    page=Math.min(page,pages);
    const first=(page-1)*perPage;
    const visible=filtered.slice(first,first+perPage);
    summary.innerHTML='<article class="history-stat"><span>Коммитов найдено</span><strong>'+filtered.length+'</strong></article><article class="history-stat"><span>Строк добавлено</span><strong class="diff-add">+'+added.toLocaleString("ru-RU")+'</strong></article><article class="history-stat"><span>Строк удалено</span><strong class="diff-del">−'+removed.toLocaleString("ru-RU")+'</strong></article>';
    resultLine.textContent=filtered.length?'Показаны '+(first+1)+'–'+Math.min(first+perPage,filtered.length)+' из '+filtered.length+' коммитов · статистика строк доступна для '+known.length:'По выбранным фильтрам ничего не найдено.';
    github.href=GH+"/commits/"+branchName;
    if(!visible.length){commits.innerHTML='<div class="empty">Измени фильтры или сбрось их, чтобы увидеть коммиты.</div>'}
    else{
      commits.innerHTML=visible.map(function(c){
        const message=String(c.message||"Без сообщения");
        const title=message.split(/\r?\n/)[0];
        const additions=Number.isFinite(c.additions)?'<span class="diff-add">+'+c.additions.toLocaleString("ru-RU")+'</span>': '<span class="diff-neutral">+—</span>';
        const deletions=Number.isFinite(c.deletions)?'<span class="diff-del">−'+c.deletions.toLocaleString("ru-RU")+'</span>':'<span class="diff-neutral">−—</span>';
        const files=Array.isArray(c.files)?c.files:[];
        const fileList=files.length?'<details class="commit-files"><summary>'+esc(c.changed_files||files.length)+' файлов изменено · показать список</summary><div class="commit-file-list">'+files.map(function(f){return '<div class="commit-file"><code>'+esc(f.filename)+'</code><span class="diff-add">+'+Number(f.additions||0).toLocaleString("ru-RU")+'</span><span class="diff-del">−'+Number(f.deletions||0).toLocaleString("ru-RU")+'</span><span class="file-status">'+esc(f.status||"modified")+'</span></div>'}).join("")+'</div></details>':'<div class="commit-files"><span class="diff-neutral">Список файлов недоступен</span></div>';
        const body=message.includes("\n")?'<details class="commit-message"><summary>Полное сообщение</summary><pre>'+esc(message)+'</pre></details>':'';
        return '<article class="commit-card"><div class="commit-card-top"><span class="commit-branch">'+esc(branchName)+'</span><a class="commit-sha" href="'+esc(c.html_url||GH+"/commit/"+c.sha)+'" target="_blank" rel="noreferrer">'+esc((c.sha||"").slice(0,8))+' ↗</a></div><h3><a href="'+esc(c.html_url||GH+"/commit/"+c.sha)+'" target="_blank" rel="noreferrer">'+esc(title)+'</a></h3><div class="commit-meta"><span>'+esc(c.author_name||c.author_login||"Автор неизвестен")+'</span><span>'+dt(c.date)+'</span><span>'+Number(c.changed_files||files.length||0)+' файлов</span></div><div class="commit-diff">'+additions+deletions+'</div>'+body+fileList+'</article>';
      }).join("");
    }
    pagination.innerHTML='<button type="button" class="btn" id="history-prev" '+(page<=1?'disabled':'')+'>← Назад</button><span>Страница '+page+' из '+pages+'</span><button type="button" class="btn" id="history-next" '+(page>=pages?'disabled':'')+'>Дальше →</button>';
    $("#history-prev").addEventListener("click",function(){if(page>1){page--;render()}});
    $("#history-next").addEventListener("click",function(){if(page<pages){page++;render()}});
  }
  [branch,from,to,query,limit].forEach(function(el){el.addEventListener(el===query?"input":"change",function(){page=1;render()})});
  $("#history-reset").addEventListener("click",function(){from.value="";to.value="";query.value="";limit.value="10";branch.value=branches.includes("main")?"main":branches[0];page=1;render()});
  render();
}catch(e){
  $("#changelog-app").innerHTML='<p class="error">Не удалось загрузить снимок истории. '+ext(GH+"/commits/main","Открыть историю на GitHub")+'</p>';
}
}

$(".menu")?.addEventListener("click",()=>document.querySelector(".site-header nav")?.classList.toggle("open"));let cur=location.pathname.split("/").pop()||"index.html";document.querySelectorAll(".site-header nav a").forEach(a=>{if(a.getAttribute("href")==="./"+cur)a.classList.add("active")});let fn={home,about,branches,download,docs,source,changelog}[app?.dataset.page]||home;fn().catch(e=>{app.innerHTML='<div class="container"><h1>Ошибка загрузки</h1><p class="error">'+esc(e.message)+'</p></div>'});
})();