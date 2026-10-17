# Implement: модуль памяти для Hermes (ДЗ Л7–Л8)

- **Дата:** 2026-10-17
- **Вход:** `docs/memory/plan.md` (вехи M1–M9, прогноз), `docs/memory/design.md`, `docs/memory/grill.md`.
- **Объём запуска:** вехи M1–M3 (по просьбе автора; M4–M9 — следующим запуском).
- **Второй запуск:** вехи M4–M6 (по просьбе автора; M7–M9 — следующим), журнал `~/.claude/projects/…/fbd666b7-….jsonl` (+ `subagents/`, их не было), тот же способ замера.
- **Третий запуск:** вехи M7–M9 (последние), журнал `~/.claude/projects/…/c8b94b34-….jsonl` (+ `subagents/`, их не было), тот же способ замера.
- **Сборка:** `MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"`, из корня репозитория; JDK 26.0.1, Maven 3.9.11, `java.version` 17.
- **Замер:** по журналу сессии `~/.claude/projects/…/72602981-….jsonl` (+ `subagents/`, их не было) — сумма поля `usage` ответов модели (по одному на `message.id`) между началом и закрытием вехи; минуты — по меткам времени журнала. Токены в таблице — output, как в прогнозе plan.

## Вехи

### M1: Каркас сервиса и проверка рисков D4 / D1

- Сделано: модуль `wardrobe-memory` в корневом `pom.xml`; `wardrobe-memory/pom.xml` (webmvc, jdbc, `sqlite-jdbc` — версия из BOM Boot, 3.53.2.1; `lucene-analysis-common` 9.12.3; starter-test; boot-plugin); `WardrobeMemoryApplication`; `schema.sql` (`counter` с засевом, `turn`, `fact`, `turn_one_open`, `fact_one_active`, `fact_source`, `fact_fts`, триггеры `fact_fts_close`, `fact_append_only`, разделитель `@@`); `infrastructure/search/RussianStems` (`index`, `query`).
- Тесты: `SchemaTest` (схема на файле в `@TempDir` применяется дважды, все объекты есть, `counter` засеян, FTS5 `MATCH` работает); `RussianStemsTest` (`allSkirtForms`, стоп-слова и короткие слова, `ё→е`, `query` через `OR` без повторов, пустой запрос).
- Проверка: `"$MVN" -pl wardrobe-memory test` — 6 из 6 прошли (`RussianStemsTest` 5, `SchemaTest` 1).
- Стоп-условие не сработало: FTS5 есть во встроенном SQLite `sqlite-jdbc`, класс `org.tartarus.snowball.ext.RussianStemmer` есть в `lucene-analysis-common` (открытый вопрос 4 design закрыт).
- Отклонения:
  1. Версия Lucene — 9.12.3, а не последняя 10.x: Lucene 10 собран под Java 21 (class file 65), модуль компилируется с `java.version` 17 из корневого pom. Plan версию не задавал.
  2. Список стоп-слов — `RussianAnalyzer.getDefaultStopSet()` (стандартный список Snowball из той же библиотеки); design говорит «стоп-слова отброшены», не называя список. В нём есть «не»: «я не ношу юбки» → `нош юбк`.
  3. `RussianStems.query` берёт каждую основу в кавычки (`"юбк" OR "нош"`), чтобы основа не читалась как оператор FTS5; design: «основы реплики через `OR`».
- Факт: 4 мин (17:18:54–17:22:45 UTC), output 10 325 токенов, 12 вызовов модели (включая чтение plan/design), 0 переделок.

### M2: Домен — сущности и инварианты

- Сделано (`domain/`): `ErrorCode` (коды из таблицы design, категория `VALIDATION | NOT_FOUND | CONFLICT | EXTRACTOR | INTERNAL`, `code()` → `MEM-VAL-001`), `MemoryException`, `TurnStatus`, `FactKind` и `Standing` (`parse` → VAL-002 / VAL-007), `FactStatus`, `QuoteMatcher`, `Turn` (compact constructor; `open/close/abandon/markDone/markFailed/requestForget/eraseUserText/needsExtraction`), `Fact` (compact constructor; `create`, `checkScopeFree`, `supersededBy`, `forget`, `erase`, `recallable`, `recallLine`), `RecallBlock` (`render`, одна ветка `lines.isEmpty()`).
- Тесты: `FactTest` 20 (I1 — цитата и цитата из ответа ассистента, I2/AC-12 — формат scope и полей, I3/AC-7 — `checkScopeFree`, I4/AC-5/AC-8 — замена и её отказы, I7 — `forget`, `erase`), `TurnTest` 7 (переходы, STATE-007, `needsExtraction` — I11, `eraseUserText`, проверки полей), `QuoteMatcherTest` 3 (регистр, `ё`, пунктуация, пересказ, `assistantOnly`), `RecallLineTest` 5 (формат, возраст по датам UTC через `Clock.fixed`, обрезка 40 по слову), `RecallBlockTest` 2 (пусто → `""`, заголовок и строки).
- Проверка: `"$MVN" -pl wardrobe-memory test` — 43 из 43 прошли (37 новых + 6 из M1).
- Отклонения:
  1. Ошибки целостности, у которых в таблице кодов design нет своего кода (id хода не `t-NNN`, `assistant_text` не пуст вне `pending`, `fail_reason` не совпадает со статусом, пустой `source_turn_id`, стёртый факт с текстом, `erase` у действующего факта), бросают `IllegalArgumentException` / `IllegalStateException`, а не `MemoryException`: их создаёт сам сервис, не клиент; в API они станут 500 `MEM-INT-001` («прочее»).
  2. Сообщение VAL-008 для `assistant_text` — `assistant_text must be 0..20000 chars` (в таблице кодов общий шаблон `1..20000`; по модели сущностей `assistantText` может быть пустым).
  3. `Fact.create` принимает `FactKind` / `Standing`, а не строки: разбор строки — `FactKind.parse` / `Standing.parse` с кодами VAL-002 / VAL-007; вызывать его будет `FactWriter` (M5).
  4. Порядок проверок `supersededBy`: сначала «другой пользователь» (STATE-002), потом статус (STATE-001) — чтобы по чужому id не узнать статус чужого факта. Design порядок не задаёт.
  5. Возраст «N дней назад» — дословно по design, без согласования числа («2 дней назад»).
