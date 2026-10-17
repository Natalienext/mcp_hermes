# Design: модуль памяти для Hermes (ДЗ Л7–Л8)

- **Дата:** 2026-10-17
- **Вход:** `docs/memory/grill.md` (Р1–Р17, AC-1…AC-23), `docs/memory/research.md`; задание 3 в `клод_агенты/ДЗ - 6.docx`.
- **Паттерны:** `wardrobe-api` — records с проверкой в compact constructor и порты в `domain/`, `@Service` use case в `application/`, адаптеры в `infrastructure/`, `@RestControllerAdvice`, `schema.sql`; `wardrobe-mcp` — `@McpTool`, `RestClient` в `client/`, ошибка → `CallToolResult.isError`, логи в файл.

## Обзор

Три новых Maven-модуля (Р7, Р9, Р10): сервис `wardrobe-memory` (Spring Boot, порт 8091, SQLite + FTS5, Р8) держит ходы, факты, recall, экстракцию и все проверки; `wardrobe-memory-hook` (чистый JDK) — мост Hermes → сервис; `wardrobe-memory-mcp` — tools `memory_remember`, `memory_forget`, `memory_list`. Корневой `pom.xml` получает три строки `<module>`; код `wardrobe-api`/`wardrobe-mcp` не меняется (AC-23).

### Решения design (закрывают открытые вопросы research)

| # | Вопрос research | Решение |
|---|---|---|
| D1 | Словоформы S1 (Д3) | Запрос FTS строит `FtsQueryBuilder`: нижний регистр, `ё→е`, слова от 5 букв обрезаются на 2 (основа ≥ 3), каждое слово — префикс `"…"*`, через `OR`, стоп-слова отброшены. «юбкой»→`"юбк"*` находит «юбки». Вход S1 не меняем; тест `FtsFactSearchTest.findsOtherWordForm`. |
| D2 | Где очередь экстракции (Д4) | В памяти процесса: однопоточный `ExecutorService`; статус `pending` в таблице `turn`. При старте `pending` не разбираются (grill «Вне рамок» п.3, Д4 остаётся верным). |
| D3 | Прерванный ход (AC-17) | Статус `abandoned`, конечный, экстрактор его **не** разбирает: ответа нет, Р12 допускает потерю. Факты, записанные в нём через `memory_remember`, остаются. |
| D4 | FTS5 в Java | `org.xerial:sqlite-jdbc` (встроенный SQLite собран с FTS5). Проверка — `SchemaTest` создаёт схему на временном файле; упал — веха 1 стоп. |
| D5 | Обвязка вне `-Q` (research в.4) | Не снимаем (Р14: только `chat -q -Q`). Хук берёт `extra.user_message`, только если это строка; иначе печатает пусто, ход не открывается. |
| D6 | Ход с «забудь» | Ход, в котором выполнен `memory_forget`, получает `forget_requested=1`, экстрактор его пропускает: иначе засев S3 («Забудь, что я не ношу юбки») снова записал бы `skirts`. |

## Модель сущностей

Пакет `org.example.wardrobe.memory.domain`, без Spring и JDBC.

### `Turn` (record) — ход

| Поле | Тип | Правило |
|---|---|---|
| `id` | `String` | `^t-\d{3,}$` |
| `externalId` | `String` | не пусто; `turn_id` Hermes; уникально |
| `userId` | `String` | `^[A-Za-z0-9_-]{1,64}$` |
| `sessionId` | `String` | не пусто |
| `userText` | `String` | 1…20000 символов; `""` только при `erasedAt != null` |
| `assistantText` | `String` | 0…20000; `""` в `open`, `abandoned`, `done`, `failed` |
| `status` | `TurnStatus` | `open \| pending \| done \| failed \| abandoned` |
| `failReason` | `String` | не пусто ⇔ `failed` |
| `forgetRequested` | `boolean` | — |
| `createdAt` | `Instant` | UTC |
| `erasedAt` | `Instant?` | — |

Переходы (методы `Turn`, иначе `MEM-STATE-007`):

| Метод | Из → в | Условие |
|---|---|---|
| `Turn.open(...)` | — → `open` | поля валидны |
| `close(assistantText)` | `open → pending` | длина ≤ 20000 |
| `abandon()` | `open → abandoned` | — |
| `markDone()` | `pending → done` | стирает `assistantText` |
| `markFailed(reason)` | `pending → failed` | стирает `assistantText` |
| `requestForget()` | `open`, флаг → true | — |
| `eraseUserText(now)` | любой | идемпотентно |
| `needsExtraction()` | — | `status == pending && !forgetRequested` |

