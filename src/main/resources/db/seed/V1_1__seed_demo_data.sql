-- REQ-DA-003 demo seed data. Applied only by the local profile (spring.flyway.locations includes db/seed).
-- No real brand names. Passwords are BCrypt hashes only; the demo passwords are listed in README.md.

INSERT INTO categories (id, name) VALUES
    (1, '생활용품'),
    (2, '주방'),
    (3, '문구'),
    (4, '디지털 액세서리');

INSERT INTO products (id, category_id, name, description, price, stock, image_url, created_at, updated_at) VALUES
    (1,  1, '무지 면 수건 3입',        '도톰한 면 소재의 기본 수건 세트입니다.',            12900,  80, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (2,  1, '접이식 빨래 바구니',      '사용하지 않을 때 납작하게 접어 보관합니다.',        15900,  45, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (3,  1, '향 없는 순한 비누 4입',    '자극을 줄인 무향 고체 비누입니다.',                  8900, 100, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (4,  1, '규조토 발매트',            '물기를 빠르게 흡수하는 발매트입니다.',              19900,  30, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (5,  1, '한정판 리넨 쿠션 커버',    '동시 주문 시연용 한정 수량 상품입니다.',            24900,  10, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (6,  2, '스테인리스 텀블러 500ml',  '보온·보냉이 되는 이중 구조 텀블러입니다.',          17900,  60, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (7,  2, '원목 도마 중형',           '단단한 원목으로 만든 도마입니다.',                  22900,  25, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (8,  2, '실리콘 주걱 2종',          '내열 실리콘 주걱 세트입니다.',                       9900,  70, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (9,  2, '유리 밀폐용기 4종',        '전자레인지에 쓸 수 있는 유리 용기입니다.',          29900,  40, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (10, 2, '핸드드립 주전자',          '물줄기 조절이 쉬운 가는 주둥이 주전자입니다.',      34900,   0, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (11, 3, '모눈 노트 A5',             '5mm 모눈 내지의 노트입니다.',                        4900,  90, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (12, 3, '젤 볼펜 0.5mm 5입',        '부드럽게 써지는 검정 젤 볼펜입니다.',                6500, 100, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (13, 3, '탁상 달력',                '메모 칸이 넓은 탁상 달력입니다.',                    7900,  55, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (14, 3, '마스킹 테이프 세트',       '무지 색상 마스킹 테이프 6롤입니다.',                 5900,  65, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (15, 3, '가죽 느낌 펜 파우치',      '펜과 작은 소품을 담는 파우치입니다.',               11900,  35, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (16, 4, 'USB-C 충전 케이블 1m',     '고속 충전을 지원하는 편조 케이블입니다.',            8900,  85, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (17, 4, '알루미늄 노트북 거치대',   '높이와 각도를 조절할 수 있는 거치대입니다.',        32900,  20, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (18, 4, '무선 마우스 패드',         '미끄럼 방지 바닥의 마우스 패드입니다.',              9900,  75, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (19, 4, '멀티 포트 USB 허브',       'USB-A 3포트와 USB-C 1포트를 제공합니다.',           27900,  15, '/images/products/placeholder.svg', NOW(6), NOW(6)),
    (20, 4, '케이블 정리 클립 10입',    '책상 위 케이블을 정리하는 클립입니다.',              3900,  95, '/images/products/placeholder.svg', NOW(6), NOW(6));

INSERT INTO members (id, email, password_hash, name, role, created_at) VALUES
    (1, 'user1@shop.local', '$2y$10$rdEKw0.jvP86Dr/hyToc..NhSe0Q8e7xr18yAXz0IyGbuMQHga9dm', '사용자1', 'USER', NOW(6)),
    (2, 'user2@shop.local', '$2y$10$o.EIPoUXSS8cLcMwy5ltXe6tDDTPkL1LiCNHHqJmxFd9JCTZh5Xma', '사용자2', 'USER', NOW(6)),
    (3, 'admin@shop.local', '$2y$10$mmy9cWXaaVAizURmH6334O0RsqdDEh41X2znB7dI83MCvI7g6ZB9u', '관리자', 'ADMIN', NOW(6));
