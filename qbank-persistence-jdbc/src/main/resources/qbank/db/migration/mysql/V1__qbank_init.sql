CREATE TABLE qbank_subjects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    expected_questions_per_module INT NOT NULL,
    marks_per_question INT NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_modules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    subject_code VARCHAR(32) NOT NULL,
    module_number INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    question_count INT NOT NULL,
    last_import_filename VARCHAR(255),
    last_imported_by VARCHAR(100),
    last_imported_at BIGINT,
    last_error_count INT NOT NULL DEFAULT 0,
    last_warning_count INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_qbank_modules UNIQUE (subject_id, module_number),
    CONSTRAINT fk_qbank_modules_subj FOREIGN KEY (subject_id) REFERENCES qbank_subjects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_mcqs (
    id VARCHAR(64) PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    module_number INT NOT NULL,
    seq INT NOT NULL,
    text_iv VARCHAR(64) NOT NULL,
    text_cipher LONGTEXT NOT NULL,
    options_iv VARCHAR(64) NOT NULL,
    options_cipher LONGTEXT NOT NULL,
    correct_iv VARCHAR(64) NOT NULL,
    correct_cipher TEXT NOT NULL,
    normalized_hash VARCHAR(64) NOT NULL,
    source_row INT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_qbank_mcqs_subj FOREIGN KEY (subject_id) REFERENCES qbank_subjects(id),
    INDEX idx_qbank_mcqs_mod (subject_id, module_number, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_import_previews (
    id VARCHAR(64) PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    module_number INT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    sheet_name VARCHAR(128) NOT NULL,
    payload_iv VARCHAR(64) NOT NULL,
    payload_cipher LONGTEXT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_exams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    subject_code VARCHAR(32) NOT NULL,
    subject_name VARCHAR(200) NOT NULL,
    title VARCHAR(255) NOT NULL,
    exam_type VARCHAR(32) NOT NULL,
    exam_date VARCHAR(16) NOT NULL,
    marks_per_question INT NOT NULL,
    total_marks INT NOT NULL,
    total_questions INT NOT NULL,
    selected_modules_json TEXT NOT NULL,
    blueprint_json TEXT NOT NULL,
    rotation_used INT NOT NULL,
    rng_seed BIGINT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_qbank_exams_subj FOREIGN KEY (subject_id) REFERENCES qbank_subjects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_exam_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    session_slot VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    chosen_set_number INT,
    scheduled_start_at BIGINT NOT NULL,
    scheduled_end_at BIGINT NOT NULL,
    actual_started_at BIGINT,
    actual_ended_at BIGINT,
    started_by VARCHAR(100),
    ended_by VARCHAR(100),
    frozen_by VARCHAR(100),
    frozen_at BIGINT,
    content_sha256 VARCHAR(64),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_qbank_exam_sessions UNIQUE (exam_id, session_slot),
    CONSTRAINT fk_qbank_sessions_exam FOREIGN KEY (exam_id) REFERENCES qbank_exams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_paper_sets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    set_number INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    question_count INT NOT NULL,
    question_ids_json LONGTEXT NOT NULL,
    CONSTRAINT uq_qbank_paper_sets UNIQUE (session_id, set_number),
    CONSTRAINT fk_qbank_sets_session FOREIGN KEY (session_id) REFERENCES qbank_exam_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_paper_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_set_id BIGINT NOT NULL,
    version_label VARCHAR(4) NOT NULL,
    questions_iv VARCHAR(64) NOT NULL,
    questions_cipher LONGTEXT NOT NULL,
    answer_key_iv VARCHAR(64) NOT NULL,
    answer_key_cipher LONGTEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT uq_qbank_paper_versions UNIQUE (paper_set_id, version_label),
    CONSTRAINT fk_qbank_versions_set FOREIGN KEY (paper_set_id) REFERENCES qbank_paper_sets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_exam_students (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    roll_number VARCHAR(64) NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    room_code VARCHAR(64) NOT NULL,
    row_number INT,
    seat_number INT,
    assigned_version VARCHAR(4) NOT NULL,
    access_code_hash VARCHAR(200) NOT NULL,
    login_state VARCHAR(24) NOT NULL,
    active_token_jti VARCHAR(64),
    font_scale DOUBLE NOT NULL DEFAULT 1.0,
    high_contrast BOOLEAN NOT NULL DEFAULT FALSE,
    accommodation_extra_minutes INT NOT NULL DEFAULT 0,
    granted_extra_minutes INT NOT NULL DEFAULT 0,
    last_question_index INT NOT NULL DEFAULT 0,
    last_heartbeat_at BIGINT,
    finished_at BIGINT,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_qbank_exam_students UNIQUE (session_id, roll_number),
    CONSTRAINT fk_qbank_students_session FOREIGN KEY (session_id) REFERENCES qbank_exam_sessions(id),
    INDEX idx_qbank_students_room (session_id, room_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_student_responses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    roll_number VARCHAR(64) NOT NULL,
    question_position INT NOT NULL,
    selected_option_index INT,
    version_label VARCHAR(4) NOT NULL,
    client_timestamp BIGINT,
    server_received_at BIGINT NOT NULL,
    CONSTRAINT uq_qbank_student_responses UNIQUE (session_id, roll_number, question_position),
    CONSTRAINT fk_qbank_responses_session FOREIGN KEY (session_id) REFERENCES qbank_exam_sessions(id),
    INDEX idx_qbank_responses_session (session_id, roll_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_extra_time_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    room_code VARCHAR(64),
    roll_number VARCHAR(64),
    extra_minutes INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    granted_by VARCHAR(100) NOT NULL,
    granted_at BIGINT NOT NULL,
    CONSTRAINT fk_qbank_grants_session FOREIGN KEY (session_id) REFERENCES qbank_exam_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(150) NOT NULL,
    role VARCHAR(32) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_user_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    username VARCHAR(80) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT fk_qbank_usessions_user FOREIGN KEY (user_id) REFERENCES qbank_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE qbank_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seq_no BIGINT NOT NULL UNIQUE,
    event_type VARCHAR(80) NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    details_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    prev_hash VARCHAR(64) NOT NULL,
    entry_hash VARCHAR(64) NOT NULL,
    INDEX idx_qbank_audit_seq (seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