- Факт: 3 мин (17:22:45–17:26:05 UTC), output 24 158 токенов, 8 вызовов модели, 0 переделок.

### M3: Порты и хранилище SQLite

- Сделано:
  - порты `domain/TurnRepository` (`nextId`, `findById`, `findByExternalId`, `findOpen`, `insert`, `update`), `domain/FactRepository` (`nextId`, `findById`, `findActive`, `findActiveByUser`, `findChain`, `insert`, `update`), `domain/FactSearch` (`search`, `compact`);
  - `infrastructure/persistence/JdbcTurnRepository`, `JdbcFactRepository` (`JdbcClient`; `nextId` — `UPDATE counter SET n = n + 1 WHERE name = ? RETURNING n` → `t-001` / `f-001`; `insert` пишет строку FTS через `RussianStems.index(fact + " " + quote)`, если факт `active`; нарушение `fact_one_active` → STATE-004; `RAISE` триггера → STATE-010), общий маппер `FactRows`, `Counter`;
  - `infrastructure/search/FtsFactSearch` — SQL recall из design с `LIMIT ?`; `compact()` — FTS `optimize`;
  - `application.yml` — порт 8091, `jdbc:sqlite:${MEMORY_DB_PATH}?foreign_keys=on` без умолчания, Hikari `maximum-pool-size: 1`, `connection-init-sql: PRAGMA secure_delete=ON`, `sql.init.mode: always`, `separator: "@@"`.
- Тесты (`infrastructure/`, SQLite-файл в `@TempDir`, общий помощник `TestDatabase` с настройками как в `application.yml`): `JdbcTurnRepositoryTest` 7 (`nextId` по порядку и откат с транзакцией, round trip, `turn_one_open`, уникальный `external_id`, CHECK-и стёртого текста), `JdbcFactRepositoryTest` 8 (round trip и поиск, `fact_one_active` → STATE-004, цепочка замен, `appendOnly` → STATE-010, `eraseSuperseded`, CHECK-и забытого, FTS-строка при insert и удаление при смене статуса, `forgottenTextNotInFile`), `FtsFactSearchTest` 7 (`findsOtherWordForm`, только `stated` + `active` своего пользователя, заменённый не находится, `LIMIT`, `limitZero`, забытый не находится, запрос из одних стоп-слов).
- Проверка: `"$MVN" -pl wardrobe-memory clean test` — 65 из 65 прошли (22 новых + 43 из M1–M2). Первый прогон: 64 из 65, упал `JdbcFactRepositoryTest.factOneActive`.
- Переделка 1: в `factOneActive` я ожидал, что после отказа STATE-004 следующий id — `f-003`; пришёл `f-002`, потому что `UPDATE counter` откатился вместе с отказанной вставкой. Так и требуют plan и design (`nextId` «в транзакции вставки»), ошибка была в ожидании теста, не в коде: ожидание исправлено на `f-002` с пояснением в `as(...)`. Это правка теста, а не реализации, — записываю её здесь явно.
- Риски из прогноза («триггеры, FTS, `secure_delete`») не сработали: триггеры, FTS-строки и стирание байтов файла (`forgottenTextNotInFile`) прошли с первого раза; `?foreign_keys=on` проверен отдельным разовым тестом (`PRAGMA foreign_keys` = 1, `secure_delete` = 1), тест удалён.
- Отклонения:
  1. `TurnRepository` получил `insert` / `update` (как `FactRepository`) вместо `save` / `saveAll` из потока данных design; в plan методы порта хода не перечислены («…»). Одну транзакцию на `abandon` + `open` даст `TransactionTemplate` в M5.
  2. Классы адаптеров `public`, а не package-private, как `JpaItemRepository` в Л6: тесты лежат в `infrastructure/` (по plan), а `FtsFactSearch` из `search/` читает строки через `FactRows` из `persistence/`.
  3. `update(Fact)` пишет все столбцы, а не только изменяемые: иначе запрещённое изменение (тема, род, `standing`) молча терялось бы, а не ловилось триггером `fact_append_only`.
  4. Уникальный `external_id` и `turn_one_open` в адаптере не переводятся в коды (видно как `DataAccessException`): plan велит переводить только `fact_one_active` и триггер. STATE-008 при повторном `external_id` — дело `OpenTurnUseCase` (M5).
  5. `application.yml` в M3 не загружается ни одним тестом: контекст Spring поднимет `MemoryControllerTest` в M6.
- Факт: 3 мин (17:26:05–17:29:26 UTC), output 23 458 токенов, 7 вызовов модели, 1 переделка.

### M4: Экстрактор (адаптер модели)

- Сделано:
  - `application/Extractor` (порт: `extract(ExtractionInput) → List<ExtractorDecision>`), `ExtractionInput` (`user_text`, `assistant_text`, `candidates`; фабрика `of(turn, activeFacts)` — кандидаты D7), `ExtractorDecision` (все поля строками, как в ответе), `ExtractionFailedException` (код EXT-001 / EXT-002);
  - `infrastructure/extractor/OpenAiCompatibleExtractor` — `RestClient` → `POST {base}/chat/completions`, `model`, `temperature=0`, `response_format={"type":"json_object"}`, system = `ExtractorPrompt.SYSTEM`, user = JSON входа; `Authorization: Bearer …` только при непустом ключе; таймаут соединения и чтения — `SimpleClientHttpRequestFactory` из `EXTRACTOR_TIMEOUT`; одна попытка; `RestClientException` (сеть, таймаут, не 2xx) → EXT-001; нет `choices[0].message.content`, `content` не JSON после снятия ```` ```json ````, нет массива `decisions` → EXT-002; элемент не объект или поле не строка → в лог `extract reject element=<i> code=MEM-EXT-002`, отклоняется только он;
  - `ExtractorPrompt.SYSTEM` — дословно из design (сверено скриптом с `design.md:205-240`, совпадает);
  - `ExtractorProperties` (`@ConfigurationProperties("extractor")`, умолчания из design: `https://openrouter.ai/api/v1`, `deepseek/deepseek-v4.1-flash`, ключ пуст, 30 с; `toString` без ключа);
  - `wardrobe-memory/pom.xml` — `spring-boot-starter-restclient`.
