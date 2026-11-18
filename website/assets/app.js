(()=>{"use strict";const O="wyou25f",R="kotlinscriptloader",GH="https://github.com/"+O+"/"+R,API="https://api.github.com/repos/"+O+"/"+R,$=s=>document.querySelector(s),app=$("#app"),esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c])),dt=s=>s?new Date(s).toLocaleString("ru-RU",{dateStyle:"medium",timeStyle:"short"}):"",sh=s=>(s||"").slice(0,7);let SITE_DATA=null;async function loadStatic(name){let r=await fetch("./"+name,{cache:"no-store"});if(!r.ok)throw Error(name+" HTTP "+r.status);return r.json()}async function get(p){if(!SITE_DATA){let r=await fetch("./data.json",{cache:"no-store"});if(!r.ok)throw Error("Site data HTTP "+r.status);SITE_DATA=await r.json()}if(p==="")return SITE_DATA.repo;if(p.startsWith("/branches"))return SITE_DATA.branches;if(p.startsWith("/releases/latest"))return SITE_DATA.release;if(p.startsWith("/commits?"))return SITE_DATA.commits;throw Error("Unknown site data request: "+p)}function ext(u,t){return '<a class="small-link" href="'+u+'" target="_blank" rel="noreferrer">'+t+' ↗</a>'}function feat(i,t,d){const paths={"⌁":'<path d="M4 8h8l3 8h5"/><path d="M4 16h5l3-8h8"/>',"◷":'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',"▤":'<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',"◇":'<path d="M12 3 21 12 12 21 3 12 12 3Z"/><path d="M8 12h8M12 8v8"/>',"✳":'<path d="M12 3v18M3 12h18M5.6 5.6l12.8 12.8m0-12.8L5.6 18.4"/>',"⌘":'<rect x="8" y="8" width="8" height="8" rx="2"/><path d="M8 3v5m8-5v5M8 16v5m8-5v5M3 8h5m8 0h5M3 16h5m8 0h5"/>',"◎":'<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><path d="m16 8 4-4"/>',"↻":'<path d="M20 7v5h-5"/><path d="M20 12a8 8 0 1 0 1 4"/>',"♙":'<path d="M12 3 20 7v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4Z"/><path d="m8 12 3 3 5-6"/>',"⌂":'<path d="m3 11 9-8 9 8v9H3z"/><path d="M9 20v-6h6v6"/>'};return '<article class="card"><div class="icon"><svg class="feature-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(paths[i]||paths["⌂"])+'</svg></div><h3>'+t+'</h3><p>'+d+'</p></article>'}function shell(k,t,d,b){app.innerHTML='<div class="container"><section class="page-hero"><span class="eyebrow">'+k+'</span><h1 class="page-title">'+t+'</h1><p>'+d+'</p></section><section class="content">'+b+'</section></div>'}
async function home(){
  let bs=[],repo=null,rel=null;
  const result=await Promise.allSettled([get("/branches"),get(""),get("/releases/latest")]);
  if(result[0].status==="fulfilled")bs=result[0].value||[];
  if(result[1].status==="fulfilled")repo=result[1].value;
  if(result[2].status==="fulfilled")rel=result[2].value;
  const branchStat=bs.length?String(bs.length):"—";
  const starStat=repo&&Number.isFinite(repo.stargazers_count)?repo.stargazers_count.toLocaleString("ru-RU"):"—";
  const releaseStat=rel&&rel.tag_name?esc(rel.tag_name):"пока нет";
  app.innerHTML='<div class="container home-page">'+
    '<section class="hero home-hero"><div class="home-hero-copy">'+
      '<span class="eyebrow">PAPER SERVER · KOTLIN SCRIPTING RUNTIME</span>'+
      '<h1>Добавляй возможности серверу.<br><span class="gradient">Пиши на Kotlin.</span></h1>'+
      '<p class="lead">KotlinScriptLoader (KSL) — Paper-плагин и runtime для .kts-скриптов. Создавай игровые механики, команды и автоматизацию прямо на сервере, используя Kotlin и API Bukkit/Paper — без отдельного JAR для каждой небольшой идеи.</p>'+
      '<div class="actions"><a class="btn primary" href="./download.html">↓ Скачать KSL</a><a class="btn" href="./docs.html">Начать с документации →</a><a class="btn" href="./about.html">Как это работает</a></div>'+
      '<div class="home-target"><i></i><span><b>Основная ветка:</b> Paper 1.21.1 · Java 21</span><a href="./branches.html">Другие линии совместимости ↗</a></div>'+
    '</div><div class="terminal home-code"><div class="terminal-head"><div class="terminal-dots"><i></i><i></i><i></i></div><span>scripts/hello.kts</span><b>KOTLIN</b></div><pre><span class="muted">// Первый скрипт KSL</span>\n<span class="code-purple">onEvent</span>&lt;PlayerJoinEvent&gt; {\n    player.sendRichMessage(\n        <span class="ok">"&lt;green&gt;Welcome, &lt;white&gt;"</span> +\n        player.name + <span class="ok">"&lt;/white&gt;!&lt;/green&gt;"</span>\n    )\n}\n\n<span class="muted">// В консоли сервера</span>\n<span class="code-cyan">/ksl reload</span>\n<span class="ok">✓ Скрипт загружен</span></pre><div class="terminal-foot"><span><i></i> .kts · Kotlin Script</span><span>Paper API context</span></div></div></section>'+
    '<section class="home-quickstart"><div><span class="eyebrow">QUICK START</span><h2>Три шага до первого скрипта</h2><p>Не нужно изучать весь API перед стартом — начни с небольшого сценария.</p></div><div class="home-quickstart-steps"><a href="./download.html"><b>01</b><span><strong>Установи KSL</strong><small>Выбери сборку под Minecraft и Java</small></span><i>→</i></a><a href="'+GH+'/blob/main/docs/01-scripts.md"><b>02</b><span><strong>Создай .kts-файл</strong><small>Посмотри минимальный пример и контекст</small></span><i>→</i></a><a href="./docs.html"><b>03</b><span><strong>Изучи нужный API</strong><small>Найди события, команды или базы данных</small></span><i>→</i></a></div></section>'+
    '<section class="home-live-stats"><div class="home-stat"><strong>'+branchStat+'</strong><span>публичные ветки</span></div><div class="home-stat"><strong>'+starStat+'</strong><span>GitHub stars</span></div><div class="home-stat"><strong>'+releaseStat+'</strong><span>последний релиз</span></div><a class="home-stat-link" href="'+GH+'/commits/main">История разработки ↗</a></section>'+
    '<section class="section home-intro"><div><span class="eyebrow">WHAT IS KSL</span><h2>Не отдельный язык.<br><span class="gradient">Kotlin внутри твоего сервера.</span></h2></div><div class="home-intro-copy"><p>KSL запускает Kotlin Script-файлы с расширением <code>.kts</code> в контексте Paper-сервера. Ты пишешь обычный Kotlin, а KSL предоставляет удобные точки входа для событий, команд, задач и серверных API.</p><p>Это подходит для функций, которые хочется быстро создать, проверить и изменить: от приветствия игрока до игровых систем. Для крупных библиотек и повторно используемой логики предусмотрены аддоны на Kotlin или Java.</p><a class="card-link" href="./about.html">Подробнее об архитектуре →</a></div></section>'+
    '<section class="section home-features"><div class="home-section-heading"><span class="eyebrow">WHAT YOU CAN BUILD</span><h2>Инструменты для реальных задач сервера</h2><p>Скрипты не ограничиваются сообщениями при входе. KSL объединяет игровые API и управление жизненным циклом кода.</p></div><div class="grid home-feature-grid">'+
      feat("⌁","События и команды","Обрабатывай события Bukkit/Paper, создавай команды, проверяй аргументы и permissions.")+
      feat("◷","Задачи и потоки","Запускай sync/async задачи, задержки и повторения; управляй ими и отменяй при выгрузке скрипта.")+
      feat("▤","Данные и конфигурация","Используй YAML, persist/table и SQL-хранилища SQLite, MySQL или MariaDB.")+
      feat("◇","Предметы и инвентари","Собирай ItemStack через builder, работай с GUI, PersistentDataContainer и NBT.")+
      feat("✳","Звук и частицы","Создавай последовательности звуков, формы частиц и игровые эффекты через DSL.")+
      feat("⌘","Аддоны и интеграции","Расширяй контекст скриптов, публикуй сервисы и подключай необязательные интеграции.")+
      feat("◎","Декларативная Automation","Загружай YAML/TOML расписания, вызывающие только заранее зарегистрированные обработчики.")+
      feat("↻","Accounts и сессии","Используй JDBC account store с PBKDF2, хешированными токенами и отдельными таблицами аддонов.")+
      feat("♙","Несколько SQL backends","Работай с SQLite, MySQL/MariaDB и PostgreSQL; экспериментальный PostEmberDB client пока loopback-only.")+
      feat("⌂","Диагностика и IDE","Проверяй скрипты, lifecycle и ошибки через doctor, runtime diagnostics и IntelliJ companion.")+
    '</div></section>'+
    '<section class="section home-lifecycle"><div class="home-section-heading"><span class="eyebrow">SCRIPT LIFECYCLE</span><h2>От файла до работающей механики</h2><p>Компиляция и выполнение связаны с управлением ресурсами скрипта.</p></div><div class="home-steps">'+
      '<article class="home-step"><span>01</span><h3>Напиши .kts</h3><p>Создай Kotlin-файл в папке scripts и используй контекст сервера.</p></article>'+
      '<article class="home-step"><span>02</span><h3>Скомпилируй</h3><p>Kotlin Scripting проверит исходник; компиляционная работа вынесена из главного потока.</p></article>'+
      '<article class="home-step"><span>03</span><h3>Загрузи</h3><p>Скрипт регистрирует события, команды, задачи и другие ресурсы.</p></article>'+
      '<article class="home-step"><span>04</span><h3>Обнови</h3><p>Перезагружай все скрипты или только изменённые; ресурсы старой версии очищаются при выгрузке.</p></article></div>'+
      '<div class="home-command-row"><code>/ksl reload</code><span>все скрипты</span><code>/ksl reload changed</code><span>только изменённые</span><code>/ksl validate</code><span>проверка без загрузки</span></div></section>'+
    '<section class="section home-paths"><div class="home-section-heading"><span class="eyebrow">CHOOSE YOUR WORKFLOW</span><h2>С чего начать</h2></div><div class="grid">'+
      '<article class="card home-path-card"><span class="pill">SERVER OWNER</span><h3>Хочу установить KSL</h3><p>Проверь версию Paper и Java, выбери подходящую ветку и скачай JAR.</p><a class="card-link" href="./download.html">Открыть загрузки →</a></article>'+
      '<article class="card home-path-card"><span class="pill">SCRIPT AUTHOR</span><h3>Хочу написать скрипт</h3><p>Начни с установки, структуры .kts-файлов, событий и первой команды.</p><a class="card-link" href="'+GH+'/blob/main/docs/01-scripts.md">Книга I · Скрипты ↗</a></article>'+
      '<article class="card home-path-card"><span class="pill">ADDON DEVELOPER</span><h3>Хочу расширить KSL</h3><p>Создай Kotlin/Java-плагин, подключи Public API и добавь сервис или собственный DSL.</p><a class="card-link" href="'+GH+'/blob/main/docs/02-addons.md">Книга II · Аддоны ↗</a></article></div></section>'+
    '<section class="section home-ide-promo"><div class="home-section-heading"><span class="eyebrow">BUILD WITH THE RIGHT TOOLS</span><h2>Пиши аддоны в удобной среде</h2><p>Для Kotlin рекомендуем IntelliJ IDEA. VS Code подойдёт как универсальный редактор и более лёгкая альтернатива.</p></div><div class="home-ide-mini-grid"><a href="./development.html" class="home-ide-mini"><img src="./assets/ide-intellij.svg" alt=""><span><b>IntelliJ IDEA</b><small>Лучший выбор для Kotlin</small></span></a><a href="./development.html" class="home-ide-mini"><img src="./assets/ide-vscode.svg" alt=""><span><b>Visual Studio Code</b><small>Гибкая альтернатива</small></span></a></div><a class="card-link" href="./development.html">Сравнить редакторы и настроить проект →</a></section>'+
    '<section class="home-bottom-cta"><div><span class="eyebrow">EXPLORE KSL</span><h2>Посмотри, как устроен проект</h2><p>Исходники, руководства, история коммитов и сборки совместимых линий — в одном месте.</p></div><div class="actions"><a class="btn primary" href="./download.html">Скачать</a><a class="btn" href="./source.html">Исходники</a><a class="btn" href="./changelog.html">Changelog</a></div></section>'+
    '<div class="notice home-safety"><b>Важно о безопасности.</b> Скрипты выполняются внутри JVM-процесса Minecraft-сервера. KSL не является изолированной средой для недоверенного кода. Доступ к папке scripts и загрузке скриптов следует давать только доверенным администраторам.</div>'+
  '</div>';
}
async function about(){
  const body=
    '<section class="about-overview"><div><span class="eyebrow">THE IDEA</span><h2>Серверная логика без отдельного плагина для каждой небольшой функции</h2><p>KotlinScriptLoader — Paper-плагин, который предоставляет runtime для выполнения Kotlin Script-файлов <code>.kts</code>. Он помогает быстро добавлять и менять серверную логику, сохраняя выразительность и типизацию Kotlin.</p><p>KSL не вводит собственный язык программирования. Скрипты пишутся на Kotlin и получают контекст Bukkit/Paper, DSL-функции и стандартную библиотеку KSL.</p></div><aside class="about-fact-panel"><span class="pill">MAIN BRANCH TARGET</span><strong>Paper 1.21.1</strong><strong>Java 21</strong><small>Для других версий смотри отдельные compatibility-ветки и их требования.</small><a class="card-link" href="./branches.html">Ветки и совместимость →</a></aside></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">HOW IT WORKS</span><h2>Как устроено выполнение скрипта</h2><p>От исходного файла до активных обработчиков — поэтапный процесс с управляемым жизненным циклом.</p></div><div class="about-pipeline">'+
      '<article><span>01 · SOURCE</span><h3>Файл .kts</h3><p>Исходник находится в папке данных KSL внутри plugins.</p></article><b>↓</b><article><span>02 · COMPILER</span><h3>Kotlin Scripting</h3><p>KSL подготавливает контекст и компилирует скрипт; компиляционная работа вынесена с главного потока.</p></article><b>↓</b><article><span>03 · CONTEXT</span><h3>BukkitScriptContext</h3><p>Скрипт получает доступ к серверному API, функциям DSL и STD KSL.</p></article><b>↓</b><article><span>04 · RUNTIME</span><h3>Управляемые ресурсы</h3><p>События, команды, задачи и GUI-сессии связаны с жизненным циклом скрипта.</p></article></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">CAPABILITIES</span><h2>Что входит в KSL</h2><p>Основные API сгруппированы по задачам разработки Paper-сервера.</p></div><div class="grid about-capability-grid">'+
      '<article class="card"><div class="icon">⌁</div><h3>События и команды</h3><p>Обработчики Bukkit/Paper events, команды, аргументы, permissions, сообщения и placeholders.</p></article>'+
      '<article class="card"><div class="icon">◷</div><h3>Планировщик и runtime</h3><p>Sync/async и повторяющиеся задачи, worker pool, task handles, диагностика и просмотр runtime-ресурсов.</p></article>'+
      '<article class="card"><div class="icon">▤</div><h3>Конфигурация и данные</h3><p>YAML/TOML/JSON, CSV, persist/table, SQL, SQLite, MySQL/MariaDB и PostgreSQL; JDBC Accounts и транзакции.</p></article>'+
      '<article class="card"><div class="icon">◇</div><h3>Предметы, GUI и NBT</h3><p>Item builder, GUI, PersistentDataContainer, detached/binary NBT, schematics и KSL Custom Enchantments.</p></article>'+
      '<article class="card"><div class="icon">✳</div><h3>Звук и частицы</h3><p>Звуковые presets, sound sequences, формы частиц и builders для мобов.</p></article>'+
      '<article class="card"><div class="icon">◈</div><h3>Текст и удобные API</h3><p>MiniMessage и legacy-цвета, ActionBar, Titles, Adventure Components и extension-методы.</p></article></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">DATA & AUTOMATION</span><h2>От надёжного хранения до декларативных задач</h2><p>Новые возможности KSL расширяют проект, не смешивая экспериментальные интеграции со стабильными backend.</p></div><div class="grid about-capability-grid"><article class="card"><img src="./assets/icon-data.svg" width="42" height="42" alt="" loading="lazy"><h3>JDBC и Accounts</h3><p>SQLite, MySQL/MariaDB и PostgreSQL; account store с PBKDF2, хешированными сессиями и изоляцией таблиц аддонов.</p></article><article class="card"><img src="./assets/icon-automation.svg" width="42" height="42" alt="" loading="lazy"><h3>YAML/TOML Automation</h3><p>Отложенные и повторяющиеся задачи, зарегистрированные action handlers, проверка конфигурации и отмена при unload.</p></article><article class="card"><img src="./assets/icon-runtime.svg" width="42" height="42" alt="" loading="lazy"><h3>PostEmberDB · экспериментально</h3><p>Loopback-only клиент к текущему in-memory TCP протоколу upstream. Без persistence и аутентификации — только для разработки, не production backend.</p></article></div><div class="notice"><b>Важно:</b> PostEmberDB остаётся отдельным проектом. KSL не изменяет и не вендорит его исходники; текущая интеграция — ограниченный экспериментальный клиент.</div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">RELOAD & CLEANUP</span><h2>Перезагрузка — часть архитектуры</h2></div><div class="about-two-col"><div><h3>Что делает KSL</h3><ul><li>Позволяет перезагрузить все скрипты или только изменённые.</li><li>Сравнивает содержимое файлов по SHA-256 при поиске изменений.</li><li>Отслеживает ресурсы, созданные через API скрипта.</li><li>Освобождает принадлежащие скрипту listeners, tasks, команды и другие ресурсы при выгрузке.</li><li>Предоставляет validate, doctor, errors и runtime-инструменты.</li></ul></div><div class="about-note"><b>Что это даёт?</b><p>Можно быстрее проходить цикл «изменил → проверил → загрузил», не собирая отдельный JAR для каждой небольшой правки.</p><p>Hot reload не отменяет правил Bukkit: игровые объекты и изменения мира нужно обрабатывать в корректном потоке сервера.</p></div></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">EXTENSIBILITY</span><h2>Скрипты для механик. Аддоны для платформы.</h2><p>У KSL несколько уровней расширения — выбирай по масштабу задачи.</p></div><div class="about-extension-grid">'+
      '<article class="card"><span class="pill">LEVEL 1</span><h3>.kts script</h3><p>Для механик конкретного сервера: события, команды, награды, конфигурация и автоматизация.</p><a class="card-link" href="'+GH+'/blob/main/docs/01-scripts.md">Руководство по скриптам ↗</a></article>'+
      '<article class="card"><span class="pill">LEVEL 2</span><h3>MesLib2 / libraries</h3><p>Для повторного использования функций между скриптами и общей логики.</p><a class="card-link" href="'+GH+'/blob/main/docs/01-scripts.md">Книга I · Runtime ↗</a></article>'+
      '<article class="card"><span class="pill">LEVEL 3</span><h3>Kotlin / Java addon</h3><p>Для собственных зависимостей, сервисов, расширения DSL, hooks и библиотек.</p><a class="card-link" href="'+GH+'/blob/main/docs/02-addons.md">Книга II · Addons ↗</a></article></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">ADMIN TOOLBOX</span><h2>Команды администратора</h2><p>Большинство управляющих команд требуют permission <code>ksl.admin</code>.</p></div><div class="about-command-table">'+
      '<div><code>/ksl reload</code><span>Перезагрузить все скрипты.</span></div><div><code>/ksl reload &lt;script-id&gt;</code><span>Перезагрузить один скрипт.</span></div><div><code>/ksl reload changed</code><span>Перезагрузить только изменённые скрипты.</span></div><div><code>/ksl validate</code><span>Проверить скрипты, не загружая их.</span></div><div><code>/ksl doctor</code><span>Запустить системную диагностику.</span></div><div><code>/ksl errors</code><span>Посмотреть недавние ошибки.</span></div><div><code>/ksl runtime</code><span>Посмотреть состояние runtime и worker-настроек.</span></div></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">PROJECT STRUCTURE</span><h2>Где находятся файлы</h2><p>После первого запуска KSL создаёт собственную папку данных.</p></div><div class="about-directory"><pre>plugins/\n└── KotlinScriptLoader/\n    ├── config.yml\n    ├── locale/\n    ├── scripts/\n    │   ├── hello.kts\n    │   └── features/\n    │       └── welcome.kts\n    └── ksl.db</pre><div><h3>Разделяй механику на понятные файлы</h3><p>Храни конфигурацию отдельно от кода, разбивай крупные механики на папки и используй отдельный аддон, когда логика становится самостоятельной библиотекой или сервисом.</p><a class="card-link" href="'+GH+'/blob/main/docs/01-scripts.md">Структура проекта и первый скрипт ↗</a></div></div></section>'+
    '<section class="section about-section"><div class="home-section-heading"><span class="eyebrow">SECURITY MODEL</span><h2>Важное ограничение безопасности</h2></div><div class="notice"><b>KSL не является песочницей для недоверенного кода.</b> Скрипты выполняются внутри JVM-процесса Minecraft-сервера и могут взаимодействовать с доступными им API и ресурсами. Не давай посторонним возможность загружать или менять .kts-файлы. Доступ к папке scripts должен быть только у доверенных разработчиков и администраторов.</div></section>'+
    '<section class="section about-section about-faq"><div class="home-section-heading"><span class="eyebrow">COMMON QUESTIONS</span><h2>Перед тем как начать</h2><p>Коротко о назначении KSL и границах его возможностей.</p></div><div class="about-faq-grid"><article><h3>KSL заменяет обычные Paper-плагины?</h3><p>Не обязательно. Скрипты удобны для быстрой серверной логики и часто меняемых механик. Большие самостоятельные системы и интеграции можно оформить отдельным аддоном.</p></article><article><h3>Нужно ли знать Kotlin?</h3><p>Да, базовое понимание Kotlin поможет писать и отлаживать скрипты. Документация содержит примеры и объясняет API KSL.</p></article><article><h3>Можно ли безопасно запускать чужие скрипты?</h3><p>Не следует считать скрипты изолированными. Запускай только код от доверенных авторов и ограничивай доступ к папке scripts.</p></article><article><h3>Как выбрать совместимую сборку?</h3><p>Сверь версии Minecraft, Paper и Java с описанием ветки. Для старых серверов используй отдельные compat-сборки.</p></article></div></section>'+
    '<section class="about-next"><div><span class="eyebrow">READY TO TRY?</span><h2>Выбери ветку, установи плагин и напиши первый скрипт.</h2><p>Начни с официальной загрузки и Книги I — там есть установка и рабочие примеры.</p></div><div class="actions"><a class="btn primary" href="./download.html">Скачать KSL</a><a class="btn" href="'+GH+'/blob/main/docs/01-scripts.md">Первая книга ↗</a><a class="btn" href="./branches.html">Ветки и совместимость</a></div></section>';
  shell("ABOUT KSL",'Знакомство с <span class="gradient">KotlinScriptLoader</span>',"Что это за проект, как выполняются .kts-скрипты, какие инструменты доступны и как расширять KSL под задачи сервера.",body);
}
async function branches(){
  shell("BRANCH DIRECTORY","Ветки и <span class=\"gradient\">совместимость</span>","Выбери линию разработки по версии Minecraft. У каждой ветки — отдельная сборка и свой набор требований.",'<div id="branch-list" class="loading">Получаем ветки и сборки…</div>');
  try{
    await get("");const bs=SITE_DATA.branches||[],builds=SITE_DATA.builds||[];
    const info={"main":{kind:"Основная разработка",description:"Главная линия KSL: новые функции, API и актуальные релизы.",target:"Paper 1.21.1",java:"Java 21",buildKey:"main",order:0},"compat/mc-1.16.5-1.21":{kind:"Совместимость",description:"Отдельная линия для серверов на поддерживаемом диапазоне Minecraft.",target:"Minecraft 1.16.5–1.21",java:"JVM bytecode 16",buildKey:"compat/mc-1.16.5-1.21",order:1},"compat/mc-1.18-plus":{kind:"Совместимость",description:"Отдельная линия для серверов Minecraft 1.18 и новее в пределах её API.",target:"Minecraft 1.18+",java:"JVM bytecode 17",buildKey:"compat/mc-1.18-plus",order:2}};
    const ordered=bs.slice().sort((a,b)=>(info[a.name]?.order??99)-(info[b.name]?.order??99)||a.name.localeCompare(b.name));
    const card=b=>{const d=info[b.name]||{kind:"Дополнительная ветка",description:"Отдельная ветка репозитория. Перед установкой проверь её README и build-конфигурацию.",target:"См. конфигурацию ветки",java:"См. build-файл",buildKey:b.name};const build=builds[d.buildKey],artifact=build&&build.artifact,sha=b.commit?.sha||"",path=b.name.split("/").map(encodeURIComponent).join("/"),targetId=d.buildKey==="main"?"build-main":d.buildKey==="compat/mc-1.16.5-1.21"?"build-compat116":d.buildKey==="compat/mc-1.18-plus"?"build-compat118":"",status=artifact?'<span class="branch-status is-ready">Сборка готова · #'+esc(build.run_number)+'</span>':build?'<span class="branch-status">Сборка найдена · артефакт недоступен</span>':'<span class="branch-status">Нет опубликованной сборки</span>';return '<article class="branch-card '+(b.name==="main"?"branch-card-primary":"")+'"><div class="branch-card-top"><span class="pill">'+(b.name==="main"?"MAIN · РЕКОМЕНДУЕТСЯ":d.kind.toUpperCase())+'</span>'+status+'</div><h3>'+esc(b.name)+'</h3><p>'+esc(d.description)+'</p><div class="branch-facts"><span><small>ЦЕЛЕВАЯ ВЕРСИЯ</small><b>'+esc(d.target)+'</b></span><span><small>JAVA / BYTECODE</small><b>'+esc(d.java)+'</b></span></div><div class="branch-card-meta">'+(sha?'<a href="'+GH+'/commit/'+esc(sha)+'" target="_blank" rel="noreferrer">Последний коммит '+sh(sha)+' ↗</a>':'<span>SHA коммита недоступен</span>')+(build?'<span>Сборка '+dt(build.created_at)+'</span>':'')+'</div><div class="branch-card-actions"><a class="btn '+(artifact?"primary":"")+'" href="'+(targetId?"./download.html#"+targetId:GH+"/tree/"+path)+'">'+(artifact?"Скачать сборку":"Подробнее о ветке")+' →</a><a class="btn" href="'+GH+'/tree/'+path+'" target="_blank" rel="noreferrer">Исходники ↗</a></div></article>'};
    const main=ordered.filter(b=>b.name==="main"),compat=ordered.filter(b=>b.name!=="main"),known=compat.filter(b=>info[b.name]),other=compat.filter(b=>!info[b.name]);
    $("#branch-list").outerHTML='<div class="branch-directory"><section class="branch-group"><div class="branch-group-heading"><div><span class="eyebrow">DEFAULT LINE</span><h2>Основная разработка</h2><p>Выбирай эту ветку, если начинаешь новый проект и используешь актуальную целевую версию.</p></div></div><div class="branch-main-grid">'+(main.length?main.map(card).join(""):'<div class="empty">Основная ветка не найдена в снимке репозитория.</div>')+'</div></section><section class="branch-group"><div class="branch-group-heading"><div><span class="eyebrow">MINECRAFT COMPATIBILITY</span><h2>Ветки совместимости</h2><p>Для существующих серверов на других версиях Minecraft. Сверяй версию Paper и Java перед установкой.</p></div></div><div class="branch-compat-grid">'+(known.length?known.map(card).join(""):'<div class="empty">Ветки совместимости не найдены.</div>')+'</div></section>'+(other.length?'<section class="branch-group"><div class="branch-group-heading"><div><span class="eyebrow">OTHER BRANCHES</span><h2>Другие ветки</h2></div></div><div class="branch-compat-grid">'+other.map(card).join("")+'</div></section>':'')+'<div class="branch-help"><div><b>Не уверен, какую ветку выбрать?</b><p>Сначала проверь версию Minecraft, Paper и Java на сервере, затем открой соответствующую карточку.</p></div><a class="btn" href="./download.html">Перейти к загрузкам →</a></div><p class="branch-footnote">Сведения о ветках и успешных сборках обновляются автоматически при публикации сайта.</p></div>';
  }catch(e){$("#branch-list").innerHTML='Не удалось загрузить данные сайта. '+ext(GH+"/branches","Открыть список веток на GitHub")}
}
async function download(){
  shell("DOWNLOAD CENTER",'Загрузки <span class="gradient">KSL</span>',"Выбери совместимую сборку KotlinScriptLoader для своей версии Minecraft.",'<div class="notice"><b>Статус разработки:</b> версия 1.2.2 находится в разработке и не опубликована как стабильный релиз. Для production используй опубликованный стабильный релиз; сборки GitHub Actions — тестовые артефакты, а не релиз.</div><div id="download-content" class="loading">Подготавливаем список загрузок…</div>');
  try{
    await get("");
    const release=SITE_DATA.release,builds=SITE_DATA.builds||{},ide=SITE_DATA.ide_plugin;
    const formatSize=n=>n?((n/1048576).toFixed(1)+" MB"):"";
    const releaseAssets=release?(release.assets||[]).filter(a=>/\.jar$/i.test(a.name)).sort((a,b)=>{
      const rank=n=>n.includes("-mc-1.16.5-1.21")?1:n.includes("-mc-1.18-plus")?2:0;
      return rank(a.name)-rank(b.name);
    }):[];
    const releaseTarget=a=>{
      if(a.name.includes("-mc-1.16.5-1.21"))return {label:"Paper 1.16.5–1.21.x",detail:"Java 16+ · широкая совместимая сборка"};
      if(a.name.includes("-mc-1.18-plus"))return {label:"Paper 1.18.2 и новее",detail:"Java 17+ · совместимая сборка для новых серверов"};
      return {label:"Paper 1.21.1",detail:"Java 21 · основная ветка KSL"};
    };
    const stable=release&&releaseAssets.length?(()=>{
      const options=releaseAssets.map((a,i)=>'<option value="'+i+'">'+esc(releaseTarget(a).label)+'</option>').join("");
      const first=releaseAssets[0],target=releaseTarget(first);
      const checksum=(release.assets||[]).find(a=>a.name===first.name+".sha256");
      return '<article class="download-release-card">'+
        '<div class="download-release-info"><div class="download-topline"><span class="pill">СТАБИЛЬНЫЙ РЕЛИЗ</span><span class="download-kind">Официальная версия</span></div>'+
        '<div class="download-version">'+esc(release.tag_name)+'</div><h2>KotlinScriptLoader</h2>'+
        '<p class="download-muted">Выбери сборку по версии Paper и установленной Java. Ссылка и контрольная сумма обновятся автоматически.</p>'+
        '<div class="download-release-meta"><span id="release-size">'+formatSize(first.size)+'</span><span>'+releaseAssets.length+' сборки</span><span>GitHub Releases</span></div>'+
        '<a class="card-link" href="'+esc(release.html_url)+'" target="_blank" rel="noreferrer">Что нового в '+esc(release.tag_name)+' →</a></div>'+
        '<div class="download-release-picker"><label for="release-variant">Версия Minecraft / Paper</label>'+
        '<select class="download-version-select" id="release-variant">'+options+'</select>'+
        '<div class="download-release-target" id="release-target"><strong>'+esc(target.label)+'</strong><span>'+esc(target.detail)+'</span></div>'+
        '<a class="btn primary download-release-button" id="release-download" href="'+esc(first.browser_download_url)+'">↓ Скачать плагин (.jar)</a>'+
        '<a class="download-checksum-link" id="release-checksum" '+(checksum?'href="'+esc(checksum.browser_download_url)+'"':'hidden')+'>Проверить SHA-256</a>'+
        '<span class="download-release-filename" id="release-filename">'+esc(first.name)+'</span></div></article>';
    })():'<article class="download-release-card"><div><span class="pill">СТАБИЛЬНЫЙ РЕЛИЗ</span><h2>Сборки релиза не найдены</h2><p class="download-muted">Открой официальный список файлов релиза, чтобы выбрать JAR вручную.</p><a class="btn" href="'+GH+'/releases">Все релизы ↗</a></div></article>';
    const buildCard=(key,title,target,id)=>{
      const b=builds[key];
      if(!b)return '<article class="download-card" id="'+id+'"><div class="download-topline"><span class="pill">BUILD</span></div><h3>'+title+'</h3><p>'+target+'</p><p class="download-muted">Успешная сборка пока не найдена.</p><a class="btn" href="'+GH+'/actions">Открыть GitHub Actions ↗</a></article>';
      const a=b.artifact,download=a&&a.site_download_url?'<a class="btn primary" href="'+esc(a.site_download_url)+'">↓ Скачать JAR</a>':'<a class="btn" href="'+esc(b.html_url)+'">Открыть сборку ↗</a>';
      const checksum=a&&a.sha256?'<div class="download-expiry">SHA-256: <code>'+esc(a.sha256.slice(0,16))+'…</code> · <a href="'+esc(a.checksum_url)+'">полная сумма</a></div>':'<div class="download-expiry">Лог сборки и артефакты доступны на GitHub Actions</div>';
      return '<article class="download-card" id="'+id+'"><div class="download-topline"><span class="pill">'+(a?"SUCCESS":"BUILD")+'</span><span class="download-kind">'+esc(target)+'</span></div><h3>'+esc(title)+' · #'+esc(b.run_number)+'</h3><p class="download-muted">Коммит <code>'+esc(sh(b.sha))+'</code> · '+dt(b.created_at)+'</p><div class="download-facts"><span>'+(a?formatSize(a.jar_size_in_bytes||a.size_in_bytes):"Артефакт не найден")+'</span><span>GitHub Actions</span></div><div class="download-buttons">'+download+'<a class="btn" href="'+esc(b.html_url)+'">Лог сборки ↗</a></div>'+checksum+'</article>';
    };
    const plugin=ide&&ide.site_download_url?'<article class="download-card download-card-plugin" id="ide-plugin"><div class="download-topline"><span class="pill">IDE PLUGIN</span><span class="download-kind">IntelliJ IDEA</span></div><div class="download-version">v'+esc(ide.version||"1.2.0")+'</div><h3>KSL IntelliJ Plugin</h3><p class="download-muted">Подсказки по KSL API, диагностика и предварительная проверка скриптов.</p><div class="download-facts"><span>'+formatSize(ide.size_in_bytes)+'</span><span>Сборка #'+esc(ide.run_number)+'</span><span>'+dt(ide.created_at)+'</span></div><div class="download-buttons"><a class="btn primary" href="'+esc(ide.site_download_url)+'">↓ Скачать ZIP</a>'+(ide.checksum_url?'<a class="btn" href="'+esc(ide.checksum_url)+'">SHA-256</a>':'')+'<a class="btn" href="'+esc(ide.html_url||GH+"/actions")+'">Лог сборки ↗</a></div><div class="download-expiry">Установка: IntelliJ IDEA → Settings → Plugins → Install Plugin from Disk.</div></article>':'<article class="download-card download-card-plugin" id="ide-plugin"><div class="download-topline"><span class="pill">IDE PLUGIN</span></div><h3>KSL IntelliJ Plugin</h3><p class="download-muted">Пока нет опубликованной сборки плагина.</p><a class="btn" href="'+GH+'/actions/workflows/ide-plugin.yml">Открыть сборки ↗</a></article>';
    const highlightItems=(release?.body||"").split("\n").filter(line=>line.startsWith("- **")).slice(0,3).map(line=>{const clean=line.slice(2).replaceAll("**","");const parts=clean.split(":");return {title:parts.shift().trim(),body:parts.join(":").trim()};});
    const highlights=highlightItems.length?'<section class="download-highlights"><div class="download-section-heading"><div><span class="eyebrow">RELEASE HIGHLIGHTS</span><h2>Что нового в '+esc(release.tag_name)+'</h2></div><a class="card-link" href="'+esc(release.html_url)+'">Все изменения ↗</a></div><div class="download-highlight-grid">'+highlightItems.map(item=>'<article class="download-highlight"><span class="download-highlight-mark">✦</span><div><h3>'+esc(item.title)+'</h3><p>'+esc(item.body)+'</p></div></article>').join("")+'</div></section>':'';
    const html='<div class="download-page">'+
      '<section class="download-hero"><div class="download-hero-copy"><span class="eyebrow">KOTLINSCRIPTLOADER · DOWNLOAD CENTER</span><h2>Твоя сборка KSL.<br><span class="gradient">Без лишних поисков.</span></h2><p>Официальные релизы, совместимые версии Minecraft и инструменты разработчика — в одном месте.</p><div class="download-hero-actions"><a class="btn primary" href="#stable-download">Выбрать сборку ↓</a><a class="download-hero-link" href="'+GH+'/releases">Все релизы ↗</a></div></div><div class="download-hero-mark"><div class="download-hero-logo">K</div><span>STABLE RELEASE</span><strong>'+esc(release?.tag_name||"KSL")+'</strong><small>'+(release?.published_at?("Опубликован "+dt(release.published_at)):"Последняя версия")+'</small></div></section>'+
      '<section class="download-section" id="stable-download"><div class="download-section-heading"><div><span class="eyebrow">SERVER PLUGIN</span><h2>Скачать KotlinScriptLoader</h2><p class="download-muted">Один список вместо отдельных кнопок для каждой сборки. Выбери версию Minecraft — ссылка обновится автоматически.</p></div></div>'+stable+'</section>'+highlights+
      '<section class="download-section"><div class="download-section-heading"><div><span class="eyebrow">LATEST CI BUILDS</span><h2>Последние тестовые сборки</h2><p class="download-muted">Свежие сборки веток из GitHub Actions. Для обычной установки предпочтителен стабильный релиз выше.</p></div></div><div class="download-build-grid">'+
      buildCard("main","Основная ветка","Paper 1.21.1 · main","build-main")+
      buildCard("compat/mc-1.16.5-1.21","Minecraft 1.16.5–1.21","compat/mc-1.16.5-1.21","build-compat116")+
      buildCard("compat/mc-1.18-plus","Minecraft 1.18+","compat/mc-1.18-plus","build-compat118")+
      '</div></section>'+
      '<section class="download-section"><div class="download-section-heading"><div><span class="eyebrow">DEVELOPER TOOLING</span><h2>Плагин для IntelliJ IDEA</h2><p class="download-muted">Автодополнение публичного API KSL и диагностика потенциально небезопасных Bukkit-вызовов.</p></div></div>'+plugin+'</section>'+
      '<section class="download-section download-source"><div><span class="eyebrow">SOURCE ARCHIVES</span><h2>Исходники KSL</h2><p class="download-muted">ZIP-архив содержит исходный код выбранной ветки, а не готовый JAR-плагин.</p></div><div class="download-select"><select class="select" id="branch"><option value="main">main — основная ветка</option><option value="compat/mc-1.16.5-1.21">compat/mc-1.16.5-1.21</option><option value="compat/mc-1.18-plus">compat/mc-1.18-plus</option></select><a class="btn" id="zip" href="'+GH+'/archive/refs/heads/main.zip">↓ Скачать исходники</a></div></section>'+
      '<div class="notice">Стабильные JAR берутся из GitHub Releases. CI-сборки и плагины для IDE публикуются отдельно после успешных проверок.</div><div class="download-footer-links">'+ext(GH+"/actions/workflows/build.yml","Сборки KSL")+' · '+ext(GH+"/actions/workflows/ide-plugin.yml","Сборки IDE-плагина")+' · '+ext(GH+"/releases","Релизы")+' · '+ext(GH+"/branches","Ветки")+'</div></div>';
    $("#download-content").outerHTML=html;
    const variant=$("#release-variant");
    if(variant)variant.onchange=e=>{
      const asset=releaseAssets[Number(e.target.value)];
      if(!asset)return;
      const checksum=(release.assets||[]).find(a=>a.name===asset.name+".sha256");
      $("#release-download").href=asset.browser_download_url;
      $("#release-size").textContent=formatSize(asset.size);
      $("#release-filename").textContent=asset.name;
      const target=releaseTarget(asset);
      $("#release-target").innerHTML='<strong>'+esc(target.label)+'</strong><span>'+esc(target.detail)+'</span>';
      const checksumLink=$("#release-checksum");
      if(checksum){checksumLink.href=checksum.browser_download_url;checksumLink.hidden=false}
      else{checksumLink.removeAttribute("href");checksumLink.hidden=true}
    };
    $("#branch").onchange=e=>$("#zip").href=GH+"/archive/refs/heads/"+e.target.value+".zip";
    if(location.hash){const target=document.querySelector(location.hash);if(target)setTimeout(()=>target.scrollIntoView({behavior:"smooth",block:"center"}),80)}
  }catch(e){$("#download-content").innerHTML='<p>Не удалось загрузить каталог сборок. '+ext(GH+"/actions","Открыть GitHub Actions")+'</p>'}
}
async function development(){
  const body = `
    <div class="ide-intro">
      <div class="ide-intro-copy">
        <span class="eyebrow">KSL DEVELOPER WORKFLOW</span>
        <h2>Инструменты для комфортной разработки</h2>
        <p>Выбирай среду под задачу: полноценная IDE для Kotlin-аддонов или лёгкий редактор для скриптов и конфигурации.</p>
      </div>
      <aside class="ide-intro-note">
        <span class="ide-note-label">НАШ ВЫБОР</span>
        <b>IntelliJ IDEA + KSL Plugin</b>
        <p>Самый полный набор для Kotlin: навигация, Gradle, диагностика и предварительная проверка скриптов.</p>
      </aside>
    </div>

    <div class="ide-tool-grid">
      <article class="ide-tool-card ide-tool-primary">
        <div class="ide-tool-top">
          <img src="./assets/ide-intellij.svg" alt="Логотип IntelliJ IDEA" width="52" height="52">
          <span class="pill">РЕКОМЕНДУЕМ</span>
        </div>
        <div class="ide-tool-heading"><span class="eyebrow">ПОЛНОЦЕННАЯ IDE</span><h2>IntelliJ IDEA</h2></div>
        <p>Основная среда для разработки Kotlin-аддонов и Java-плагинов. Даёт инструменты для работы со всем проектом, а не только с отдельным файлом.</p>
        <ul>
          <li>Поддержка Kotlin, Java и Gradle.</li>
          <li>Навигация, рефакторинг, анализ типов и отладка.</li>
          <li>KSL IntelliJ Plugin: подсказки API, диагностика и локальный Preflight.</li>
          <li>Можно подключить точные версии KSL, Paper и API зависимостей.</li>
        </ul>
        <div class="ide-tool-tags"><span>Kotlin</span><span>Gradle</span><span>KSL Plugin</span></div>
        <div class="ide-tool-actions">
          <a class="btn primary" href="https://www.jetbrains.com/idea/download/" target="_blank" rel="noopener noreferrer">Скачать IntelliJ IDEA ↗</a>
          <a class="btn" href="./download.html#ide-plugin">Плагин KSL</a>
        </div>
      </article>

      <article class="ide-tool-card ide-tool-light">
        <div class="ide-tool-top">
          <img src="./assets/ide-vscode.svg" alt="Логотип Visual Studio Code" width="52" height="52">
          <span class="pill">ЛЁГКАЯ АЛЬТЕРНАТИВА</span>
        </div>
        <div class="ide-tool-heading"><span class="eyebrow">РЕДАКТОР С РАСШИРЕНИЯМИ</span><h2>Visual Studio Code</h2></div>
        <p>Универсальный редактор для небольших проектов, KSL-скриптов и конфигурации. Kotlin-возможности зависят от расширений и настроенного classpath.</p>
        <ul>
          <li>Удобен для .kts, YAML, JSON и Markdown.</li>
          <li>Быстрый запуск и гибкая настройка.</li>
          <li>Поддержка Kotlin предоставляется расширениями.</li>
          <li>Официальный KSL IntelliJ Plugin здесь не работает.</li>
        </ul>
        <div class="ide-tool-tags"><span>Лёгкий редактор</span><span>Расширения</span><span>Конфигурация</span></div>
        <div class="ide-tool-actions">
          <a class="btn" href="https://code.visualstudio.com/Download" target="_blank" rel="noopener noreferrer">Скачать VS Code ↗</a>
        </div>
      </article>
    </div>

    <section class="ide-workflow">
      <div class="home-section-heading">
        <span class="eyebrow">ADDON DEVELOPMENT</span>
        <h2>Подготовка проекта: четыре шага</h2>
        <p>Главное правило — IDE и сервер должны использовать совместимые версии API.</p>
      </div>
      <div class="ide-workflow-steps">
        <article><span>01</span><h3>Создай Gradle-проект</h3><p>Выбери Kotlin/JVM и Java toolchain, подходящие целевой версии KSL и сервера.</p></article>
        <article><span>02</span><h3>Подключи API</h3><p>Добавь KSL и Paper как compileOnly. Подключай API сторонних плагинов только при необходимости.</p></article>
        <article><span>03</span><h3>Сверь версии</h3><p>Проверь classpath и версии зависимостей. Иначе подсказки IDE могут отличаться от окружения сервера.</p></article>
        <article><span>04</span><h3>Проверь аддон</h3><p>Запусти KSL Preflight, собери проект и проверь его на тестовом сервере. Preflight не заменяет компиляцию.</p></article>
      </div>
      <div class="actions">
        <a class="btn primary" href="${GH}/tree/main/ide/templates/addon-starter">Addon Starter Kit ↗</a>
        <a class="btn" href="${GH}/blob/main/docs/09-api-reference.md">KSL API Reference ↗</a>
        <a class="btn" href="./download.html#ide-plugin">Скачать KSL Plugin →</a>
      </div>
    </section>
    <div class="notice"><b>Совместимость:</b> KSL IntelliJ Plugin предназначен для IntelliJ IDEA. В VS Code он не устанавливается. Для корректной диагностики Kotlin-проекта настрой Gradle и зависимости.</div>`;
  shell("DEVELOPER TOOLS",'Среда <span class="gradient">разработки</span>',"Выбери удобный инструмент для Kotlin-аддонов, серверных .kts-скриптов и конфигурации KSL.",body);
}
async function docs(){
  const entries=[
    {kind:"BOOK I",title:"Разработка скриптов",desc:"Kotlin и .kts, события, команды, scheduler, lifecycle, STD, GUI, предметы, звук, частицы, мобы, Mixins и диагностика.",file:"01-scripts.md",tags:"kotlin script events commands scheduler lifecycle std gui items sound particles mobs mixins logging"},
    {kind:"BOOK II",title:"Аддоны и библиотеки",desc:"Gradle, KSLAPI, KSLAddonScope, сервисы, hooks, context extensions, Java/Kotlin interop, strict sandbox, IntelliJ и надёжность.",file:"02-addons.md",tags:"addon plugin gradle java api services hooks sandbox ide"},
    {kind:"BOOK III",title:"Данные и базы данных",desc:"JDBC, SQLite, MySQL/MariaDB, PostgreSQL, experimental PostEmberDB client, Accounts, транзакции, YAML/TOML/JSON, CSV, NBT и schematics.",file:"03-data-and-databases.md",tags:"database sql sqlite mysql postgresql postember accounts yaml toml json csv nbt schem"},
    {kind:"BOOK IV",title:"Практикум",desc:"Сквозные примеры команд, игровых механик, инвентаря, SQL, Accounts, Automation, PostEmberDB и production-проверки.",file:"04-cookbook.md",tags:"cookbook examples recipes gui inventory automation"}
  ];
  const cards=entries.map((x,i)=>'<a class="docs-card" data-search="'+esc((x.title+" "+x.desc+" "+x.tags+" "+x.kind).toLowerCase())+'" href="'+GH+'/blob/main/docs/'+x.file+'"><span class="docs-card-top"><span class="pill">'+x.kind+'</span><span class="docs-card-number">0'+(i+1)+'</span></span><h3>'+esc(x.title)+'</h3><p>'+esc(x.desc)+'</p><span class="docs-card-open">Открыть книгу <b>→</b></span></a>').join("");
  shell("FOUR BOOK LIBRARY",'Четыре книги. <span class="gradient">Весь KSL.</span>',"Полная документация собрана в четырёх последовательных книгах. Больше никаких разрозненных API-справочников: главы и примеры находятся в одном месте.",'<section class="docs-overview"><div><span class="eyebrow">ONE DOCUMENTATION SYSTEM</span><h2>От первого .kts до собственной библиотеки.</h2><p>Книги разделены по задачам: скрипты, разработка расширений, данные и готовые рецепты. Все главы также доступны в офлайн-читалке.</p></div><div class="docs-overview-count"><strong>04</strong><span>полные книги</span><small>runtime · addons · data · operations</small></div></section><div class="docs-toolbar"><label class="docs-search"><span>⌕</span><input id="docs-search" type="search" placeholder="Поиск: SQL, NBT, Automation, KSLAPI…" autocomplete="off"><kbd>/</kbd></label></div><div class="docs-results-line"><span id="docs-result-count">Показаны все 4 книги</span><button id="docs-reset" type="button">Сбросить поиск</button></div><div class="docs-grid" id="docs-grid">'+cards+'</div><section class="docs-footer-panel"><div><span class="eyebrow">READ ONLINE OR OFFLINE</span><h2>Одна библиотека для сайта и компьютера.</h2><p>Офлайн-читалка содержит те же Markdown-источники, поиск по главам, оглавление, копирование кода и печать.</p></div><div class="actions"><a class="btn primary" href="./library/">Открыть библиотеку ↗</a><a class="btn" href="'+GH+'/tree/main/docs">Исходные Markdown ↗</a></div></section>');
  const input=$("#docs-search"),empty=$("#docs-empty"),count=$("#docs-result-count"),reset=$("#docs-reset"),cardsEls=[...$("#docs-grid").querySelectorAll(".docs-card")];
  const apply=()=>{const q=input.value.trim().toLowerCase();let visible=0;cardsEls.forEach(card=>{const show=!q||card.dataset.search.includes(q);card.hidden=!show;if(show)visible++;});count.textContent="Найдено: "+visible+" из 4";};
  input.addEventListener("input",apply);reset.addEventListener("click",()=>{input.value="";apply();input.focus();});
  document.addEventListener("keydown",e=>{if(e.key==="/"&&!e.ctrlKey&&!e.metaKey&&!e.altKey&&!/input|textarea/i.test(document.activeElement?.tagName||"")){e.preventDefault();input.focus();}if(e.key==="Escape"&&document.activeElement===input){input.value="";apply();input.blur();}});
}
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

$(".menu")?.addEventListener("click",()=>document.querySelector(".site-header nav")?.classList.toggle("open"));let cur=location.pathname.split("/").pop()||"index.html";document.querySelectorAll(".site-header nav a").forEach(a=>{if(a.getAttribute("href")==="./"+cur)a.classList.add("active")});let fn={home,about,branches,download,development,docs,source,changelog}[app?.dataset.page]||home;fn().catch(e=>{app.innerHTML='<div class="container"><h1>Ошибка загрузки</h1><p class="error">'+esc(e.message)+'</p></div>'});
})();