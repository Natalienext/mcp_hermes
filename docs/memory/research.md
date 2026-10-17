# Research: модуль памяти для Hermes (ДЗ Л7–Л8)

- **Дата:** 2026-10-17
- **Вход:** `docs/memory/grill.md` (решения 1–17, допущения Д1–Д4, AC-1…AC-23); вопросы — задание 2 в `клод_агенты/ДЗ - 6.docx`.
- **Hermes:** `Hermes Agent v0.21.3 (2026.9.14) · upstream d3c012ff` (`hermes --version`), исходники `~/.hermes/hermes-agent` (коммит `d3c012ffd20f…`, 2026-09-19). Ниже пути Hermes даны от этого каталога.
- **Документация:** локальная копия той же версии `website/docs/…` и онлайн https://hermes-agent.nousresearch.com/docs/user-guide/features/hooks (сверено: строки каталога `pre_llm_call` / `post_llm_call` и фраза про `api_content` совпадают).
- **Пример:** `клод_агенты/example-memory/` (Go), ниже — `ex/`.
- **Что исследовано:** вход/выход shell-хука, куда попадает context, таймауты, прерванные ходы, что получает MCP-сервер, токенайзер FTS5 на русских словоформах, запись и recall в примере. **Не исследовано:** gateway/Telegram (вне рамок, grill «Вне рамок» п.1), векторный поиск, код Л6.

## Итог

Hermes вызывает shell-хук синхронно, отдельным процессом, один раз на `pre_llm_call` до цикла модели и один раз на `post_llm_call` после него. На stdin приходит JSON, где реплика лежит в `extra.user_message`. В `chat -q … -Q` это дословный текст `-q`, к нему применяется только `sanitize_surrogates`. Вывод `{"context": …}` дописывается в конец сообщения пользователя через `\n\n`, а не в system prompt.

Прерванный ход или ход без текста ответа `post_llm_call` не получает. MCP-сервер получает только имя tool и аргументы, без `session_id`/`turn_id`.

FTS5 `unicode61` основы слов не выделяет: «юбкой» не находит «юбки», «бюджета» не находит «бюджет». Пример обходит это обрезкой длинных слов и префиксным запросом. Для входа сценария S1 из grill пример находит 0 строк.

## Ключевые файлы

Hermes:
- `agent/shell_hooks.py` — мост `hooks:` → подпроцесс: payload, таймаут, разбор stdout.
- `hermes_cli/plugins_dispatch.py` — `invoke_hook`: второй, внешний таймаут колбэка и подавление после него.
- `agent/turn_context.py` — пролог хода: `turn_id`, вызов `pre_llm_call`, склейка context с репликой, sidecar `api_content`.
- `agent/turn_finalizer.py` — вызов `post_llm_call` и условия, при которых он не идёт.
- `cli.py`, `hermes_cli/cli_chat_turn_mixin.py` — откуда берётся `user_message` в `chat -q -Q` и в интерактивном чате.
- `agent/context_references.py`, `agent/skill_commands.py` — источники служебного текста вокруг реплики.
- `tools/mcp_tool_handlers.py`, `tools/mcp_tool_config.py` — вызов MCP tool и env stdio-сервера.
- `tools/hook_output_spill.py` — сброс слишком длинного context на диск.
- `website/docs/user-guide/features/hooks.md` — документация хуков.

Пример:
- `ex/adapter/hermes/{payload,plan,hook,envelope}.go` — разбор stdin, план pre/post, снятие обвязки.
- `ex/adapter/repository/sqlite/db.go` — схема `turns`, `facts`, `facts_fts` и триггеры.
- `ex/adapter/retriever/fts/{query,retriever}.go` — построение MATCH-запроса и поиск.
- `ex/domain/usecase/memory/usecase.go` — open/close хода, recall, remember, текущий ход.
- `ex/domain/entity/fact/recall.go` — формат строки recall.
- `ex/adapter/worker/worker.go` — очередь экстракции.
- `ex/adapter/mcpserver/server.go`, `ex/cmd/{hook,mcp}/main.go` — MCP-tools и env.

## Путь данных

### Поток 1: `pre_llm_call` (recall + открытие хода)