### `Fact` (record) — факт

| Поле | Тип | Правило |
|---|---|---|
| `id` | `String` | `^f-\d{3,}$` (`MEM-VAL-009`) |
| `userId` | `String` | как у `Turn` (`MEM-VAL-001`) |
| `kind` | `FactKind` | `preference \| constraint \| background` (`MEM-VAL-002`) |
| `scope` | `String` | `^[a-z0-9]+(-[a-z0-9]+)*$`, ≤ 48 (`MEM-VAL-003`) |
| `text` | `String` | 1…200; `""` ⇔ `forgottenAt != null` (`MEM-VAL-004`) |
| `quote` | `String` | 1…200; `""` ⇔ `forgottenAt != null` (`MEM-VAL-005`) |
| `standing` | `Standing` | `stated \| inferred` (`MEM-VAL-007`) |
| `sourceTurnId`, `sourceSessionId` | `String` | не пусто |
| `createdAt` | `Instant` | UTC |
| `supersedes` | `String?` | `null` или `^f-\d{3,}$` |
| `status` | `FactStatus` | `active \| superseded \| forgotten` |
| `forgottenAt` | `Instant?` | не `null` при `forgotten` |
| `forgottenIn` | `String?` | session_id просьбы «забудь» |

Переходы: `active → superseded` (`supersededBy`), `active → forgotten` (`forget`); `superseded`, `forgotten` конечные. У `superseded` текст может быть стёрт (`erase`), статус не меняется.

### Инварианты

| # | Инвариант | Где держится | Тест |
|---|---|---|---|
| I1 | Цитата дословно есть в `userText` хода-источника, не в `assistantText` (AC-1, AC-2, AC-3) | `Fact.create(...)` → `QuoteMatcher.contains(source.userText(), quote)` (`domain/Fact.java`, `domain/QuoteMatcher.java`); иначе `MEM-VAL-006` | `FactTest`, `QuoteMatcherTest` |
| I2 | Формат полей, тема — ключ (AC-12) | compact constructor `Fact`, `Turn` | `FactTest` |
| I3 | На `(userId, scope)` одна `active` (AC-6, AC-7) | `Fact.checkScopeFree(Optional<Fact> active)` → `MEM-STATE-004`; partial unique index `fact_one_active`; адаптер переводит нарушение индекса в `MEM-STATE-004` | `FactTest`, `JdbcFactRepositoryTest` |
| I4 | Замена: цель есть, `active`, тот же `userId`, та же тема, `inferred` не заменяет `stated` (AC-5, AC-8) | `Fact.supersededBy(Fact next)`: `MEM-STATE-001/002/003/005`; отсутствие цели — `MEM-NF-001` в `FactWriter` | `FactTest` |
| I5 | Запись только добавляется: замена — новая строка со ссылкой, у старой меняется только статус | `Fact.supersededBy` возвращает копию со статусом; триггер `fact_append_only` (ABORT при смене `kind/scope/user_id/standing/supersedes/source_*` или тексте, ставшем не `""`) | `JdbcFactRepositoryTest` |
| I6 | `inferred` не идёт в recall (AC-9) | SQL recall `standing='stated'` + фильтр `Fact.recallable()` | `FtsFactSearchTest`, `RecallUseCaseTest` |
| I7 | «Забудь»: текст и цитата стёрты у факта и цепочки, след остался (AC-10) | `Fact.forget(userId, sessionId, now)`, `Fact.erase(now)`, `Turn.eraseUserText(now)`; CHECK-и `fact`, `turn`; триггер `fact_fts_close` удаляет из FTS | `FactTest`, `ForgetUseCaseTest`, `JdbcFactRepositoryTest` |
| I8 | Один `open` ход на пользователя (Р15) | `OpenTurnUseCase` вызывает `abandon()` у прежнего; partial unique index `turn_one_open` | `OpenTurnUseCaseTest`, `JdbcTurnRepositoryTest` |
| I9 | Одна цитата хода — один действующий факт (Р15: экстрактор не дублирует `memory_remember`) | `Fact.checkQuoteFree(List<Fact> activeFromSameTurn)` → `MEM-STATE-009` | `FactTest`, `ExtractTurnUseCaseTest` |
| I10 | Лимит recall уходит в `LIMIT`, ветки для 0 нет (AC-14) | `RecallUseCase`: `Math.min(requested, configured)` → `FactSearch.search(..., limit)` → SQL `LIMIT ?` | `RecallUseCaseTest`, `FtsFactSearchTest` |
| I11 | Ход с «забудь» не разбирается (D6) | `Turn.needsExtraction()` | `TurnTest`, `ExtractTurnUseCaseTest` |
| I12 | Хук не ломает ход (AC-18, AC-19) | `Hook.run(...)` ловит `Throwable`, возвращает 0; `HookMain` печатает только итог | `HookTest` |
| — | Переменное не в памяти (Р4) | **Только промпт экстрактора** — для отчёта: инвариант держит промпт | — |

