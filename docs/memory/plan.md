# Plan: модуль памяти для Hermes (ДЗ Л7–Л8)

- **Дата:** 2026-10-17
- **Вход:** `docs/memory/design.md` (D1–D11, I1–I12, AC-1…AC-23), `docs/memory/REVIEW-design.md` — все 15 предложений приняты и внесены в design.
- **Сборка:** глобального `mvn` нет. Везде ниже `MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"`, запуск из корня репозитория.
- **Пакеты:** `org.example.wardrobe.memory.{domain,application,infrastructure,web}`; пути ниже — от `wardrobe-memory/src/{main,test}/java/org/example/wardrobe/memory/`.

## Вехи

**M1: Каркас сервиса и проверка рисков D4 / D1**
- Файлы:
  - `pom.xml` (корень) — `<module>wardrobe-memory</module>`
  - `wardrobe-memory/pom.xml` — starter-webmvc, starter-jdbc, `org.xerial:sqlite-jdbc`, `org.apache.lucene:lucene-analysis-common`, starter-test, boot-plugin
  - `WardrobeMemoryApplication.java` — `@SpringBootApplication`
  - `src/main/resources/schema.sql` — `counter`, `turn`, `fact`, индексы `turn_one_open`, `fact_one_active`, `fact_source`, `fact_fts`, триггеры `fact_fts_close`, `fact_append_only`; разделитель `@@`
  - `infrastructure/search/RussianStems.java` — `index(text)`, `query(text)` (D1)
- Тесты: `infrastructure/SchemaTest` — схема на файле в `@TempDir`, FTS5 есть; `infrastructure/search/RussianStemsTest` — `allSkirtForms`, стоп-слова, `ё→е`, пустой запрос
- Приёмка: AC-20 (часть: стемминг)
- Проверка: `"$MVN" -pl wardrobe-memory test`
- Стоп-условие: нет FTS5 или класса `RussianStemmer` → открытый вопрос 4 design (`org.tartarus:snowball`), до M2 не идём
- Зависит от: нет

**M2: Домен — сущности и инварианты**
- Файлы (`domain/`): `ErrorCode` (коды и категории из таблицы «Коды ошибок»), `MemoryException`, `TurnStatus`, `FactKind`, `Standing`, `FactStatus`, `QuoteMatcher`, `Turn` (compact constructor, `open/close/abandon/markDone/markFailed/requestForget/eraseUserText/needsExtraction`), `Fact` (`create`, `checkScopeFree`, `supersededBy`, `forget`, `erase`, `recallable`, `recallLine`), `RecallBlock` (`render`, одна ветка `lines.isEmpty()`)
- Тесты (`domain/`): `FactTest` (I1–I4, I7, AC-5/7/8/12), `TurnTest` (переходы, STATE-007, `needsExtraction` — I11), `QuoteMatcherTest` (регистр, `ё`, пунктуация, `assistantOnly`), `RecallLineTest` (формат, возраст по `Clock`, обрезка 40), `RecallBlockTest` (пусто → `""`)
- Приёмка: AC-1, AC-2, AC-5, AC-7, AC-8, AC-12, AC-13 (строка), AC-14 (блок)
- Проверка: `"$MVN" -pl wardrobe-memory test`
- Зависит от: M1

**M3: Порты и хранилище SQLite**
- Файлы:
  - `domain/TurnRepository`, `domain/FactRepository` (`findById`, `findActive`, `findActiveByUser`, `findChain`, `insert`, `update`), `domain/FactSearch` (`search`, `compact`)
  - `infrastructure/persistence/JdbcTurnRepository`, `JdbcFactRepository` (FTS-строка при insert через `RussianStems.index`; нарушение `fact_one_active` → STATE-004; `RAISE` триггера → STATE-010)
  - `infrastructure/search/FtsFactSearch` — SQL recall с `LIMIT ?`
  - `src/main/resources/application.yml` — порт 8091, `MEMORY_DB_PATH`, Hikari `maximum-pool-size: 1`, `connection-init-sql: PRAGMA secure_delete=ON`, `sql.init.mode: always`, `separator: "@@"`
- Тесты (`infrastructure/`): `JdbcTurnRepositoryTest` (`turn_one_open`), `JdbcFactRepositoryTest` (`fact_one_active`, `appendOnly`, `eraseSuperseded`, CHECK-и забытого, FTS insert/удаление, `forgottenTextNotInFile`), `FtsFactSearchTest` (`findsOtherWordForm`, только `stated`+`active`, `limitZero`, забытый не находится)
- Приёмка: AC-6/7 (индекс), AC-9 (SQL), AC-10 (хранилище, I9), AC-13, AC-14 (`LIMIT`), AC-17 (индекс), AC-20
- Проверка: `"$MVN" -pl wardrobe-memory test`
- Зависит от: M2