1. `hermes -p l78 chat -q "<вход>" -Q`: запрос берётся из argv (`cli.py:4571`, `_collect_query_images` `cli.py:2236-2255`). Он меняется, только если строка — путь к картинке (`cli.py:2242-2245`) или передан `--image`. Потом `_route_single_query_images` (`cli.py:4582`, `4270-4273`) и `run_conversation(user_message=effective_query, …)` без `persist_user_message` (`cli.py:4190-4192`).
2. `build_turn_context`: `sanitize_surrogates` (`agent/turn_context.py:922-925`) → `turn_id = "{session_id}:{task_id}:{8 hex}"` (`agent/turn_context.py:492-494`) → `original_user_message = persist_user_message ?? user_message` (`agent/turn_context.py:962`).
3. `_collect_pre_llm_call_context` (`agent/turn_context.py:682-737`, вызов `:1010`). Его аргументы: `session_id, task_id, turn_id, user_message, conversation_history, is_first_turn, model, platform, parent_session_id, sender_id` (`:693-705`).
4. `invoke_hook` добавляет `telemetry_schema_version` (`hermes_cli/plugins_dispatch.py:200-201`) и запускает колбэки по очереди (`:206-219`).
5. Колбэк shell-хука собирает stdin (`agent/shell_hooks.py:391-393`, `:79-94`). Верхний уровень: `hook_event_name, tool_name, tool_input, session_id, cwd, profile, extra`. В `extra` попадают все kwargs, кроме `tool_name, args, session_id, parent_session_id` (`:49`, `:93`): `task_id, turn_id, user_message, conversation_history` (весь список сообщений), `is_first_turn, model, platform, sender_id, telemetry_schema_version`. `parent_session_id` в payload нет; он подставляется в `session_id`, только если тот пуст (`:89`).
6. Hermes запускает подпроцесс (`agent/shell_hooks.py:312-318`). Env — копия `os.environ` без чистки секретов, если нет мультиплекса (`:309-314`; `tools/environments/local.py:344-354`).
7. Hermes разбирает stdout (`agent/shell_hooks.py:376-381`, `:426-444`). Принимается только JSON-объект со строкой `context`, непустой после `strip`. Пустой stdout → `None`. Не JSON → warning и `None`. Ненулевой код выхода → warning, stdout всё равно разбирается (`:373-377`).
8. Длинный context: больше `hooks.output_spill.max_chars` (по умолчанию 10 000) уходит на диск (`agent/turn_context.py:707-725`, `tools/hook_output_spill.py:24`).
9. Склейка: `content + "\n\n" + [блок prefetch встроенной памяти] + "\n\n" + context` (`agent/turn_context.py:81-95`). Результат записывается в `api_content` текущего сообщения (`:812-829`) и уходит в API (`:1116-1129`). System prompt не меняется (`:1164-1171`; docs `hooks.md:696`).
10. В истории сессии сообщение хранит чистый `content`, а `api_content` с context повторяется в последующих ходах этой же сессии (`agent/turn_context.py:1130-1137`; docs `hooks.md:698`). При `chat -q` каждый запуск — новая сессия.

Пример на этом шаге: `NewPlan` (`ex/adapter/hermes/plan.go:13-49`) → `HumanText` снимает обвязку (`ex/adapter/hermes/envelope.go:34-66`) → `GET /recall` → `POST /turns/open` (`ex/adapter/hermes/hook.go:55-77`, маршруты `ex/adapter/controller/rest/controller.go:28-33`). Затем печать `{"context": заголовок + строки}` (`hook.go:73-75`, `:123-130`).

`OpenTurn` переводит все открытые ходы пользователя в `pending` с пустым ответом (`ex/domain/usecase/memory/usecase.go:42-50`; `ex/domain/entity/turn/turn.go:59-66`) и создаёт новый `open`.

Recall: `min(limit, RECALL_LIMIT)` → `Retriever.Search(StatedOnly: true)` → строки `id | возраст | факт | «цитата≤40»` (`usecase.go:110-150`; `ex/domain/entity/fact/recall.go:11-13`, `:9`).

### Поток 2: `post_llm_call` (закрытие хода → экстракция)

