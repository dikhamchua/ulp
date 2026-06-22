-- Phase Conversation 2026-06-22: Lessons learned from refactoring ulp/src/ package layout.
-- These backlog items record the friction (over-engineering) so future agents
-- avoid repeating the same mistakes on this codebase.

----------------------------------------------------------------------
-- Backlog #1: Avoid over-splitting DTO records into one-file-per-record
----------------------------------------------------------------------
INSERT INTO backlog (
    title,
    discovered_while,
    current_pain,
    suggested_improvement,
    risk,
    status,
    predicted_impact,
    actual_outcome,
    implemented_at,
    notes
) VALUES (
    'DTO records: gom theo feature trong 1 file, dung 1-file-per-record',
    'Tai cau truc package com.ulp.auth/profile theo layered architecture (controller/service/repository/dto/entity).',
    'Khi nguoi dung yeu cau "chia ro repository, dto, controller, service, ...", agent da tach AuthDtos.java (1 file chua 4 records) thanh 4 file rieng moi file chi 5-10 dong. Tao nhieu file thua, kho navigate, vi pham KISS/YAGNI.',
    'Voi DTO la Java record ngan (record FooRequest, BarRequest...), GOM theo feature trong 1 file (vi du AuthDtos.java chua ForgotPasswordRequest + ResetPasswordRequest; ProfileDtos.java chua ProfileUpdateRequest + ChangePasswordRequest). Chi tach file rieng khi DTO co > 30 dong, co javadoc dai, hoac co validation phuc tap.',
    'tiny',
    'implemented',
    'Giam so file thua, giu cau truc 1 dto/ per feature ma khong noi qua chi tiet.',
    'Da gop lai: auth/dto/AuthDtos.java (2 record) + profile/dto/ProfileDtos.java (2 record). Build SUCCESS (26 main + 4 test sources).',
    datetime('now'),
    'Codebase: ulp/. Rule cu the cho project Java Spring Boot nay. Lien quan: development-rules.md (KISS-DRY-YAGNI), CLAUDE.md.'
);

----------------------------------------------------------------------
-- Backlog #2: Dung tao package con cho chi 1-2 file
----------------------------------------------------------------------
INSERT INTO backlog (
    title,
    discovered_while,
    current_pain,
    suggested_improvement,
    risk,
    status,
    predicted_impact,
    actual_outcome,
    implemented_at,
    notes
) VALUES (
    'Package layout: khong tach sub-package cho 1-2 file co don',
    'Tai cau truc package com.ulp.auth: ban dau tach ra auth/enums/ (1 file Role.java) va auth/security/ (2 file UlpUserDetails, CustomOidcUserPrincipal).',
    'Tao package rieng cho 1 enum 16 dong (auth/enums/) va 2 principal helper (auth/security/) la over-engineer. Tang depth folder, agent kha nang phai mo nhieu cap thu muc moi tim duoc class.',
    'Quy tac: chi tao sub-package khi co >= 3 file LIEN QUAN. Enum / constants 1 file -> giu o package goc feature (vi du auth/Role.java). Principal/helper class cua service -> de chung trong service/ thay vi tao security/. DTO 1-2 record -> file rong AuthDtos.java o feature/dto/, khong can chia tiep.',
    'tiny',
    'implemented',
    'Cau truc phang vua du, depth folder thap, agent doc nhanh hon.',
    'Da gop: bo auth/enums/ -> Role.java len auth/. Bo auth/security/ -> 2 principal vao auth/service/. Build SUCCESS (26 main sources, giam 2 file).',
    datetime('now'),
    'Codebase: ulp/. Heuristic cho project Java/Spring Boot nay. Khi yeu cau cua nguoi dung mo ho ("chia ro X, Y, ..."), HOI lai pham vi truoc khi tach.'
);

----------------------------------------------------------------------
-- Backlog #3: Lombok da co - khong viet getter/setter thu cong
----------------------------------------------------------------------
INSERT INTO backlog (
    title,
    discovered_while,
    current_pain,
    suggested_improvement,
    risk,
    status,
    predicted_impact,
    actual_outcome,
    implemented_at,
    notes
) VALUES (
    'Entity: dung Lombok @Getter/@Setter, khong viet getter/setter thu cong',
    'Refactor entity User, PasswordResetToken, UserOAuthProvider sau khi them dependency org.projectlombok:lombok vao pom.xml.',
    'Entity goc cua project co rat nhieu getter/setter boilerplate (User.java 117 dong, PasswordResetToken 66 dong) du da chuan bi Lombok.',
    'Quy tac cho entity moi:
    - @Getter o class level (sinh getter cho moi field).
    - @Setter CHI tren field thuc su can setter (vi du password_hash, avatar_url). Khong @Setter o class level - de tranh expose setter cho id, email, role.
    - @NoArgsConstructor(access = AccessLevel.PROTECTED) thay constructor JPA thu cong.
    - Giu nguyen business method nhu updateProfile(), markUsed(), isValid().
    - KHONG dung Lombok cho class implement UserDetails/OidcUser - cac method getUsername()/getPassword() co logic rieng (vi du !locked, blankToNull).',
    'tiny',
    'implemented',
    'Giam ~30% dong code entity. User: 117 -> 87, PasswordResetToken: 66 -> 53, UserOAuthProvider: 53 -> 47.',
    'Da apply Lombok cho 3 entity. pom.xml them lombok dependency va exclude khoi jar cuoi qua spring-boot-maven-plugin <excludes>.',
    datetime('now'),
    'Codebase: ulp/. pom.xml: <groupId>org.projectlombok</groupId>. Build verify: ./mvnw clean test-compile.'
);

----------------------------------------------------------------------
-- Backlog #4: Hoi lai khi yeu cau mo ho thay vi du-doan
----------------------------------------------------------------------
INSERT INTO backlog (
    title,
    discovered_while,
    current_pain,
    suggested_improvement,
    risk,
    status,
    predicted_impact,
    actual_outcome,
    implemented_at,
    notes
) VALUES (
    'Process: hoi clarify khi yeu cau co dau "..." hoac mo ho',
    'Nguoi dung viet "chia ro ra, repository, dto, controller, service, ...". Agent dien giai dau "..." rong qua va tach moi loai class.',
    'Khi yeu cau mo ho, agent ap dung layered architecture mac dinh va tach ca cac nhom 1-2 file, dan den 2 backlog item dau (over-split DTOs, over-package).',
    'Truoc khi tai cau truc lon (anh huong > 10 file), HOI nguoi dung 1-2 cau clarify ngan:
    1. "Ban muon tach den muc nao? (chi controller/service/repository, hay tach ca DTO/enum/security?)"
    2. "Co class nho/enum nao nen GIU CHUNG cho gon khong?"
    Agent dung AskUserQuestion (mode "preview") de show 2-3 phuong an co cau truc cu the.',
    'tiny',
    'proposed',
    'Tranh round-trip refactor, giam toi thieu 1 luot lam lai cong viec.',
    NULL,
    NULL,
    'Trigger: yeu cau co tu "tach", "chia ra", "refactor cau truc", "reorganize" va co dau "..." hoac "vv".'
);
