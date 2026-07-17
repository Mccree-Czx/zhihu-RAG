-- ============================================================
-- V1: Initial schema for Enterprise RAG Knowledge Base QA System
-- Charset: utf8mb4
-- ============================================================

-- ---------------------------
-- User / Role (RBAC)
-- ---------------------------
CREATE TABLE sys_user (
    id           BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID',
    username     VARCHAR(64)  NOT NULL COMMENT '用户名',
    password     VARCHAR(100) NOT NULL COMMENT 'BCrypt密码',
    nickname     VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    email        VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

CREATE TABLE sys_role (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '主键ID',
    role_code  VARCHAR(32) NOT NULL COMMENT '角色编码: ADMIN/USER',
    role_name  VARCHAR(64) NOT NULL COMMENT '角色名称',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

CREATE TABLE sys_user_role (
    id      BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户角色关联表';

-- ---------------------------
-- Knowledge base
-- ---------------------------
CREATE TABLE kb_category (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '主键ID',
    name       VARCHAR(64) NOT NULL COMMENT '分类名称',
    parent_id  BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID, 0为根',
    sort       INT         NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库分类表';

CREATE TABLE kb_tag (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '主键ID',
    name       VARCHAR(64) NOT NULL COMMENT '标签名称',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_tag_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库标签表';

CREATE TABLE kb_document (
    id           BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID',
    title        VARCHAR(255) NOT NULL COMMENT '文档标题',
    category_id  BIGINT       DEFAULT NULL COMMENT '分类ID',
    file_name    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_type    VARCHAR(32)  NOT NULL COMMENT '文件类型 pdf/docx/txt/md',
    file_size    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    storage_path VARCHAR(512) NOT NULL COMMENT '存储路径',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PARSING' COMMENT '状态 PARSING/INDEXED/FAILED',
    error_msg    VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '分块数量',
    uploaded_by  BIGINT       NOT NULL COMMENT '上传人ID',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_category (category_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档表';

CREATE TABLE kb_document_tag (
    id          BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    tag_id      BIGINT NOT NULL COMMENT '标签ID',
    UNIQUE KEY uk_doc_tag (document_id, tag_id),
    KEY idx_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文档标签关联表';

CREATE TABLE kb_chunk (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID(与Milvus主键一致)',
    document_id BIGINT       NOT NULL COMMENT '文档ID',
    chunk_index INT          NOT NULL COMMENT '分块序号',
    content     TEXT         NOT NULL COMMENT '分块文本',
    token_count INT          NOT NULL DEFAULT 0 COMMENT 'token数量估计',
    milvus_id   VARCHAR(64)  DEFAULT NULL COMMENT 'Milvus向量ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_document (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库分块元数据表';

-- ---------------------------
-- Chat / Session
-- ---------------------------
CREATE TABLE chat_session (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    title       VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT '会话标题',
    is_favorite TINYINT      NOT NULL DEFAULT 0 COMMENT '是否收藏',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_user (user_id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话表';

CREATE TABLE chat_message (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '主键ID',
    session_id BIGINT      NOT NULL COMMENT '会话ID',
    role       VARCHAR(16) NOT NULL COMMENT '角色 user/assistant',
    content    MEDIUMTEXT  NOT NULL COMMENT '消息内容',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话消息表';

CREATE TABLE chat_message_source (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID',
    message_id  BIGINT       NOT NULL COMMENT '消息ID',
    chunk_id    BIGINT       DEFAULT NULL COMMENT '分块ID',
    document_id BIGINT       DEFAULT NULL COMMENT '文档ID',
    doc_title   VARCHAR(255) DEFAULT NULL COMMENT '文档标题(冗余)',
    score       DOUBLE       NOT NULL DEFAULT 0 COMMENT '相似度得分',
    snippet     TEXT         DEFAULT NULL COMMENT '引用片段',
    KEY idx_message (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='消息引用来源表';

-- ---------------------------
-- Audit log
-- ---------------------------
CREATE TABLE sys_audit_log (
    id         BIGINT       NOT NULL PRIMARY KEY COMMENT '主键ID',
    user_id    BIGINT       DEFAULT NULL COMMENT '操作人ID',
    username   VARCHAR(64)  DEFAULT NULL COMMENT '操作人用户名',
    action     VARCHAR(64)  NOT NULL COMMENT '操作类型',
    detail     VARCHAR(1024) DEFAULT NULL COMMENT '操作详情',
    ip         VARCHAR(64)  DEFAULT NULL COMMENT 'IP地址',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_user (user_id),
    KEY idx_action (action)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='操作审计日志表';

-- ---------------------------
-- Seed roles (admin user is seeded programmatically with a BCrypt hash)
-- ---------------------------
INSERT INTO sys_role (id, role_code, role_name) VALUES
    (1, 'ADMIN', '管理员'),
    (2, 'USER', '普通用户');
