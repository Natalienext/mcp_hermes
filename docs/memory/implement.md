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
