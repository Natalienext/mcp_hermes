-- Тестовый гардероб для wardrobe_search (30 вещей).
-- season: WINTER / SUMMER — только категорически сезонные вещи, всё остальное UNIVERSAL.
-- item_color: у вещи может быть несколько цветов; UNKNOWN — пользователь не смог назвать цвет.

-- Верх
INSERT INTO item (id, description, type, season) VALUES (1, 'футболка белая базовая, хлопок, прямой крой', 'T_SHIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (1, 'WHITE');
INSERT INTO item (id, description, type, season) VALUES (2, 'футболка чёрная облегающая, хлопок с эластаном', 'T_SHIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (2, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (3, 'футболка с цветочным принтом, свободная', 'T_SHIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (3, 'MULTICOLOR');
INSERT INTO item (id, description, type, season) VALUES (4, 'кроптоп красный в рубчик, на тонких бретелях', 'CROP_TOP', 'SUMMER');
INSERT INTO item_color (item_id, color) VALUES (4, 'RED');
INSERT INTO item (id, description, type, season) VALUES (5, 'рубашка в бело-голубую полоску, оверсайз, хлопок', 'SHIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (5, 'WHITE');
INSERT INTO item_color (item_id, color) VALUES (5, 'BLUE');
INSERT INTO item (id, description, type, season) VALUES (6, 'блузка бежевая шёлковая с запахом', 'BLOUSE', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (6, 'BEIGE');
INSERT INTO item (id, description, type, season) VALUES (7, 'водолазка чёрная облегающая, тонкая шерсть', 'TURTLENECK', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (7, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (8, 'свитер серый оверсайз, крупная вязка', 'SWEATER', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (8, 'GREY');
INSERT INTO item (id, description, type, season) VALUES (9, 'свитер толстый с красно-белым норвежским узором, шерсть с альпакой, горло', 'SWEATER', 'WINTER');
INSERT INTO item_color (item_id, color) VALUES (9, 'RED');
INSERT INTO item_color (item_id, color) VALUES (9, 'WHITE');
INSERT INTO item (id, description, type, season) VALUES (10, 'кардиган бежевый на пуговицах, мягкий трикотаж', 'CARDIGAN', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (10, 'BEIGE');
INSERT INTO item (id, description, type, season) VALUES (11, 'свитшот с выцветшим оттенком, цвет затрудняюсь назвать', 'SWEATSHIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (11, 'UNKNOWN');

-- Низ
INSERT INTO item (id, description, type, season) VALUES (12, 'джинсы синие широкие, высокая посадка', 'JEANS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (12, 'BLUE');
INSERT INTO item (id, description, type, season) VALUES (13, 'джинсы чёрные прямые', 'JEANS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (13, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (14, 'брюки чёрные клёш, костюмная ткань', 'TROUSERS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (14, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (15, 'брюки серые прямые, шерсть', 'TROUSERS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (15, 'GREY');
INSERT INTO item (id, description, type, season) VALUES (16, 'брюки льняные белые, свободные', 'TROUSERS', 'SUMMER');
INSERT INTO item_color (item_id, color) VALUES (16, 'WHITE');
INSERT INTO item (id, description, type, season) VALUES (17, 'юбка чёрная миди плиссированная', 'SKIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (17, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (18, 'юбка джинсовая синяя мини', 'SKIRT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (18, 'BLUE');
INSERT INTO item (id, description, type, season) VALUES (19, 'шорты джинсовые голубые, короткие', 'SHORTS', 'SUMMER');
INSERT INTO item_color (item_id, color) VALUES (19, 'BLUE');

-- Платья
INSERT INTO item (id, description, type, season) VALUES (20, 'платье красное миди, облегающее, трикотаж', 'DRESS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (20, 'RED');
INSERT INTO item (id, description, type, season) VALUES (21, 'сарафан жёлтый льняной на бретелях', 'DRESS', 'SUMMER');
INSERT INTO item_color (item_id, color) VALUES (21, 'YELLOW');
INSERT INTO item (id, description, type, season) VALUES (22, 'платье-свитер серое, тёплый трикотаж, ниже колена', 'DRESS', 'WINTER');
INSERT INTO item_color (item_id, color) VALUES (22, 'GREY');

-- Верхняя одежда
INSERT INTO item (id, description, type, season) VALUES (23, 'пуховик чёрный длинный, с капюшоном', 'PUFFER', 'WINTER');
INSERT INTO item_color (item_id, color) VALUES (23, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (24, 'тренч бежевый классический, с поясом', 'TRENCH', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (24, 'BEIGE');
INSERT INTO item (id, description, type, season) VALUES (25, 'пальто серое прямое, шерсть', 'COAT', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (25, 'GREY');
INSERT INTO item (id, description, type, season) VALUES (26, 'джинсовка синяя укороченная', 'JACKET', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (26, 'BLUE');

-- Обувь
INSERT INTO item (id, description, type, season) VALUES (27, 'кроссовки белые кожаные с бежевыми вставками', 'SNEAKERS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (27, 'WHITE');
INSERT INTO item_color (item_id, color) VALUES (27, 'BEIGE');
INSERT INTO item (id, description, type, season) VALUES (28, 'ботинки чёрные челси, кожа', 'BOOTS', 'UNIVERSAL');
INSERT INTO item_color (item_id, color) VALUES (28, 'BLACK');
INSERT INTO item (id, description, type, season) VALUES (29, 'угги коричневые, овчина', 'UGG_BOOTS', 'WINTER');
INSERT INTO item_color (item_id, color) VALUES (29, 'BROWN');
INSERT INTO item (id, description, type, season) VALUES (30, 'босоножки бежевые на плоской подошве', 'SANDALS', 'SUMMER');
INSERT INTO item_color (item_id, color) VALUES (30, 'BEIGE');