## Контракт API

Сервис `wardrobe-memory`, JSON, snake_case. Ошибка — `{"code": "MEM-…", "error": "<сообщение>"}` (расширение `{"error"}` из `ApiExceptionHandler` Л6). HTTP по категории кода: `VAL` → 400, `NF` → 404, `STATE` → 409, прочее → 500 `MEM-INT-001`.

| Метод, путь | Запрос | Ответ | Ошибки |
|---|---|---|---|
| `GET /recall` | `user_id`, `q`, `limit?` (по умолч. N) | 200 `{"context": "<заголовок>\n<строки>" \| "", "ids": [..]}` | VAL-001, VAL-010 |
| `POST /turns` | `{user_id, session_id, external_id, user_text}` | 201 `{"turn_id","status":"open","abandoned":[..]}` | VAL-001, VAL-008, VAL-011, STATE-008 |
| `POST /turns/close` | `{user_id, external_id, assistant_text}` | 202 `{"turn_id","status":"pending"}` — сразу, экстракция в очереди (AC-15) | VAL-001, VAL-008, VAL-011, NF-002, STATE-002, STATE-007 |
| `POST /facts` (remember) | `{user_id, kind, scope, fact, quote, supersedes?}` | 201 `FactView` | VAL-001…007, VAL-009, NF-001, STATE-001…006, STATE-009 |
| `POST /facts/{id}/forget` | `{user_id}` | 200 `{"id","status":"forgotten","forgotten_at","forgotten_in"}` | VAL-001, VAL-009, NF-001, STATE-001, STATE-002, STATE-006 |
| `GET /facts` | `user_id`, `status?` = `active` (по умолч.) `\| superseded \| forgotten \| all` | 200 `{"facts":[FactView]}` | VAL-001, VAL-014 |

`FactView`: `{id, kind, scope, fact, quote, standing, status, created_at, supersedes, source_session_id, forgotten_at, forgotten_in}`.

Заголовок `context` (`RecallBlock`, `domain/RecallBlock.java`): `Память о пользователе (id | род | возраст | факт | «цитата»). constraint — запрет: передай его в исключения wardrobe_search.` При 0 строк — `""` без заголовка (это `String.join` пустого списка, не ветка по лимиту).

Строка: `Fact.recallLine(now)` → `f-012 | constraint | вчера | Не носит юбки | «я не ношу юбки»`; возраст `сегодня | вчера | N дней назад` по датам UTC; цитата > 40 символов режется по слову + `…`.

### MCP-tools (`wardrobe-memory-mcp`)

env: `MEMORY_URL`, `MEMORY_USER_ID` (Р1). Ответ tool — JSON сервиса как текст (как `wardrobe_search`); ошибка сервиса → `isError=true`, текст `MEM-…: сообщение`; сервис недоступен → `isError=true`, «Сервис памяти недоступен».

| Tool | Параметры | Вызов |
|---|---|---|
| `memory_remember` | `fact`, `quote`, `kind`, `scope`, `supersedes?` | `POST /facts` |
| `memory_forget` | `id` | `POST /facts/{id}/forget` |
| `memory_list` | — | `GET /facts?status=active` — `stated` и `inferred`, поле `standing` — пометка (AC-9) |

Описания: remember — «только по прямой просьбе; quote дословно из текущей реплики, сервис сверит»; forget — «id из строк памяти во входе, иначе из memory_list»; list — «standing=inferred — догадка модели».

### Хук (`wardrobe-memory-hook`)

env: `MEMORY_URL`, `MEMORY_USER_ID` (по умолч. `me`), `MEMORY_HOOK_TIMEOUT_MS` (по умолч. 1000, на каждый HTTP-запрос). В `pre` два запроса ⇒ ≤ 2 с + старт JVM < `timeout: 5` профиля.