- Тесты: `infrastructure/extractor/OpenAiCompatibleExtractorTest` 13 (`MockRestServiceServer`): `validJson` (тело запроса: модель, `temperature`, `json_object`, промпт, вход в snake_case), `emptyDecisions`, `markdownWrapperIsRemoved`, `contentNotJsonIsExt002`, `noDecisionsArrayIsExt002`, `completionWithoutContentIsExt002`, `serverErrorIsExt001` (500), `unauthorizedIsExt001` (401), `timeoutIsExt001`, `malformedElementIsRejectedAlone` (строка вместо объекта, число в `scope`, нет `standing` — отклонены, два валидных остались), `authorizationHeaderWithKey`, `noAuthorizationHeaderWithoutKey`, `keyIsNotInPropertiesToString`.
- Проверка: `"$MVN" -pl wardrobe-memory test` — 78 из 78 прошли (13 новых + 65 из M1–M3), с первого прогона.
- Отклонения:
  1. Два конструктора: публичный (Spring) ставит фабрику запросов с таймаутом и вызывает `restClient(builder, properties)`; пакетный `(RestClient, model)` — для теста: `MockRestServiceServer.bindTo(builder)` сам задаёт фабрику builder-а, и публичный конструктор её бы перезаписал. Следствие: тест `timeoutIsExt001` проверяет реакцию на `SocketTimeoutException` от мока, а не то, что таймаут фабрики реально срабатывает.
  2. `ExtractionFailedException` наследует `MemoryException` (несёт `ErrorCode`); design имя класса и коды даёт, иерархию — нет.
  3. Env `EXTRACTOR_*` читается через нестрогую привязку Spring к `extractor.*` (`@ConfigurationProperties`), без строк в `application.yml`; для этого на `WardrobeMemoryApplication` добавлен `@ConfigurationPropertiesScan` — файла нет в списке вехи.
  4. Отсутствующее поле элемента считается «поле не строка» → элемент отклоняется (design перечисляет только «не объект / поле не строка»).
  5. `ExtractionInput.of(turn, facts)` и вложенный `Candidate` — форма входа не задана в plan, поля — из design.
  6. Тест `keyIsNotInPropertiesToString` сверх списка plan: design требует «ключ в лог не пишется», а Spring печатает свойства при ошибке привязки.
- Факт: 4 мин (17:36:25–17:39:55 UTC), output 22 998 токенов, 18 вызовов модели (включая чтение plan, design, кода M1–M3), 0 переделок.

### M5: Use case и очередь экстракции

- Сделано:
  - `application/FactWriter` — `apply(turn, decision) → Optional<Fact>`: `SKIP` → пусто; неизвестный `op` → VAL-012; `SUPERSEDE` без `target_id` → VAL-013; `ADD` — `Fact.create` → `checkScopeFree(findActive)` → `insert`; `SUPERSEDE` — `findById` (NF-001) → `Fact.create(..., supersedes)` → `update(target.supersededBy(next))` → `insert(next)`; одно решение — одна транзакция `TransactionTemplate`; разбор `kind`/`standing` — `FactKind.parse` / `Standing.parse`;
  - `RecallUseCase` (VAL-001, VAL-010, `min(limit, memory.recall-limit)`, по умолчанию 5 — env `MEMORY_RECALL_LIMIT`; `filter(Fact::recallable)` → `RecallBlock.render`; лог `recall user_id ids`), `OpenTurnUseCase` (STATE-008 по `external_id`, `abandon()` прежнего открытого и новый ход в одной транзакции; ответ `Opened(turn, abandoned)`), `CloseTurnUseCase` (VAL-011, NF-002, STATE-002, `turn.close` → после коммита `ExtractionQueue.submit`), `RememberUseCase` (STATE-006 без открытого хода, решение `ADD`/`SUPERSEDE` со `standing=stated` → `FactWriter`), `ForgetUseCase` (VAL-009, STATE-006, `findChain` → NF-001, `forget` у головы, `erase` у прежних, `eraseUserText` у ходов-источников, `requestForget` у текущего — одна транзакция; после коммита `FactSearch.compact()`), `ExtractTurnUseCase` (D7, D11: загрузка хода и кандидатов, модель без транзакции, решения по одному с логом `accept`/`reject op scope code`, `ExtractionFailedException` → `failed` с кодом EXT, всё под `catch (Throwable)` → `failed` `MEM-INT-001: <класс>`), порт `ExtractionQueue`;
  - `infrastructure/queue/ExecutorExtractionQueue` — однопоточный `ExecutorService` (поток-демон `extraction`, `shutdown` в `@PreDestroy`);
  - `MemoryConfig` — бины `Clock.systemUTC()` и `TransactionTemplate`.