**M4: Экстрактор (адаптер модели)**
- Файлы: `application/Extractor` (порт), `application/ExtractionInput`, `application/ExtractorDecision`, `application/ExtractionFailedException`; `infrastructure/extractor/OpenAiCompatibleExtractor` (`RestClient`, `Authorization` только при непустом ключе, `temperature=0`, `json_object`, таймаут, одна попытка, снятие ```` ```json ````), `infrastructure/extractor/ExtractorPrompt` (`SYSTEM` дословно из design), `infrastructure/extractor/ExtractorProperties` (`EXTRACTOR_BASE_URL`, `EXTRACTOR_MODEL`, `EXTRACTOR_API_KEY`, `EXTRACTOR_TIMEOUT`); `wardrobe-memory/pom.xml` — starter-restclient
- Тесты: `infrastructure/extractor/OpenAiCompatibleExtractorTest` — валидный JSON, обёртка, не JSON → EXT-002, 500/401/таймаут → EXT-001, кривой элемент отклонён один, заголовок есть/нет (`MockRestServiceServer`)
- Приёмка: AC-16 (адаптер)
- Проверка: `"$MVN" -pl wardrobe-memory test`
- Зависит от: M2

**M5: Use case и очередь экстракции**
- Файлы (`application/`): `FactWriter` (ADD/SUPERSEDE/SKIP, VAL-012/013, NF-001, одна транзакция на решение), `RecallUseCase` (`min(limit, MEMORY_RECALL_LIMIT=5)`, VAL-010), `OpenTurnUseCase`, `CloseTurnUseCase`, `RememberUseCase`, `ForgetUseCase` (цепочка, `requestForget`, после коммита `compact()`), `ExtractTurnUseCase` (D7, D11, `catch (Throwable)` → INT-001), `ExtractionQueue` (порт); `infrastructure/queue/ExecutorExtractionQueue` (однопоточный `ExecutorService`); `MemoryConfig` (`Clock`, `TransactionTemplate`, бины)
- Тесты: `application/*UseCaseTest` (Open, Close, Remember: `noOpenTurn`/`quoteNotInTurn`, Forget, Recall, Extract: `rejectsOneKeepsOthers`, remember-факт → SKIP/отказ I3, `Throwable` → failed), `FactWriterTest`; фейки `test/.../fake/FakeFactRepository`, `FakeTurnRepository`, `FakeFactSearch`, `FakeExtractor`, `FakeExtractionQueue`; `infrastructure/JdbcFactRepositoryTest.supersedeInOneTransaction` (`FactWriter` + реальная база)
- Приёмка: AC-1…AC-6, AC-10, AC-11, AC-14, AC-16, AC-17
- Проверка: `"$MVN" -pl wardrobe-memory test`
- Зависит от: M3, M4

**M6: HTTP API сервиса**
- Файлы (`web/`): `MemoryController` (6 маршрутов контракта), запросы/ответы как records (`RecallRequest`, `OpenTurnRequest`, `CloseTurnRequest`, `RememberRequest`, `ForgetRequest`, `FactView`, …, snake_case), `ApiExceptionHandler` (категория → 400/404/409/500; `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` → VAL-015, `MissingServletRequestParameterException` → VAL-011)
- Тесты: `web/MemoryControllerTest` (`@SpringBootTest` + MockMvc, SQLite в `@TempDir`, `@MockitoBean Extractor`): коды и статусы, битый JSON, `closeReturnsBeforeExtraction` (`CountDownLatch`), `listShowsInferred`, `limit=0` → `""`
- Приёмка: AC-9, AC-14, AC-15
- Проверка: `"$MVN" -pl wardrobe-memory verify`; ручное: `java -jar wardrobe-memory/target/*.jar`, `curl -X POST :8091/turns`, `/facts`, `/recall`
- Зависит от: M5

**M7: MCP-сервер `wardrobe-memory-mcp`**
- Файлы: корневой `pom.xml` — `<module>`; `wardrobe-memory-mcp/pom.xml` (как `wardrobe-mcp/pom.xml`, `finalName wardrobe-memory-mcp`); `.../memory/mcp/WardrobeMemoryMcpApplication`, `client/MemoryApiClient`, `client/MemoryApiException`, `tool/MemoryTools` (`memory_remember`, `memory_forget`, `memory_list`, описания из design); `src/main/resources/application.yml` (stdio, лог в файл, `MEMORY_URL`, `MEMORY_USER_ID`)
- Тесты: `tool/MemoryToolsTest` — успех → JSON текстом, 409 → `isError` с кодом, сервис недоступен → `isError` (`MockRestServiceServer`)
- Приёмка: AC-4 (путь tool), AC-9 (пометка `standing`)
- Проверка: `"$MVN" -pl wardrobe-memory-mcp verify`
- Зависит от: M1 (корневой pom); ручной прогон — M6

**M8: Хук `wardrobe-memory-hook`**
- Файлы: корневой `pom.xml` — `<module>`; `wardrobe-memory-hook/pom.xml` (без Spring, `jackson-databind`, fat jar `wardrobe-memory-hook.jar` с `Main-Class`); `.../memory/hook/HookMain` (печатает только итог, `exit 0`), `Hook` (`run(stdin, stdout, env)`, `catch Throwable`, D10), `HookPayload` (`parse`, `user_message` только строка — D5), `MemoryClient` (JDK `HttpClient`, `MEMORY_HOOK_TIMEOUT_MS`)
- Тесты: `HookTest` (JDK `HttpServer` как фейк): `serviceDown`, `serviceSlow`, `badJson`, pre → `{"context"}`, пустой `context` → пусто, `recallFailsTurnStillOpens`, post → `/turns/close`
- Приёмка: AC-18, AC-19, AC-22 (хук)
- Проверка: `"$MVN" -pl wardrobe-memory-hook verify`; ручное: `time java -jar wardrobe-memory-hook/target/wardrobe-memory-hook.jar < payload.json` при остановленном сервисе < 5 с
- Зависит от: M1

**M9: Интеграция с Hermes**
- Файлы: вне репозитория — `~/.hermes/profiles/l78/config.yaml` (хуки и `wardrobe_memory` вместо примера), `~/.hermes/profiles/l78/.env` (`MEMORY_URL`, `MEMORY_USER_ID`); сервис примера на 8765 остановлен
- Тесты: весь реактор зелёный; `hermes -p l78 hooks list`, `hermes -p l78 mcp test wardrobe_memory`; прогон `hermes -p l78 chat -q "…" -Q` → в логе сервиса `recall` с id; `curl -X POST /recall` по входу S1; мутация `MEMORY_RECALL_LIMIT=0` → пусто; `git diff 2490b15 -- wardrobe-api wardrobe-mcp` пуст, `curl :8090/api/items`
- Приёмка: AC-20, AC-21, AC-22, AC-23
- Проверка: `"$MVN" verify` (корень)
- Зависит от: M6, M7, M8

## Критический путь

M1 → M2 → M3 → M5 → M6 → M9. M4 идёт параллельно M3 (нужен к M5), M7 и M8 — независимые модули, им нужен только M1.

## Покрытие критериев приёмки

| AC | Вехи | AC | Вехи |
|---|---|---|---|
| AC-1 | M2, M5 | AC-13 | M2, M3 |
| AC-2 | M2, M5 | AC-14 | M2, M3, M5, M6 |
| AC-3 | M5 | AC-15 | M6 |
| AC-4 | M5, M7 | AC-16 | M4, M5 |
| AC-5 | M2, M5 | AC-17 | M3, M5 |
| AC-6 | M3, M5 | AC-18 | M8 |
| AC-7 | M2, M3 | AC-19 | M8 |
| AC-8 | M2 | AC-20 | M1, M3, M9 |
| AC-9 | M3, M6, M7 | AC-21 | M9 |
| AC-10 | M3, M5 | AC-22 | M8, M9 |
| AC-11 | M5 | AC-23 | M9 |
| AC-12 | M2 | | |

Непокрытых нет.

## Пробелы design

1. **Выдача id из `counter`.** Таблица есть (`UPDATE … RETURNING n`), но не названо, какой порт её отдаёт. Plan ставит `nextId()` в `TurnRepository` / `FactRepository` — подтвердить.
2. **`MEMORY_DB_PATH` по умолчанию** не задан. Plan: обязательный env без значения по умолчанию — подтвердить.
3. **Сборка fat jar хука** — плагин не назван (grill Р9: «fat jar»). Plan: `maven-shade-plugin`.
4. **Последний коммит Л6 для AC-23** не назван; plan берёт `2490b15` (Initial commit) — подтвердить.

## Прогноз (написан до кода)

Токены — output модели, как в «Затратах фазы» прошлых фаз; чтение из кэша не прогнозируется.

| Веха | Минуты | Токены (output) | Переделки |
|------|--------|-----------------|-----------|
| M1 | 15 | 15 000 | 1 (зависимость стеммера) |
| M2 | 20 | 30 000 | 1 |
| M3 | 35 | 45 000 | 2 (триггеры, FTS, `secure_delete`) |
| M4 | 15 | 20 000 | 1 |
| M5 | 40 | 55 000 | 1 |
| M6 | 25 | 30 000 | 1 (ошибки Spring до контроллера) |
| M7 | 15 | 15 000 | 0 |
| M8 | 20 | 20 000 | 1 (fat jar, таймауты) |
| M9 | 25 | 15 000 | 1 (профиль Hermes) |
| Итого | 210 | 245 000 | 9 |

## Затраты фазы

- Минуты: ≈ 10 (2026-10-17, 16:53:43–17:03:17 UTC), 1 сообщение автора, 10 вызовов модели.
- Токены: output 13 084; input 20; запись в кэш 78 309; чтение из кэша 907 731.
- Способ: как в design.md — сумма поля `usage` ответов модели в журнале сессии `~/.claude/projects/…/28ebe7d9-….jsonl` (подагентов не было), время — по первой и последней записи журнала на момент сохранения plan.md; одобрение плана и коммит не вошли.