| Событие | Действие | stdout |
|---|---|---|
| `pre_llm_call` | `GET /recall?q=user_message`, затем `POST /turns` | `{"context": …}`, если `context` не пуст; иначе ничего |
| `post_llm_call` | `POST /turns/close` с `extra.turn_id`, `extra.assistant_response` | ничего |
| иное / нет `user_message`-строки / ошибка | — | ничего, код 0 |

## Поток данных

**Pre (recall + открытие хода).**
1. Hermes → stdin → `HookPayload.parse` (Jackson). Ошибка разбора → пусто, 0.
2. `GET /recall` → `MemoryController.recall` → `RecallUseCase`: проверка `user_id`, `limit ≥ 0` → `FactSearch.search(userId, q, min(limit, N), statedOnly=true)` → адаптер `FtsFactSearch`: `FtsQueryBuilder.build(q)`; пустой запрос (одни стоп-слова) → пустой список; иначе SQL ниже → `Fact` → `filter(Fact::recallable)` → `RecallBlock.render(facts, now)`; лог `recall user_id ids`.
3. `POST /turns` → `OpenTurnUseCase`: `TurnRepository.findOpen(userId)` → `abandon()` → `Turn.open(nextId, …)` → `saveAll` в одной транзакции; лог `open turn_id abandoned`.
4. Хук печатает `{"context": …}`. Hermes дописывает в конец реплики.

```sql
SELECT f.* FROM fact_fts JOIN fact f ON f.id = fact_fts.fact_id
WHERE fact_fts MATCH ? AND f.user_id = ? AND f.status = 'active' AND f.standing = 'stated'
ORDER BY fact_fts.rank LIMIT ?
```

**Post (закрытие → экстракция).**
1. Хук → `POST /turns/close` → `CloseTurnUseCase`: `findByExternalId` → NF-002; `turn.userId` ≠ `user_id` → STATE-002 → `turn.close(text)` → save → `ExtractionQueue.submit(turnId)` → 202.
2. Поток очереди → `ExtractTurnUseCase.process(turnId)`:
   - загрузить; `!turn.needsExtraction()` и `pending` → `markDone`, лог `skip reason=forget requested`; не `pending` → выход;
   - кандидаты: `FactSearch.search(userId, userText + " " + assistantText, 10, statedOnly=false)` ∪ `FactRepository.findActiveBySourceTurn(turnId)`;
   - `Extractor.extract(ExtractionInput)` → `List<ExtractorDecision>`; `ExtractionFailedException` → `markFailed(code + причина)`, лог `extract failed`, фактов нет (AC-16);
   - каждое решение → `FactWriter.apply(turn, decision)` в своей транзакции; `MemoryException` → лог `reject op scope code`, следующее решение (AC-1); успех → лог `accept op fact_id scope`;
   - `markDone` → save.

**`FactWriter`** (`application/FactWriter.java`, общий для экстракции и remember) — только координация:
- `SKIP` → ничего; неизвестный `op` → VAL-012; `SUPERSEDE` без `target_id` → VAL-013.
- `ADD`: `Fact.create(nextId, turn, …, supersedes=null)` → `fact.checkScopeFree(repo.findActive(userId, scope))` → `fact.checkQuoteFree(repo.findActiveBySourceTurn(turnId))` → `repo.insert`.
- `SUPERSEDE`: `target = repo.findById` → NF-001 → `Fact.create(..., supersedes=target.id)` → `checkQuoteFree` → `old = target.supersededBy(next)` → `repo.update(old)`, `repo.insert(next)` в одной транзакции.

**Remember.** `memory_remember` → `POST /facts` → `RememberUseCase`: `TurnRepository.findOpen(userId)` → нет → STATE-006 (AC-4) → решение `ADD`/`SUPERSEDE` со `standing=stated` → `FactWriter.apply` → 201. Цитата сверяется с `userText` открытого хода (AC-3).

**Forget.** `memory_forget` → `POST /facts/{id}/forget` → `ForgetUseCase`: открытый ход → STATE-006 → `FactRepository.findChain(id)` (факт + все `supersedes` назад) → пусто → NF-001 → `head.forget(userId, turn.sessionId, now)` (STATE-001/002, AC-11) → `erase(now)` у остальных → `Turn.eraseUserText(now)` у ходов-источников цепочки → `turn.requestForget()` → всё сохранить в одной транзакции; лог `forget fact_id session_id`.