- Тесты (unit на фейках `test/.../fake/`: `FakeTurnRepository`, `FakeFactRepository` — с проверкой `fact_one_active`, `FakeFactSearch`, `FakeExtractor`, синхронная `FakeExtractionQueue`, общий `Fixtures`): `FactWriterTest` 8 (ADD, STATE-004, SUPERSEDE, SKIP, VAL-012, VAL-013, NF-001, коды полей VAL-002/006/007/009), `OpenTurnUseCaseTest` 5 (открытие, `abandon` прежнего — AC-17, чужой ход не трогается, STATE-008, VAL-001/008/011), `CloseTurnUseCaseTest` 5 (`pending` и очередь после записи, NF-002, STATE-002, STATE-007, VAL-001/008/011 без постановки в очередь), `RememberUseCaseTest` 7 (`stated` из открытого хода, `noOpenTurn` — AC-4, `quoteNotInTurn` — AC-3, замена — AC-5, NF-001 и VAL-009, чужой факт STATE-002, VAL-001), `ForgetUseCaseTest` 7 (цепочка забыта и стёрта, реплики ходов-источников стёрты, `forget_requested`, `compact()` один раз — AC-10; ход-источник = текущий ход; STATE-006, NF-001, VAL-009, STATE-001 у забытого и заменённого — AC-11, STATE-002), `RecallUseCaseTest` 6 (строки и заголовок, `inferred` не идёт — I6, `min(limit, N)`, `limit=0` и `N=0` → `""` — AC-14, VAL-010, VAL-001), `ExtractTurnUseCaseTest` 9 (`rejectsOneKeepsOthers` — AC-1, цитата из ответа ассистента отклонена — AC-2, кандидаты = все действующие `stated` + `inferred` — D7, remember-факт → `SKIP` и отказ I3 — D8, EXT → `failed` без фактов — AC-16, `Throwable` → `failed` INT-001, `forget_requested` → `done` без модели — I11, не `pending` — без действий, `SUPERSEDE` из экстрактора); `infrastructure/JdbcFactRepositoryTest.supersedeInOneTransaction` (`FactWriter` с реальной базой: замена без нарушения `fact_one_active`; затем замена, чья вставка падает на внешнем ключе после `update` старой, — `update` откатился, старая `active`, FTS-строка на месте — AC-6).
- Проверка: `"$MVN" -pl wardrobe-memory test` — 126 из 126 прошли (48 новых + 78 из M1–M4), с первого прогона.
- Отклонения:
  1. `Fact.checkId` и `Turn.checkUserId` (код M2) стали `public`: use case проверяют формат id и `user_id` до поиска — иначе `POST /facts/{bad}/forget` и `supersedes: "abc"` давали бы NF-001 вместо VAL-009 из контракта. Поэтому в `FactWriter` формат `target_id` проверяется до `findById` (design: сразу `findById` → NF-001).
  2. `FactWriter.apply` возвращает `Optional<Fact>` (пусто для `SKIP`) — ответ `POST /facts` нужен `FactView`; plan форму не задаёт.
  3. Сообщение STATE-002 для хода — `turn <id> belongs to another user` (в таблице кодов есть только вариант для факта).
  4. `POST /recall` без `q` трактуется как пустая реплика (пустой результат), без отдельного кода; без `limit` — `MEMORY_RECALL_LIMIT`. Свойство — `memory.recall-limit` через `@Value` (env `MEMORY_RECALL_LIMIT` по правилам Spring).
  5. `fail_reason` при `Throwable` — `MEM-INT-001: <простое имя класса>` (design: «класс ошибки»), при EXT — `MEM-EXT-00N: <сообщение>`.
  6. Логи сверх перечисленных в design: `close turn_id`, `remember fact_id turn_id`, `extract done turn_id decisions`, `accept op=SKIP target_id` — только id и коды, без текста (I9).
  7. `MemoryConfig` лежит в корневом пакете `org.example.wardrobe.memory` (plan перечисляет его в списке `application/`, но это конфигурация Spring).
  8. Тестовые помощники сверх plan: `fake/Fixtures` (фиксированные `Clock`, `TransactionTemplate` без базы, `assertCode`); поле `TestDatabase.tx` открыто пакету для `supersedeInOneTransaction`.
  9. Отдельного теста `ExecutorExtractionQueue` нет (plan не требует); асинхронность проверит `MemoryControllerTest.closeReturnsBeforeExtraction` в M6.
- Факт: 5 мин (17:39:55–17:45:20 UTC), output 42 673 токенов, 15 вызовов модели, 0 переделок.

### M6: HTTP API сервиса

