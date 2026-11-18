CREATE TEMPORARY TABLE demo_products (
  id BIGINT PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  brief VARCHAR(300) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  image_seed VARCHAR(30) NOT NULL,
  zh_name VARCHAR(160) NOT NULL,
  zh_brief VARCHAR(300) NOT NULL,
  zh_description VARCHAR(2000) NOT NULL
) CHARACTER SET utf8mb4;

INSERT INTO demo_products VALUES
  (1, 'Learning Laptop', 'A practical laptop for coding labs',
   'Java, Angular and Docker learning workstation.', 1299.00, 'laptop',
   '学习笔记本电脑', '适合编程实验的实用笔记本电脑', '用于 Java、Angular 和 Docker 学习的工作站。'),
  (2, 'Mechanical Keyboard', 'Comfortable keyboard for long sessions',
   'Compact mechanical keyboard with tactile switches.', 89.90, 'keyboard',
   '机械键盘', '适合长时间学习的舒适键盘', '配备清晰段落感轴体的紧凑机械键盘。'),
  (3, 'Developer Monitor', '27-inch QHD display',
   'A clear display for IDE, terminal and browser side by side.', 349.50, 'monitor',
   '开发者显示器', '27 英寸 QHD 显示器', '可并排显示 IDE、终端和浏览器的清晰显示器。'),
  (4, 'USB-C Dock', 'Connect the complete workspace',
   'Dock with display, network and USB connectivity.', 119.00, 'dock',
   'USB-C 扩展坞', '连接完整开发工作区', '提供显示、网络和 USB 连接的扩展坞。'),
  (5, 'Learning Headset', 'Headset for online courses',
   'Closed-back headset with microphone.', 69.00, 'headset',
   '学习耳机', '在线课程专用耳机', '带麦克风的封闭式学习耳机。'),
  (6, 'Spring Notebook', 'Keep architecture notes',
   'A paper notebook for flows, APIs and database diagrams.', 12.50, 'notebook',
   'Spring 笔记本', '记录架构与流程', '用于记录流程、API 和数据库图的纸质笔记本。');