## Промпты и формат ответа модели

`OpenAiCompatibleExtractor` (`infrastructure/extractor/`): `RestClient` → `POST {EXTRACTOR_BASE_URL}/chat/completions`, `model=EXTRACTOR_MODEL`, `temperature=0`, `response_format={"type":"json_object"}`, таймаут `EXTRACTOR_TIMEOUT` (30 с). Одна попытка (Р12).

Вход (user message): `{"user_text": …, "assistant_text": …, "candidates": [{"id","kind","scope","fact","standing"}]}`.

Системный промпт (`ExtractorPrompt.SYSTEM`):

```
Ты извлекаешь устойчивые сведения о пользователе из одного хода диалога со стилистом. Их сохранит долговременная память стилиста.

Вход — JSON:
- user_text — реплика пользователя;
- assistant_text — ответ стилиста, только для понимания контекста;
- candidates — уже известные действующие факты: id, kind, scope, fact, standing.

Факт — то, что останется правдой в следующих разговорах:
- preference — вкус: цвета, фасоны, стиль («люблю оверсайз»);
- constraint — запрет или жёсткое условие: что пользователь не носит, чего избегает («я не ношу юбки»);
- background — сведения о себе: рост, размер, работа, город, дресс-код.

Не факт — для этого решений не возвращай:
- вещи гардероба: «у меня есть красная юбка», «купила пальто» — они уже в базе гардероба;
- разовые просьбы на этот ход: «сегодня без юбки», «собери образ на свидание»;
- погода, планы на день, настроение: «сегодня грустно, хочу в чёрном»;
- просьбы запомнить или забыть — их выполняют tools;
- слова стилиста.
Граница: «у меня есть юбка» — не факт; «я не ношу юбки» — факт.

На каждое сведение — одно решение:
- ADD — сведение новое, его тема не занята ни одним кандидатом;
- SUPERSEDE — сведение меняет кандидата на той же теме; target_id — id кандидата, scope — его scope;
- SKIP — кандидат уже говорит то же самое; target_id — id кандидата.

Поля решения:
- op: ADD | SUPERSEDE | SKIP;
- target_id: id кандидата для SUPERSEDE и SKIP, иначе "";
- kind: preference | constraint | background;
- scope: ключ темы латиницей через дефис, до 48 символов: skirts, color-blue, height. Одна тема — одна мысль: «не ношу юбки» → skirts, «не ношу шорты» → shorts. Есть кандидат на ту же тему — бери его scope;
- fact: одно утверждение в третьем лице по-русски, до 200 символов;
- quote: дословный непрерывный фрагмент user_text до 200 символов, на котором держится факт; не пересказывай, не исправляй, не бери из assistant_text — код сверит цитату и отбросит решение;
- standing: stated — пользователь сказал прямо; inferred — ты вывел из его слов.

Фактов нет — {"decisions":[]}. Ответ — только JSON, без пояснений и markdown:
{"decisions":[{"op":"ADD","target_id":"","kind":"constraint","scope":"skirts","fact":"Не носит юбки","quote":"я не ношу юбки","standing":"stated"}]}
```

Схема ответа: объект с массивом `decisions`; элемент — `{op, target_id, kind, scope, fact, quote, standing}`, все строки.