- Сделано (`web/`): `MemoryController` — 6 маршрутов контракта: `POST /recall` 200, `POST /turns` 201, `POST /turns/close` 202 (сразу, экстракция в очереди), `POST /facts` 201 `FactView`, `POST /facts/{id}/forget` 200, `GET /facts?user_id=` 200 (только `active`, `stated` и `inferred`); запросы и ответы — records (`RecallRequest/Response`, `OpenTurnRequest/Response`, `CloseTurnRequest/Response`, `RememberRequest`, `ForgetRequest/Response`, `FactView`, `FactsResponse`, `ApiError`), snake_case — `spring.jackson.property-naming-strategy: SNAKE_CASE` в `application.yml`, даты — строки ISO-8601 UTC; `ApiExceptionHandler` — `MemoryException` по категории → 400/404/409, `EXTRACTOR`/`INTERNAL` и прочие `RuntimeException` → 500 `MEM-INT-001` `internal error` (детали в лог); `HttpMessageNotReadableException` → 400 VAL-015 (`request is malformed: <поле>` или `body is not valid JSON`, текст тела в ответ не попадает), `MethodArgumentTypeMismatchException` → 400 VAL-015, `MissingServletRequestParameterException` → 400 VAL-011 `<param> is required`.
- Тесты: `web/MemoryControllerTest` 11 (`@SpringBootTest` + MockMvc из `WebApplicationContext`, SQLite в статическом `@TempDir` через `@DynamicPropertySource MEMORY_DB_PATH`, `@MockitoBean Extractor`; у каждого теста свой `user_id`): `openTurn` (201, `abandoned`), `openTurnErrors` (409 STATE-008, 400 VAL-011, VAL-001), `closeReturnsBeforeExtraction` (202 приходит, пока экстрактор ждёт `CountDownLatch`; ход `pending`; после отпускания — `done`; AC-15), `closeErrors` (404 NF-002, 400 VAL-011), `rememberAndList` (все поля `FactView` в snake_case), `rememberErrors` (409 STATE-006, 400 VAL-006, VAL-003, 404 NF-001), `forget` (200 и поля ответа, повтор 409 STATE-001, 404 NF-001, 400 VAL-009, `GET /facts` пуст), `recall` (заголовок и строка), `recallLimitZeroIsEmpty` (`limit=0` → `""`, AC-14), `listShowsInferred` (факт `inferred` из экстракции виден в `GET /facts` с `standing`, `POST /recall` его не отдаёт — AC-9), `requestErrors` (битый JSON и `limit` строкой → VAL-015, `limit=-1` → VAL-010, пустой `user_id` → VAL-001, `GET /facts` без `user_id` → VAL-011).
- Проверка: `"$MVN" -pl wardrobe-memory verify` — BUILD SUCCESS, 137 из 137 прошли (11 новых + 126 из M1–M5), с первого прогона; jar `wardrobe-memory/target/wardrobe-memory-1.0-SNAPSHOT.jar`.
- Ручная проверка: `MEMORY_DB_PATH=<scratchpad>/manual.db EXTRACTOR_BASE_URL=http://127.0.0.1:9 java -jar wardrobe-memory/target/wardrobe-memory-1.0-SNAPSHOT.jar` — старт 1,0 с на порту 8091; `curl -X POST :8091/turns` → 201 `{"turn_id":"t-001","status":"open","abandoned":[]}`; `POST /facts` → 201 `f-001`; `POST /turns/close` → 202 `pending`; `POST /recall` «Что надеть вместо юбки?» → блок со строкой `f-001 | constraint | сегодня | Не носит юбки | «я не ношу юбки»`; `limit: 0` → `{"context":"","ids":[]}`; `GET /facts?user_id=me` → факт; без `user_id` → 400 VAL-011; ход после экстракции — `failed` `MEM-EXT-001: extractor unavailable: ResourceAccessException …Connection refused` (порт 9 закрыт намеренно — без сети и ключа). В логе сервиса только id и коды. Сервис остановлен.
- Отклонения:
  1. `GET /facts` вызывает `FactRepository.findActiveByUser` прямо из контроллера (после `Turn.checkUserId`): в plan нет use case для списка, а заводить его — сверх plan.
  2. `@AutoConfigureMockMvc` в Spring Boot 4 вынесен в отдельный модуль `spring-boot-webmvc-test`; вместо новой зависимости `MockMvc` собран из контекста через `MockMvcBuilders.webAppContextSetup` (spring-test уже есть в starter-test).
  3. Snake_case задан глобально свойством Jackson, а не аннотацией на каждом record.
  4. Даты в ответах — строки `Instant.toString()`, без настройки сериализации `java.time`.
  5. Ручная проверка — с `EXTRACTOR_BASE_URL` на закрытый порт, чтобы не ходить в сеть; экстракция с реальной моделью — дело M9.
  6. Отдельного теста на 500 `MEM-INT-001` нет: вызвать необработанную ошибку через API без подмены бинов нельзя; plan такого теста не требует.
- Факт: 4 мин (17:45:20–17:48:42 UTC), output 21 449 токенов, 10 вызовов модели, 0 переделок (одна неудачная запись implement.md скриптом — ошибка кодировки в heredoc, не код вехи; ручной прогон потерял 20 с на глоб zsh в URL с «?»).

### M7: MCP-сервер `wardrobe-memory-mcp`

- Сделано: `<module>wardrobe-memory-mcp</module>` в корневом `pom.xml`; `wardrobe-memory-mcp/pom.xml` (как `wardrobe-mcp/pom.xml`: `spring-ai-starter-mcp-server`, `spring-boot-starter-restclient`, `finalName wardrobe-memory-mcp`, boot-plugin; плюс starter-test); `WardrobeMemoryMcpApplication`; `client/MemoryApiClient` — `remember` → `POST /facts` (`user_id` из `MEMORY_USER_ID`, `supersedes` только если задан), `forget` → `POST /facts/{id}/forget {user_id}`, `list` → `GET /facts?user_id=`; ответ сервиса — строкой как есть; `RestClientResponseException` → тело `{"code","error"}` → `MemoryApiException("MEM-…: сообщение")`, `ResourceAccessException` → «Сервис памяти недоступен»; `client/MemoryApiException`; `tool/MemoryTools` — `memory_remember(fact, quote, kind, scope, supersedes?)`, `memory_forget(id)`, `memory_list()` с описаниями по design и аннотациями, как `wardrobe_search`; ошибка → `CallToolResult.isError=true` с текстом исключения; `application.yml` (stdio, без баннера и консольного лога, лог `${java.io.tmpdir}/wardrobe-memory-mcp.log`, `memory.url: ${MEMORY_URL}`, `memory.user-id: ${MEMORY_USER_ID}`).
- Тесты: `tool/MemoryToolsTest` 7 (`MockRestServiceServer`): `rememberReturnsServiceJsonAsText` (тело запроса в snake_case, без `supersedes`), `rememberPassesSupersedes`, `conflictIsErrorWithCode` (409 → `isError`, `MEM-STATE-006: …`), `badRequestIsErrorWithCode` (400 VAL-009), `serviceDownIsError` (`ConnectException` → «Сервис памяти недоступен»), `forgetSendsUserId`, `listShowsStanding` (AC-9: `standing` в тексте ответа).
- Проверка: `"$MVN" -pl wardrobe-memory-mcp verify` — BUILD SUCCESS, 7 из 7 прошли, с первого прогона; jar `wardrobe-memory-mcp/target/wardrobe-memory-mcp.jar`.
- Ручная проверка: `initialize` + `tools/list` по stdio в `java -jar …` — сервер `wardrobe-memory`, три tool с описаниями и схемами, stdout чистый.
- Отклонения:
  1. `kind` — строка с перечнем значений в описании, а не enum, как `ItemType` в `wardrobe_search`: список и VAL-002 держит сервис, plan тип не задаёт.
  2. Тело ошибки, которое не разобралось как `{"code","error"}` (например, HTML прокси), → `MEM-INT-001: HTTP <статус>`; design описывает только ответ сервиса.
  3. Тесты сверх трёх из plan: `rememberPassesSupersedes`, `forgetSendsUserId`, `listShowsStanding`, 400 отдельно от 409.
  4. В `pom.xml` модуля добавлен `spring-boot-starter-test` (в `wardrobe-mcp` тестов нет, образца нет).