1. `finalize_turn` вызывает `_apply_output_hooks`, только если `final_response and not interrupted` (`agent/turn_finalizer.py:531-535`). Внутри есть ещё условие `not agent._persist_disabled` (`:430-441`).
2. Аргументы: `session_id, task_id, turn_id, user_message` (тот же `original_user_message`), `assistant_response` (ответ после `transform_llm_output`), `conversation_history, model, platform` (`agent/turn_finalizer.py:432-441`). `sender_id` в `post_llm_call` нет.
3. Пример: `POST /turns {external_id: turn_id, assistant_text≤20000}` (`ex/adapter/hermes/hook.go:79-96`) → `Close`: `open → pending` (`ex/domain/entity/turn/turn.go:47-57`) → `wakeup.Notify()` (`usecase.go:97-107`) → ответ сразу.
4. Воркер: `drain` читает `turns WHERE status='pending'` из SQLite (`ex/adapter/worker/worker.go:55-69`; `ex/adapter/repository/sqlite/turn.go:103-104`) → `ProcessTurn` (`ex/domain/usecase/extraction/usecase.go:32`). `Run` вызывает `drain` при старте процесса (`worker.go:43-44`).

### Поток 3: MCP-tools

1. Hermes вызывает `server.session.call_tool(tool_name, arguments=args)` (`tools/mcp_tool_handlers.py:374`, обёртка `:516-541`). Поиск по обработчику полей `session_id`/`turn_id`/`_meta` в вызове не нашёл: сервер получает только имя и аргументы.
2. Env stdio-сервера: `PATH, HOME, USER, LANG, LC_ALL, TERM, SHELL, TMPDIR`, `XDG_*`, секреты внешнего источника и `env` из конфига сервера (`tools/mcp_tool_config.py:74`, `:111-133`).
3. Пример: `user_id` MCP берёт из env `USER_MEMORY_USER_ID` (`ex/cmd/mcp/main.go:15-16`, `ex/adapter/mcpserver/server.go:113`, `:133`, `:143`). Текущий ход для `memory_remember` — последний ход этого `user_id` в статусе `open` (`usecase.go:241-256`).

### Хранилище примера

- `facts`: `kind ∈ {preference, constraint, decision, background}`, `scope`, `fact`, `quote`, `standing ∈ {stated, inferred}`, `source_turn_id`, `source_session_id`, `ts`, `supersedes`, `status ∈ {active, superseded, forgotten}`, `forgotten_at`, `forgotten_in` + CHECK на пустой текст у забытых (`ex/adapter/repository/sqlite/db.go:34-52`).
- Одна действующая запись на тему: частичный уникальный индекс `facts(user_id, scope) WHERE status='active'` (`db.go:55`).
- `turns`: `status ∈ {open, pending, done, failed}`, `erase_requested`, `erased_at` (`db.go:16-29`).
- `facts_fts USING fts5(body, fact_id UNINDEXED, user_id UNINDEXED, tokenize='unicode61')`. Тело — `fact || ' ' || quote` с `ё→е`, вставляется только для `active`, удаляется при смене статуса (`db.go:60-73`).

## Проверка допущений grill

**Д2 (в CLI `extra.user_message` — чистый текст).** В пути `chat -q … -Q` в хук идёт дословная строка `-q` (шаги 1–2 потока 1). Голосовой префикс и одноразовые заметки `/model`, `/reload-skills` в `user_message` не попадают (`hermes_cli/cli_chat_turn_mixin.py:310-326`), но так устроен только интерактивный `chat()`. Там `@file:`/`@diff`/`@folder:` разворачиваются **до** выбора чистого текста (`cli_chat_turn_mixin.py:66`). В `user_message` попадает текст с `--- Attached Context ---` (`agent/context_references.py:228-230`). Вызов skill даёт префикс `[IMPORTANT: The user has invoked the …` (`agent/skill_commands.py`; маркеры снимает `ex/adapter/hermes/envelope.go:5-12`, `:67-81`). При картинках в нативном режиме `user_message` — список частей, не строка (`cli_chat_turn_mixin.py:186-194`; разбор в `ex/adapter/hermes/payload.go:45-67`). Заметки gateway идут в канал context, а не в `user_message` (`agent/turn_context.py:739-765`).