| Ответ | Реакция кода |
|---|---|
| сеть, таймаут, HTTP ≠ 2xx | `ExtractionFailedException(MEM-EXT-001)` → ход `failed` |
| `content` не JSON (после снятия обёртки ```` ```json ````), нет массива `decisions` | `MEM-EXT-002` → ход `failed` |
| элемент не объект / поле не строка | отклоняется только он, `MEM-EXT-002` в лог |
| элемент с невалидным `op/kind/scope/standing`, цитатой вне реплики, занятой темой | `Fact.create` / `FactWriter` бросают код, отклоняется только он |
| `decisions: []` | ход `done`, фактов нет |

## Хранилище и миграции

Файл SQLite `${MEMORY_DB_PATH}` (`?foreign_keys=on`), Hikari `maximum-pool-size: 1`. `schema.sql` + `spring.sql.init.mode: always` как в Л6, объекты `IF NOT EXISTS`, `separator: "@@"` (в триггерах есть `;`). Доступ — `JdbcClient`: JPA не умеет `MATCH` и FTS-таблицы. Миграций данных нет.

```sql
CREATE TABLE IF NOT EXISTS counter (name TEXT PRIMARY KEY, n INTEGER NOT NULL)   -- 'turn', 'fact'; UPDATE … RETURNING n
CREATE TABLE IF NOT EXISTS turn (
  id TEXT PRIMARY KEY, external_id TEXT NOT NULL UNIQUE, user_id TEXT NOT NULL, session_id TEXT NOT NULL,
  user_text TEXT NOT NULL, assistant_text TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL CHECK (status IN ('open','pending','done','failed','abandoned')),
  fail_reason TEXT NOT NULL DEFAULT '', forget_requested INTEGER NOT NULL DEFAULT 0 CHECK (forget_requested IN (0,1)),
  created_at TEXT NOT NULL, erased_at TEXT,
  CHECK (erased_at IS NOT NULL OR user_text <> ''), CHECK (erased_at IS NULL OR user_text = ''))
CREATE UNIQUE INDEX IF NOT EXISTS turn_one_open ON turn(user_id) WHERE status = 'open'
CREATE TABLE IF NOT EXISTS fact (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('preference','constraint','background')),
  scope TEXT NOT NULL, fact TEXT NOT NULL, quote TEXT NOT NULL,
  standing TEXT NOT NULL CHECK (standing IN ('stated','inferred')),
  source_turn_id TEXT NOT NULL REFERENCES turn(id), source_session_id TEXT NOT NULL,
  created_at TEXT NOT NULL, supersedes TEXT REFERENCES fact(id),
  status TEXT NOT NULL CHECK (status IN ('active','superseded','forgotten')),
  forgotten_at TEXT, forgotten_in TEXT,
  CHECK (forgotten_at IS NOT NULL OR (fact <> '' AND quote <> '')),
  CHECK (forgotten_at IS NULL OR (fact = '' AND quote = '')),
  CHECK (status <> 'forgotten' OR forgotten_at IS NOT NULL))
CREATE UNIQUE INDEX IF NOT EXISTS fact_one_active ON fact(user_id, scope) WHERE status = 'active'
CREATE INDEX IF NOT EXISTS fact_source ON fact(source_turn_id)
CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts5(body, fact_id UNINDEXED, tokenize='unicode61')
-- триггеры:
-- fact_fts_insert  AFTER INSERT WHEN new.status='active': body = replace(lower(fact||' '||quote),'ё','е')
-- fact_fts_close   AFTER UPDATE OF status WHEN new.status<>'active': DELETE FROM fact_fts WHERE fact_id=old.id
-- fact_append_only BEFORE UPDATE: RAISE(ABORT,'MEM-STATE-010') если меняются kind/scope/user_id/standing/
--                  supersedes/source_*/created_at, текст или цитата становятся не '', или статус уходит не из 'active'
```

## Стратегия тестов

JUnit 5 + AssertJ (`spring-boot-starter-test`; в Л6 тестов нет). Классы `*Test`. Сценарии S1–S3 и мутация — вручную в `scenarios/`.

| Слой | Что проверяется | Фейки |
|---|---|---|
| domain (unit) | `FactTest`: create/цитата/формат/scope/supersede/forget/erase/checkQuoteFree; `TurnTest`: переходы, `needsExtraction`; `QuoteMatcherTest`: регистр, `ё`, пунктуация, цитата из ответа ассистента; `RecallLineTest`: формат, возраст, обрезка | нет |
| application (unit) | `OpenTurnUseCaseTest`, `CloseTurnUseCaseTest`, `RememberUseCaseTest`, `ForgetUseCaseTest`, `ExtractTurnUseCaseTest`, `RecallUseCaseTest` | in-memory `FakeFactRepository`, `FakeTurnRepository`, `FakeFactSearch`, `FakeExtractor`, синхронная `FakeExtractionQueue`, `Clock.fixed` |
| infrastructure (интеграционные, SQLite в `@TempDir`) | `SchemaTest` (FTS5 есть, D4); `JdbcFactRepositoryTest`: `fact_one_active`, `fact_append_only`, CHECK-и забытого, FTS-триггеры; `JdbcTurnRepositoryTest`: `turn_one_open`; `FtsFactSearchTest`: «юбкой»→«юбки», только `stated`+`active`, `LIMIT 0` → пусто, забытый не находится; `FtsQueryBuilderTest` (unit) | нет |
| extractor | `OpenAiCompatibleExtractorTest`: валидный JSON, обёртка ```` ```json ````, не JSON → EXT-002, 500/таймаут → EXT-001, кривой элемент | `MockRestServiceServer` вместо модели |
| web | `MemoryControllerTest` (`@SpringBootTest` + MockMvc, SQLite в `@TempDir`): коды и HTTP-статусы, `POST /turns/close` → 202, пока экстрактор заблокирован `CountDownLatch` | `@MockitoBean Extractor` |
| mcp | `MemoryToolsTest`: успех → текст JSON, 409 → `isError` с кодом, сервис недоступен → `isError` | `MockRestServiceServer` |
| hook | `HookTest`: `Hook.run(stdin, stdout, env)` → код; сервис не слушает порт → пусто, 0; сервис отвечает дольше таймаута → пусто, 0, время < таймаут + 500 мс; битый JSON → пусто, 0; pre → `{"context"}`; пустой `context` → пусто | JDK `HttpServer` как фейк сервиса |