- Факт: 3 мин (17:52:38–17:55:06 UTC; в окно вошло чтение plan, design, `implement.md` и `wardrobe-mcp`), output 11 144 токенов, 12 вызовов модели, 0 переделок.

### M8: Хук `wardrobe-memory-hook`

- Сделано: `<module>wardrobe-memory-hook</module>` в корневом `pom.xml`; `wardrobe-memory-hook/pom.xml` (без Spring: `tools.jackson.core:jackson-databind` из BOM Boot; `maven-shade-plugin` 3.6.2 → `wardrobe-memory-hook.jar` с `Main-Class: …hook.HookMain`; starter-test для тестов); `HookMain` (`Hook.run(System.in, System.out, System.getenv())` → `System.exit(0)`), `Hook` (`run(stdin, stdout, env)`: разбор, выбор события, всё под `catch (Throwable)` → код 0; pre — `POST /recall {user_id, q}` и `POST /turns {user_id, session_id, external_id=turn_id, user_text}` каждый в своём `try` (D10), печать `{"context"}` только при непустом; post — `POST /turns/close {user_id, external_id, assistant_text}`; env `MEMORY_URL` / `MEMORY_USER_ID` / `MEMORY_HOOK_TIMEOUT_MS` с умолчаниями design), `HookPayload` (`parse`: `hook_event_name`, `session_id`, `extra.turn_id`, `extra.user_message`, `extra.assistant_response` — поле, которое не строка, → `null`, D5), `MemoryClient` (JDK `HttpClient`, HTTP/1.1, `connectTimeout` и ожидание ответа `sendAsync(...).get(timeout)` — таймаут на запрос целиком; не 2xx → исключение со статусом и `code` сервиса).
- Тесты: `HookTest` 10 (JDK `HttpServer` как фейк, записывает пути и тела): `preRecallsOpensTurnAndPrintsContext` (stdout — ровно `{"context"}`, тела обоих запросов), `emptyContextPrintsNothing`, `recallFailsTurnStillOpens` (500 на recall — `/turns` вызван, D10), `openFailsContextStillPrinted` (409 на `/turns` — context напечатан), `postClosesTurn`, `serviceDown` (закрытый порт → пусто, 0, < 2,5 с), `serviceSlow` (сервис спит 3 с, таймаут 300 мс: pre < 2·300+500 мс, post < 300+500 мс), `badJson` (без запросов), `userMessageNotStringOpensNothing` (список частей — D5), `otherEventDoesNothing`.
- Проверка: `"$MVN" -pl wardrobe-memory-hook verify` — BUILD SUCCESS, 10 из 10 прошли; fat jar 2,6 МБ.
- Ручная проверка: `time java -jar wardrobe-memory-hook/target/wardrobe-memory-hook.jar < payload.json` при остановленном сервисе — пусто, `exit=0`, 0,59 с (< 5 с `timeout` Hermes, AC-18). С запущенным сервисом (`MEMORY_DB_PATH` в scratchpad, экстрактор на закрытом порту): pre «Запомни: я не ношу юбки» → ход `t-001`; `POST /facts` → `f-001`; post → `close turn_id=t-001`; pre «Что надеть вместо юбки?» → `{"context":"Память о пользователе … f-001 | constraint | сегодня | Не носит юбки | «я не ношу юбки»"}`, 0,55 с; в логе сервиса только id. Сервис остановлен.
- Отклонения:
  1. `serviceSlow` проверяет pre с границей `2 × таймаут + 500 мс`, а не `таймаут + 500 мс` из стратегии тестов design: в pre два запроса, у каждого свой таймаут (design сам считает «≤ 2 с»); граница `таймаут + 500 мс` проверена на post (один запрос).
  2. В stderr хука пишется строка `wardrobe-memory-hook <шаг> failed <класс>[: <путь> HTTP <статус> <код>]` — без текста реплики и ответа (I9); design говорит «ничего не пишет в лог, кроме кодов ошибок и id», куда именно — не задано.
  3. Тесты сверх списка plan: `openFailsContextStillPrinted`, `userMessageNotStringOpensNothing`, `otherEventDoesNothing`.
  4. В post `user_message` не нужен: закрытие хода идёт при строковых `turn_id` и `assistant_response`; без них — ничего.
  5. В `maven-shade-plugin` у `<transformers>` стоит `combine.self="override"`: иначе конфигурация сливается с `pluginManagement` `spring-boot-starter-parent` (его трансформеры с `<resource>`), и сборка падает.
- Факт: 3 мин (17:55:06–17:58:09 UTC), output 16 408 токенов, 11 вызовов модели, 1 переделка — `package` упал на конфигурации shade (тесты были зелёными с первого прогона); ручной прогон потерял ~20 с на глоб zsh в URL с «?» и `$H` одной строкой.

### M9: Интеграция с Hermes

