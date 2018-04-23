CREATE TABLE IF NOT EXISTS weather2345 (
  id BIGINT NOT NULL AUTO_INCREMENT,
  city_code VARCHAR(32) NOT NULL ,
  city_name VARCHAR(32) NOT NULL ,
  weather VARCHAR(512) DEFAULT NULL ,
  dt INT NOT NULL ,
  create_time TIMESTAMP  DEFAULT current_timestamp ,
  update_time TIMESTAMP DEFAULT current_timestamp ON UPDATE current_timestamp,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_citycode_dt` (`city_code`, `dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='2345今日天气信息表';

INSERT INTO weather2345 (city_code, city_name, weather, dt) VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE city_name = values(city_name), weather = values(weather);