CREATE TABLE IF NOT EXISTS app_users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(190) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  first_name VARCHAR(80) NOT NULL,
  last_name VARCHAR(80) NOT NULL,
  phone VARCHAR(40) NOT NULL,
  role VARCHAR(30) NOT NULL,
  enabled BOOLEAN NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_app_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS products (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  brief VARCHAR(300) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  price DECIMAL(12, 2) NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS customer_orders (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  note VARCHAR(500) NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  CONSTRAINT fk_customer_orders_user FOREIGN KEY (user_id) REFERENCES app_users (id),
  CONSTRAINT fk_customer_orders_product FOREIGN KEY (product_id) REFERENCES products (id)
);

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'demo@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Demo', 'Student', '+90 555 000 0001', 'USER', TRUE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'demo@example.com');

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'ada@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Ada', 'Lovelace', '+44 20 0000 0001', 'USER', FALSE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'ada@example.com');

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'grace@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Grace', 'Hopper', '+1 212 000 0002', 'USER', FALSE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'grace@example.com');

INSERT INTO app_users (email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 'alan@example.com', '$2a$10$.vOuqtr8OlOAWivaL/d9te.Y3JkyUNC.T1cBG3eHPG/U8zv0e1Gg6',
       'Alan', 'Turing', '+44 20 0000 0003', 'USER', FALSE
WHERE NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'alan@example.com');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'Learning Laptop', 'A practical laptop for coding labs',
       'Java, Angular and Docker learning workstation.', 1299.00,
       'https://picsum.photos/seed/laptop/800/500', 'https://picsum.photos/seed/laptop/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Learning Laptop');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'Mechanical Keyboard', 'Comfortable keyboard for long sessions',
       'Compact mechanical keyboard with tactile switches.', 89.90,
       'https://picsum.photos/seed/keyboard/800/500', 'https://picsum.photos/seed/keyboard/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Mechanical Keyboard');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'Developer Monitor', '27-inch QHD display',
       'A clear display for IDE, terminal and browser side by side.', 349.50,
       'https://picsum.photos/seed/monitor/800/500', 'https://picsum.photos/seed/monitor/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Developer Monitor');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'USB-C Dock', 'Connect the complete workspace',
       'Dock with display, network and USB connectivity.', 119.00,
       'https://picsum.photos/seed/dock/800/500', 'https://picsum.photos/seed/dock/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'USB-C Dock');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'Learning Headset', 'Headset for online courses',
       'Closed-back headset with microphone.', 69.00,
       'https://picsum.photos/seed/headset/800/500', 'https://picsum.photos/seed/headset/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Learning Headset');

INSERT INTO products (name, brief, description, price, image_url, thumbnail_url)
SELECT 'Spring Notebook', 'Keep architecture notes',
       'A paper notebook for flows, APIs and database diagrams.', 12.50,
       'https://picsum.photos/seed/notebook/800/500', 'https://picsum.photos/seed/notebook/160/100'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Spring Notebook');
