CREATE TABLE item (
    id          BIGINT       PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    type        VARCHAR(30)  NOT NULL,
    season      VARCHAR(30)  NOT NULL
);

CREATE TABLE item_color (
    item_id BIGINT      NOT NULL REFERENCES item (id),
    color   VARCHAR(30) NOT NULL,
    PRIMARY KEY (item_id, color)
);