- Сделано (вне репозитория): резервные копии `~/.hermes/profiles/l78/config.yaml.bak-20261017T175839` и `.env.bak-20261017T175839`; в `config.yaml` `mcp_servers.user_memory` (бинарник примера) заменён на `wardrobe_memory` (`java -jar …/wardrobe-memory-mcp/target/wardrobe-memory-mcp.jar`, env `MEMORY_URL`/`MEMORY_USER_ID`, `timeout: 10`), хуки `pre_llm_call`/`post_llm_call` — на `java -jar …/wardrobe-memory-hook/target/wardrobe-memory-hook.jar` (`timeout: 5`) — дословно по design; `wardrobe`, `memory`, `auxiliary`, `agent`, `hooks_auto_accept` не тронуты; в `.env` `USER_MEMORY_URL`/`USER_MEMORY_USER_ID` → `MEMORY_URL=http://127.0.0.1:8091`, `MEMORY_USER_ID=me`. Сервис примера на 8765 не был запущен (порт свободен).
- Проверка: `"$MVN" clean verify` (корень) — BUILD SUCCESS, все 6 модулей реактора; тесты 137 (`wardrobe-memory`) + 7 (`wardrobe-memory-mcp`) + 10 (`wardrobe-memory-hook`) = 154 из 154.
- Ручные проверки (сервис: `MEMORY_DB_PATH=<scratchpad>/m9.db`, `EXTRACTOR_API_KEY` из `OPENROUTER_API_KEY` профиля, модель по умолчанию `deepseek/deepseek-v4.1-flash`; `wardrobe-api` на 8090):
  - `hermes -p l78 hooks list` — оба события, наш jar, `timeout=5s` (до первого прогона «not allowlisted», `hooks_auto_accept: true` принял их в `chat`);
  - `hermes -p l78 mcp test wardrobe_memory` — подключение 1,6 с, найдено 3 tool: `memory_forget`, `memory_list`, `memory_remember`;
  - засев S1: `hermes -p l78 chat -q "Я не ношу юбки, мне в них неуютно" -Q` → в логе сервиса `recall ids=[]`, `open t-001`, `close t-001`, `accept op=ADD fact_id=f-001 scope=skirts`; `GET /facts` — `f-001 constraint skirts stated`, цитата дословно. Агент сам `memory_remember` не вызвал (просьбы «запомни» не было — Р5), факт записал экстрактор;
  - вход S1: `chat -q "Собери мне образ на свидание, можно с юбкой или платьем" -Q` → `recall user_id=me ids=[f-001]` (AC-20), в `wardrobe-mcp.log` `GET /api/items excludeType=[SKIRT]`, ответ ссылается на f-001 и без вещей 17–18 — S1 пройден;
  - `curl -X POST :8091/recall` с входом S1 → `ids:["f-001"]`, блок с заголовком и строкой `f-001 | constraint | сегодня | …`;
  - мутация: сервис перезапущен с `MEMORY_RECALL_LIMIT=0` на той же базе → `curl /recall` → `{"context":"","ids":[]}`; `chat` с входом S1 → `recall ids=[]`, `excludeType=[]` — S1 не проходит (AC-21);
  - сервис памяти остановлен → `chat` с входом S1 отвечает (22 с), `GET /api/items` вызван, хуки молча пусты (AC-22);
  - `git diff 2490b15 -- wardrobe-api wardrobe-mcp` — 0 строк, `curl :8090/api/items` → 200 (AC-23).
  - После проверок сервис памяти и `wardrobe-api` остановлены; профиль `l78` оставлен на новых бинарниках.
- Отклонения:
  1. Сценарии S2, S3 и мутация как полный замер в `scenarios/` не прогонялись: в plan у M9 только S1 и мутация; S1 и мутация проверены вручную, а не скриптом.
  2. Ключ экстрактора сервису передан через env `EXTRACTOR_API_KEY` при запуске из `OPENROUTER_API_KEY` профиля; способ запуска сервиса с ключом в plan/design не описан (только переменная).
  3. Allowlist хуков отдельно не правился: принят автоматически (`hooks_auto_accept: true`) при первом `chat`.
- Факт: 5 мин (17:58:09–18:02:54 UTC; три прогона `hermes chat` ≈ 1,7 мин из них), output 8 687 токенов, 12 вызовов модели, 0 переделок.

## Отклонения (сводка)

Все отклонения записаны в вехах выше: M1 — 3 (версия Lucene 9.12.3, список стоп-слов Lucene, основы в кавычках в `MATCH`), M2 — 5 (исключения целостности без кода, сообщение VAL-008 для `assistant_text`, типы в `Fact.create`, порядок проверок замены, «N дней назад» дословно), M3 — 5 (`insert`/`update` у `TurnRepository`, `public` адаптеры, `update` всех столбцов, без перевода `external_id`/`turn_one_open` в коды, `application.yml` без теста). Фич сверх plan нет. Одна правка теста — ожидание id в `factOneActive` (M3, переделка 1).

Второй запуск (M4–M6): M4 — 6 (два конструктора экстрактора и тест таймаута через мок, `ExtractionFailedException` наследует `MemoryException`, `@ConfigurationPropertiesScan` вне списка вехи, отсутствующее поле = «не строка», форма `ExtractionInput`, тест `toString` без ключа), M5 — 9 (`public` у `Fact.checkId` / `Turn.checkUserId` и VAL-009 до поиска, `Optional` из `FactWriter`, сообщение STATE-002 для хода, `q`/`limit` по умолчанию и `memory.recall-limit`, формат `fail_reason`, лишние логи с id, пакет `MemoryConfig`, тестовые помощники, нет отдельного теста очереди), M6 — 6 (`GET /facts` без use case, MockMvc без `spring-boot-webmvc-test`, глобальный snake_case, даты строками, ручная проверка без сети, нет теста на 500). Фич сверх plan нет. Правок тестов ради зелёного прогона нет.

Третий запуск (M7–M9): M7 — 4 (`kind` строкой, а не enum; ответ без `{"code"}` → `MEM-INT-001: HTTP <статус>`; тесты сверх plan; starter-test в pom), M8 — 5 (граница `serviceSlow` для pre `2 × таймаут + 500 мс`; строка ошибки в stderr; тесты сверх plan; post без `user_message`; `combine.self="override"` у shade), M9 — 3 (S2/S3 не прогонялись, ключ экстрактора через env при запуске, allowlist хуков — автоматически). Фич сверх plan нет. Правок тестов ради зелёного прогона нет.

## Сборка и тесты

- `"$MVN" clean verify` (корень, весь реактор): BUILD SUCCESS — `mcp_hermes`, `wardrobe-api`, `wardrobe-mcp`, `wardrobe-memory`; в `wardrobe-memory` 65 из 65 тестов прошли.
- `git diff 2490b15 -- wardrobe-api wardrobe-mcp` пуст: код Л6 не тронут (часть AC-23).
- Не проверено в этом запуске: старт приложения с `application.yml` (будет в M6), вехи M4–M9.

