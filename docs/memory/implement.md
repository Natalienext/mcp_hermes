# Implement: модуль памяти для Hermes (ДЗ Л7–Л8)

- **Дата:** 2026-10-17
- **Вход:** `docs/memory/plan.md` (вехи M1–M9, прогноз), `docs/memory/design.md`, `docs/memory/grill.md`.
- **Объём запуска:** вехи M1–M3 (по просьбе автора; M4–M9 — следующим запуском).
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