**Д3 (находит ли FTS5 «юбкой» по «юбки»).** Нет. Опыт на SQLite 3.51.0, `tokenize='unicode61'`, тело «Не носит юбки «я не ношу юбки»»:

| MATCH | строк |
|---|---|
| `юбкой` | 0 |
| `"юбкой"*` | 0 |
| `"юбк"*` | 2 |
| `юбки`, `ЮБКИ` | 2 |
| `бюджета` / `"бюдже"*` / `бюджет` (тело «Бюджет на наушники…») | 0 / 1 / 1 |
| `зеленый` по «зелёный» | 0 |
| `porter unicode61`: `юбкой` | 0 |
| `trigram`: `юбкой` / `юбк` | 0 / 1 |

`unicode61` приводит кириллицу к нижнему регистру, а `ё` не сводит к `е` (документация токенайзера: https://www.sqlite.org/fts5.html#unicode61_tokenizer). Пример обрезает слова от 6 букв на 2 буквы и ищет префиксом через `OR`, слова короче 3 букв и стоп-слова отбрасывает (`ex/adapter/retriever/fts/query.go:9-47`). «бюджета» (7) → `"бюдже"*` находит; «юбкой» (5) не обрезается → `"юбкой"*` не находит «юбки». Запрос примера для входа S1 из grill («Собери мне образ на свидание, можно с юбкой или платьем») против факта «Не носит юбки «я не ношу юбки, мне в них неуютно»» вернул 0 строк.

**Д4 (ход, ждущий экстракции при перезапуске, не будет разобран).** В примере очередь не в памяти процесса. Это статус `pending` в SQLite, и воркер разбирает его при старте (`ex/adapter/worker/worker.go:43-44`, `ex/adapter/repository/sqlite/turn.go:103-104`). Канал `Signal` с буфером 1 только будит воркер (`worker.go:14-27`). Прерванный (`Abandon`) ход тоже уходит в `pending` и разбирается с пустым ответом (`turn.go:59-66`). Как будет устроено у нас — вопрос design.

**Д1** — поведение модели-экстрактора; из кода не проверяется, grill относит проверку к S2.

## Ответы на вопросы задания 2

- **stdin в pre/post** — шаг 5 потока 1 и шаг 2 потока 2. Документация перечисляет те же поля (`website/docs/user-guide/features/hooks.md:446-447`, `:1699-1716`). `hooks test` для `post_llm_call` кладёт только `session_id, model, platform`, без `user_message`/`assistant_response`/`turn_id` (`hermes_cli/hooks.py:120`). Свои поля добавляются через `--payload-file` (`hermes_cli/hooks.py:178-191`).
- **Куда идёт context** — в конец сообщения пользователя текущего хода, после `\n\n`, и в `api_content` этой строки истории (шаги 9–10).
- **Таймаут — два уровня:**
  - У записи `hooks:` поле `timeout`: по умолчанию 60 с, максимум 300, меньше 1 → 60 (`agent/shell_hooks.py:37-38`, `:259-269`). По истечении — kill группы процессов и `None`, то есть пустой context (`:318-329`, `:358-361`).
  - Сам колбэк выполняется под `plugins.hook_callback_timeout`: 30 с по умолчанию (`hermes_cli/config_defaults.py:1629`, `hermes_cli/plugins.py:1109-1129`), `pre_llm_call` и `post_llm_call` входят в ограниченные (`hermes_cli/plugins_dispatch.py:41-45`). При этом таймауте колбэк подавляется на 60 с (`:52`, `:304-315`).
  - В `l78` сейчас `timeout: 5` у обоих событий (`~/.hermes/profiles/l78/config.yaml:258-259`).
  - Ход ждёт хук синхронно (`communicate`, `agent/shell_hooks.py:318`).
- **Прерванные ходы** — `post_llm_call` не вызывается при `interrupted` или пустом `final_response` (`agent/turn_finalizer.py:531`; docs: «Successful, non-interrupted turn finalization», `hooks.md:447`). Ctrl+C в `-q` → выход 130 (`cli.py:4193-4198`). Следующий `pre_llm_call` придёт с новым `turn_id`.
- **Что MCP-вызов передаёт серверу** — поток 3, шаг 1.
- **Токенайзер** — `unicode61` в примере (`ex/adapter/repository/sqlite/db.go:60`); «бюджета» по «бюджет» сам токенайзер не находит, запрос примера находит через `"бюдже"*` (Д3).

## Зависимости

- Hermes Agent v0.21.3, upstream `d3c012ff` — внешний; контракт хука: `agent/shell_hooks.py`, docs `hooks.md` §Shell Hooks. Сноска ДЗ про плагин подписки Claude требует Hermes ≥ 0.21.4 (`ДЗ - 6.docx`, сноска 0); установлена 0.21.3.
- SQLite FTS5 — проверено на системном `sqlite3` 3.51.0; пример использует `modernc.org/sqlite` (`ex/adapter/repository/sqlite/db.go:7`).
- MCP Python SDK внутри Hermes (`session.call_tool`, `tools/mcp_tool_handlers.py:374`).
- Профиль `l78` сейчас указывает на бинарники примера: `memhook` (`config.yaml:258-259`), `memory-mcp` с env `USER_MEMORY_URL`, `USER_MEMORY_USER_ID` (`:253-256`); `memory_enabled: false`, `user_profile_enabled: false` (`:99-101`); `disabled_toolsets: [memory, session_search]` (`:22`); `hooks_auto_accept: true` (`:260`).

## Различия примера и grill (материал для design)

- Роды: в примере есть `decision` (`db.go:37`), в grill его нет (Р2).
- Строка recall в примере без рода: `id | возраст | факт | «цитата»` (`recall.go:12`); в grill — `id | род | возраст | факт | «цитата»` (Р13).
- `memory_list` в примере запрашивает только `active` + `stated` (`ex/adapter/mcpserver/server.go:143`); в grill `inferred` виден в `memory_list` с пометкой (Р5, AC-9).
- Открытие хода: пример переводит старые открытые ходы в `pending` (разбираются экстрактором, `usecase.go:42-50`, `turn.go:59-66`); grill — «закрывается как прерванный» (Р15, AC-17), разбирать ли его, не записано.
- Recall примера ищет по `fact || quote` (`db.go:66`); grill — «по тексту факта и цитате» (Р13), совпадает.
- Таймаут HTTP хука в примере — 1 с по умолчанию (`ex/adapter/hermes/builder.go:18`, `:91-93`); grill — ≈1–2 с (Р9).

## Открытые вопросы

1. **Д3 / S1:** вход S1 и факт засева в разных словоформах («юбкой» и «юбки»): ни `unicode61`, ни запрос примера их не сводят. Что делать — менять входы сценариев (grill Р16 это допускает), токенизацию или запрос — решение design/человека.
2. **Д4:** в примере ход в `pending` переживает перезапуск; в grill Д4 предполагается очередь в памяти процесса (вне рамок п.3). Где у нас лежит очередь — решение design.
3. **AC-17:** прерванный ход разбирать экстрактором (как `Abandon` в примере) или нет — в grill не решено.
4. **Д2 вне `-Q`:** в интерактивном `chat` в `user_message` попадает развёрнутый `@`-контекст и префикс skill. В сценариях grill только `chat -q … -Q` (Р14), но снимать ли эту обвязку в хуке или сервисе — решение design.
5. **SQLite для Java:** есть ли FTS5 в выбранном JDBC-драйвере и какой его версии — в репозитории Java-кода памяти ещё нет, проверить не на чем.
6. **`conversation_history` в stdin:** хук получает всю историю сессии в `extra` (шаг 5 потока 1); размер stdin при длинной сессии не измерен.
7. **Затраты на хук:** Hermes ждёт хук синхронно; время старта fat jar под JDK на этой машине не измерено (grill Р9 опирается на оценку).

## Затраты фазы

- Минуты: ≈ 7 (2026-10-17, 12:31:39–12:38:23 UTC), 2 сообщения автора, 62 вызова модели.
- Токены: output 27 459; input 124; запись в кэш 156 678; чтение из кэша 8 482 778.
- Способ: как в grill.md — сумма поля `usage` ответов модели в журнале сессии `~/.claude/projects/…/3d2f0d8f-….jsonl` (подагентов не было), время — по первой и последней записи журнала, после завершения сессии.