Второй запуск (после M6):
- `"$MVN" clean verify` (корень, весь реактор): BUILD SUCCESS — `mcp_hermes`, `wardrobe-api`, `wardrobe-mcp`, `wardrobe-memory`; в `wardrobe-memory` 137 из 137 тестов прошли.
- `git diff 2490b15 -- wardrobe-api wardrobe-mcp` пуст: код Л6 не тронут (часть AC-23).
- Старт приложения с `application.yml` проверен (`MemoryControllerTest` и ручной `java -jar` + `curl`, M6).
- Не проверено в этом запуске: экстракция с реальной моделью (M9), вехи M7–M9.

Третий запуск (после M9, финальный):
- `"$MVN" clean verify` (корень, весь реактор): BUILD SUCCESS — `mcp_hermes`, `wardrobe-api`, `wardrobe-mcp`, `wardrobe-memory`, `wardrobe-memory-mcp`, `wardrobe-memory-hook`; тесты 154 из 154 (137 + 7 + 10).
- `git diff 2490b15 -- wardrobe-api wardrobe-mcp` пуст (AC-23).
- Экстракция с реальной моделью проверена в M9 (засев S1 → `f-001`).
- Не проверено: сценарии S2, S3 и полный замер «до/после» (`scenarios/`) — вне вех plan.

## Факт против прогноза

| Веха | Минуты (прогноз / факт) | Токены, output (прогноз / факт) | Переделки (прогноз / факт) |
|------|-------------------------|---------------------------------|----------------------------|
| M1 | 15 / 4 | 15 000 / 10 325 | 1 / 0 |
| M2 | 20 / 3 | 30 000 / 24 158 | 1 / 0 |
| M3 | 35 / 3 | 45 000 / 23 458 | 2 / 1 |
| Итого M1–M3 | 70 / 10 | 90 000 / 57 941 | 4 / 1 |
| M4 | 15 / 4 | 20 000 / 22 998 | 1 / 0 |
| M5 | 40 / 5 | 55 000 / 42 673 | 1 / 0 |
| M6 | 25 / 4 | 30 000 / 21 449 | 1 / 0 |
| Итого M4–M6 | 80 / 13 | 105 000 / 87 120 | 3 / 0 |
| M7 | 15 / 3 | 15 000 / 11 144 | 0 / 0 |
| M8 | 20 / 3 | 20 000 / 16 408 | 1 / 1 |
| M9 | 25 / 5 | 15 000 / 8 687 | 1 / 0 |
| Итого M7–M9 | 60 / 11 | 50 000 / 36 239 | 2 / 1 |
| Итого M1–M9 | 210 / 34 | 245 000 / 181 300 | 9 / 2 |

Больше всего расхождение в минутах (в 7 раз меньше прогноза), особенно у M3: прогноз закладывал отладку триггеров, FTS и `secure_delete`, а всё это прошло с первого прогона — design уже содержал точный SQL схемы и триггеров, писать пришлось почти без решений. Токены ближе к прогнозу (64 %): объём кода и тестов совпал с ожиданием, сэкономлены только итерации.

M4–M6: минуты снова в 6 раз меньше прогноза, переделок нет — design задавал коды, SQL, промпт и поток данных, тесты прошли с первого прогона. Токены у M4 выше прогноза (115 %: в окно вехи вошло чтение plan, design и кода M1–M3), у M5 и M6 — 78 % и 72 %; больше всего расхождение у M5 по минутам (40 / 5): прогноз закладывал отладку транзакций и очереди, а она не понадобилась.

M7–M9: минуты в 5 раз меньше прогноза; больше всего расхождение у M9 (25 / 5): прогноз закладывал переделку профиля Hermes, а замена по готовому YAML из design прошла с первого раза, время ушло в основном на ожидание трёх `hermes chat`. Единственная переделка — M8, конфигурация `maven-shade-plugin` против `pluginManagement` Boot-родителя (прогноз её угадал). Токены — 72 % прогноза; у M9 58 %: вывод `hermes chat` в контекст почти не писал output.

## Затраты фазы

- Минуты: ≈ 11 (2026-10-17, 17:18:54–17:30:09 UTC), 1 сообщение автора, 30 вызовов модели.
- Токены: output 60 679; input 60; запись в кэш 121 586; чтение из кэша 3 603 748.
- Способ: сумма поля `usage` ответов модели (по одному на `message.id`) в журнале сессии `~/.claude/projects/…/72602981-….jsonl` (подагентов, `subagents/`, не было), время — по меткам журнала. Сверх вех (финальная сборка и итог): ≈ 1 мин, output 2 830. Запись итога и коммит после 17:30:09 не вошли.

Второй запуск (M4–M6):
- Минуты: ≈ 13 (2026-10-17, 17:36:25–17:49:27 UTC), 1 сообщение автора, 47 вызовов модели.
- Токены: output 88 948; input 94; запись в кэш 169 951; чтение из кэша 7 255 141.
- Способ: сумма поля `usage` ответов модели (по одному на `message.id`) в журнале `~/.claude/projects/…/fbd666b7-….jsonl` (подагентов, `subagents/`, не было), время — по меткам журнала. Сверх вех (финальная сборка и итог): ≈ 1 мин, output 4 684. Коммит итога после 17:49:27 не вошёл.

Третий запуск (M7–M9):
- Минуты: ≈ 10 (2026-10-17, 17:52:38–18:02:54 UTC), 1 сообщение автора, 36 вызовов модели.
- Токены: output 36 968; input 72; запись в кэш 121 471; чтение из кэша 4 514 878.
- Способ: сумма поля `usage` ответов модели (по одному на `message.id`) в журнале `~/.claude/projects/…/c8b94b34-….jsonl` (подагентов, `subagents/`, не было), время — по меткам журнала. Запись итога и коммит после 18:02:54 не вошли.