## Коды ошибок

| Код | Условие | Сообщение |
|---|---|---|
| MEM-VAL-001 | `user_id` пуст или не по шаблону | `user_id is invalid` |
| MEM-VAL-002 | `kind` не из списка | `invalid kind "<v>", valid: preference, constraint, background` |
| MEM-VAL-003 | `scope` не ключ или > 48 | `invalid scope "<v>": lowercase latin words joined by "-"` |
| MEM-VAL-004 | `fact` пуст или > 200 | `fact must be 1..200 chars` |
| MEM-VAL-005 | `quote` пуст или > 200 | `quote must be 1..200 chars` |
| MEM-VAL-006 | цитаты нет в реплике пользователя | `quote not found in user message of turn <id>` |
| MEM-VAL-007 | `standing` не из списка | `invalid standing "<v>", valid: stated, inferred` |
| MEM-VAL-008 | `user_text` пуст / > 20000, `assistant_text` > 20000 | `<field> must be 1..20000 chars` |
| MEM-VAL-009 | id не `f-NNN` | `invalid fact id "<v>"` |
| MEM-VAL-010 | `limit` < 0 или не число | `limit must be >= 0` |
| MEM-VAL-011 | нет `external_id`/`session_id` | `<field> is required` |
| MEM-VAL-012 | `op` экстрактора неизвестен | `unknown op "<v>"` |
| MEM-VAL-013 | `SUPERSEDE` без `target_id` | `target_id is required for SUPERSEDE` |
| MEM-VAL-014 | `status` фильтра неизвестен | `invalid status "<v>"` |
| MEM-NF-001 | факт не найден | `fact <id> not found` |
| MEM-NF-002 | ход по `external_id` не найден | `turn <external_id> not found` |
| MEM-STATE-001 | цель не `active` | `fact <id> is <status>` |
| MEM-STATE-002 | факт или ход другого `user_id` | `fact <id> belongs to another user` |
| MEM-STATE-003 | тема новой записи ≠ теме цели | `scope "<v>" differs from target <id> scope "<v>"` |
| MEM-STATE-004 | на теме уже есть `active` | `scope "<v>" already has active fact <id>` |
| MEM-STATE-005 | `inferred` заменяет `stated` | `inferred fact cannot replace stated <id>` |
| MEM-STATE-006 | нет открытого хода | `no open turn for user <id>: remember/forget work only inside a turn` |
| MEM-STATE-007 | недопустимый переход хода | `turn <id> is <status>` |
| MEM-STATE-008 | `external_id` уже есть | `turn <external_id> already exists` |
| MEM-STATE-009 | цитата уже держит действующий факт этого хода | `quote already backs active fact <id>` |
| MEM-STATE-010 | триггер `fact_append_only` | `fact record is append-only` |
| MEM-EXT-001 | экстрактор недоступен / таймаут / не 2xx | (лог, `fail_reason`) `extractor unavailable: <cause>` |
| MEM-EXT-002 | ответ вне схемы | (лог) `extractor response invalid: <cause>` |
| MEM-INT-001 | прочее | `internal error` (детали в лог) |

Код — `enum ErrorCode` (`domain/ErrorCode.java`) с категорией `VALIDATION | NOT_FOUND | CONFLICT | EXTRACTOR | INTERNAL`; `MemoryException(ErrorCode, String)`; HTTP-статус выбирает `web/ApiExceptionHandler`.

## Трассировка критериев приёмки

