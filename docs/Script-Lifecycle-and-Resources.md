# Жизненный цикл скриптов и владение ресурсами

Это руководство описывает правила hot-reload: кто владеет задачей или обработчиком, кто обязан его остановить и как не оставить старую логику работать после выгрузки скрипта.

Для полного списка DSL-вызовов смотри [Script DSL Reference](Script-DSL-Reference.md), для addon callbacks — [Addon Development](Addon-Development-Guide.md), для потоков и worker pool — [Performance and Scaling](Performance-and-Scaling.md).

## 1. Главный принцип: ресурс должен иметь владельца

Каждый listener, scheduler task, динамическая команда, placeholder, подписка или внешний callback должен иметь понятного владельца и определённый момент освобождения.

В скриптах предпочитай KSL DSL вместо прямой регистрации ресурсов через Bukkit или сторонние API. KSL отслеживает ресурсы, созданные через поддерживаемый контекст, и снимает их при unload/reload соответствующего скрипта.

Не считай произвольный объект, созданный внутри скрипта, автоматически управляемым KSL. Сам факт, что объект создан в .kts, не означает, что KSL умеет его закрыть.

## 2. Что KSL отслеживает для скриптов

Поддерживаемые контекстные операции включают:

- onEvent<T> { ... } — обработчики Bukkit events;
- registerCommand(...), registerCommandAny(...) и aliases cmd(...), cmdAny(...);
- registerPlaceholder(...);
- runSync, runAsync, delay, every, delayAsync, everyAsync;
- scheduler aliases sync, async, after, repeat;
- worker, cpu, work и cancelWorker;
- subscribe(topic) { ... } — подписка на KSL event bus.

Задачи, зарегистрированные через KSL context, получают владельца-скрипт. При выгрузке скрипта KSL отменяет отслеживаемые Bukkit tasks и worker tasks, удаляет зарегистрированные команды и связанные обработчики/placeholder resources, а подписки, созданные скриптом, снимаются автоматически.

Используй эти точки входа вместо самостоятельного Bukkit.getScheduler() или ручного добавления глобальных listener'ов, когда эквивалентный KSL DSL доступен.

## 3. Пример: безопасный периодический обработчик

~~~kotlin
val taskId = every(ticks = 20L) {
    // Этот callback принадлежит текущему скрипту.
    // Bukkit API здесь выполняется scheduler'ом на основном потоке.
}

onEvent<PlayerJoinEvent> { event ->
    event.player.sendMessage("Добро пожаловать!")
}
~~~

Не создавай новый периодический task внутри каждого вызова события: это быстро создаёт дубликаты. Зарегистрируй его один раз при загрузке скрипта.

Если задача должна быть остановлена раньше reload, используй ID, возвращённый scheduler-вызовом:

~~~kotlin
val taskId = every(ticks = 20L) {
    // Периодическая работа
}
cancelTask(taskId)
~~~

Для worker pool worker(...) возвращает Long; для него используй cancelWorker(taskId).

## 4. Ошибки и выгрузка

- warn(message) сообщает о проблеме и продолжает текущую операцию.
- error(message) прерывает текущую операцию скрипта, но не останавливает сервер.
- fatal(message) инициирует фатальную ошибку текущего скрипта и его выгрузку с освобождением принадлежащих ему ресурсов.

Не используй fatal для обычной ошибки аргумента команды или ожидаемого отсутствия необязательного плагина. В таких случаях сообщи пользователю понятную причину и заверши только обработчик.

При ошибке и reload не рассчитывай на то, что незавершённая внешняя операция обязательно успеет завершиться. Worker code должен учитывать interruption и не запускать Bukkit-действия после того, как владелец уже выгружен.

## 5. Внешние ресурсы: собственная ответственность

KSL не может автоматически управлять любым ресурсом сторонней библиотеки. Если скрипт или addon самостоятельно создаёт:

- ExecutorService или поток;
- сетевой клиент с собственным lifecycle;
- стороннюю подписку/event listener;
- файловый watcher;
- таймер или callback в другом плагине;
- соединение/клиент, который не принадлежит HikariCP KSL,

то владелец должен сохранить handle и закрыть/отменить ресурс при выгрузке.

Не создавай бесконечные unmanaged threads. Предпочитай управляемые scheduler/worker API KSL и API, у которых есть явный close()/cancel().

Если внешний ресурс невозможно надёжно освободить при reload, не создавай его на уровне перезагружаемого скрипта: перенеси владение в addon с явным onUnload().

## 6. Addon lifecycle

Аддон — отдельный Paper plugin, реализующий KSLAddon. Его lifecycle отличается от lifecycle .kts:

1. KSL регистрирует addon и вызывает onLoad(api).
2. Addon регистрирует services, imports и context extensions.
3. При остановке addon должен освободить собственные ресурсы в onUnload().
4. Зарегистрированные addon services/imports/extensions должны сниматься симметрично их регистрации.

Не путай KSLAddon.onUnload() с выгрузкой одного .kts: addon обычно живёт дольше отдельных скриптов. Для script-specific ресурсов используй KSLContextExtension и callbacks контекста, а не глобальное состояние addon без очистки.

Подробные сигнатуры и примеры находятся в [Addon Development Guide](Addon-Development-Guide.md) и [Public API](Public-API.md).

## 7. Потоки и hot-reload

Hot-reload не делает Bukkit API thread-safe и не превращает асинхронную операцию в синхронную.

- Bukkit world/entity/player/inventory операции выполняй на основном потоке, если Paper API явно не говорит обратное.
- SQL, сеть и тяжёлые вычисления не запускай на main thread.
- Для фоновой работы передавай простые immutable данные (UUID, строки, числа, DTO), а не живые Bukkit объекты.
- Результат фоновой работы, который меняет Bukkit state, переноси через runSync { ... }.
- Не вызывай Future.get() или join() на главном потоке для долгой операции.

Смотри [Performance and Scaling](Performance-and-Scaling.md) и [Database Guide](Database-Guide.md).

## 8. Проверка перед публикацией

- [ ] Все KSL-managed listeners/tasks/commands/placeholders создаются через KSL DSL.
- [ ] Нет повторной регистрации в каждом event callback.
- [ ] Worker task ID сохраняется, если нужна ручная отмена.
- [ ] Внешние subscriptions/clients/watchers имеют явный cleanup.
- [ ] Нет неограниченных executor/thread pools.
- [ ] Bukkit API не вызывается из произвольного async callback.
- [ ] После /ksl reload старые callbacks больше не выполняют логику.
- [ ] Addon освобождает собственные ресурсы в onUnload().
- [ ] Ошибка одного скрипта не приводит к очистке ресурсов другого скрипта.

## Связанные документы

- [Script DSL Reference](Script-DSL-Reference.md)
- [Addon Development Guide](Addon-Development-Guide.md)
- [Public API](Public-API.md)
- [Performance and Scaling](Performance-and-Scaling.md)
- [Troubleshooting](Troubleshooting.md)