| AC | Компонент | Тест |
|---|---|---|
| AC-1 | `Fact.create` + `QuoteMatcher`; цикл решений в `ExtractTurnUseCase` | `FactTest`, `ExtractTurnUseCaseTest.rejectsOneKeepsOthers` |
| AC-2 | `QuoteMatcher` по `userText` | `QuoteMatcherTest.assistantOnly`, `ExtractTurnUseCaseTest` |
| AC-3 | `RememberUseCase` → `Fact.create` с открытым ходом | `RememberUseCaseTest.quoteNotInTurn` |
| AC-4 | `RememberUseCase` → STATE-006 | `RememberUseCaseTest.noOpenTurn` |
| AC-5 | `FactWriter` (NF-001), `Fact.supersededBy` (001/002) | `FactTest`, `RememberUseCaseTest` |
| AC-6 | `Fact.supersededBy`, `fact_one_active` | `FactTest`, `JdbcFactRepositoryTest` |
| AC-7 | `Fact.checkScopeFree`, `fact_one_active` | `FactTest`, `JdbcFactRepositoryTest` |
| AC-8 | `Fact.supersededBy` (STATE-005) | `FactTest` |
| AC-9 | `Fact.recallable`, SQL recall, `GET /facts` с `standing` | `FtsFactSearchTest`, `MemoryToolsTest` |
| AC-10 | `ForgetUseCase`, `Fact.forget/erase`, `Turn.eraseUserText`, CHECK-и, `fact_fts_close` | `ForgetUseCaseTest`, `JdbcFactRepositoryTest`, `FtsFactSearchTest` |
| AC-11 | `ForgetUseCase` (NF-001), `Fact.forget` (STATE-001) | `ForgetUseCaseTest` |
| AC-12 | compact constructor `Fact` | `FactTest` |
| AC-13 | `RecallUseCase`, `FtsFactSearch`, `Fact.recallLine` | `RecallLineTest`, `FtsFactSearchTest` |
| AC-14 | `RecallUseCase` → `LIMIT ?` | `FtsFactSearchTest.limitZero`, `MemoryControllerTest` |
| AC-15 | `CloseTurnUseCase` + `ExecutorExtractionQueue` | `MemoryControllerTest.closeReturnsBeforeExtraction` |
| AC-16 | `OpenAiCompatibleExtractor`, `ExtractTurnUseCase` → `markFailed` | `OpenAiCompatibleExtractorTest`, `ExtractTurnUseCaseTest` |
| AC-17 | `OpenTurnUseCase` → `Turn.abandon` | `OpenTurnUseCaseTest`, `JdbcTurnRepositoryTest` |
| AC-18 | `Hook.run`, `MEMORY_HOOK_TIMEOUT_MS` | `HookTest.serviceDown`, `HookTest.serviceSlow` |
| AC-19 | `HookPayload.parse` в `try` | `HookTest.badJson` |
| AC-20 | D1 `FtsQueryBuilder` + recall | `FtsFactSearchTest.findsOtherWordForm`; ручной S1 (`curl /recall`) |
| AC-21 | `MEMORY_RECALL_LIMIT=0` → I10 | ручная мутация S1 |
| AC-22 | отдельный процесс (Р7), хук пуст при недоступном сервисе | `HookTest.serviceDown`; ручной прогон «до» |
| AC-23 | Л6 не трогается | ручная проверка: `git diff <последний коммит Л6> -- wardrobe-api wardrobe-mcp` пуст, `curl /api/items` |

## Открытые вопросы

1. **D2.** Ход, ждущий экстракции при перезапуске, остаётся `pending` навсегда — так велит grill (Д4, «Вне рамок» п.3). Разбор `pending` при старте дешёв, но это пересмотр гриля.
2. **D6 дополняет Р6:** grill молчит о ходе с «забудь». Без D6 экстрактор может записать ту же мысль под другой темой — I3 этого не ловит.
3. **Старт хука** не измерен (research в.7): измерить в implement; при > 1 с — AppCDS, контракт не меняется.

## Затраты фазы

- Минуты: ≈ 6 (2026-10-17, 15:13:27–15:19:26 UTC), 1 сообщение автора, 19 вызовов модели.
- Токены: output 37 602; input 38; запись в кэш 118 594; чтение из кэша 2 162 861.
- Способ: как в grill.md — сумма поля `usage` ответов модели в журнале сессии `~/.claude/projects/…/f009ac57-….jsonl` (подагентов не было), время — по первой и последней записи журнала.
