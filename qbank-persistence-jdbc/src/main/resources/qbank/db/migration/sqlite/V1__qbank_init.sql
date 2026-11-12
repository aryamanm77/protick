CREATE TABLE qbank_subjects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    expected_questions_per_module INTEGER NOT NULL,
    marks_per_question INTEGER NOT NULL,
    archived INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE qbank_modules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_id INTEGER NOT NULL REFERENCES qbank_subjects(id),
    subject_code TEXT NOT NULL,
    module_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    question_count INTEGER NOT NULL,
    last_import_filename TEXT,
    last_imported_by TEXT,
    last_imported_at INTEGER,
    last_error_count INTEGER NOT NULL DEFAULT 0,
    last_warning_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (subject_id, module_number)
);

CREATE TABLE qbank_mcqs (
    id TEXT PRIMARY KEY,
    subject_id INTEGER NOT NULL REFERENCES qbank_subjects(id),
    module_number INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    text_iv TEXT NOT NULL,
    text_cipher TEXT NOT NULL,
    options_iv TEXT NOT NULL,
    options_cipher TEXT NOT NULL,
    correct_iv TEXT NOT NULL,
    correct_cipher TEXT NOT NULL,
    normalized_hash TEXT NOT NULL,
    source_row INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX idx_qbank_mcqs_mod ON qbank_mcqs(subject_id, module_number, seq);

CREATE TABLE qbank_import_previews (
    id TEXT PRIMARY KEY,
    subject_id INTEGER NOT NULL,
    module_number INTEGER NOT NULL,
    filename TEXT NOT NULL,
    sheet_name TEXT NOT NULL,
    payload_iv TEXT NOT NULL,
    payload_cipher TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE qbank_exams (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_id INTEGER NOT NULL REFERENCES qbank_subjects(id),
    subject_code TEXT NOT NULL,
    subject_name TEXT NOT NULL,
    title TEXT NOT NULL,
    exam_type TEXT NOT NULL,
    exam_date TEXT NOT NULL,
    marks_per_question INTEGER NOT NULL,
    total_marks INTEGER NOT NULL,
    total_questions INTEGER NOT NULL,
    selected_modules_json TEXT NOT NULL,
    blueprint_json TEXT NOT NULL,
    rotation_used INTEGER NOT NULL,
    rng_seed INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE qbank_exam_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exam_id INTEGER NOT NULL REFERENCES qbank_exams(id),
    session_slot TEXT NOT NULL,
    status TEXT NOT NULL,
    chosen_set_number INTEGER,
    scheduled_start_at INTEGER NOT NULL,
    scheduled_end_at INTEGER NOT NULL,
    actual_started_at INTEGER,
    actual_ended_at INTEGER,
    started_by TEXT,
    ended_by TEXT,
    frozen_by TEXT,
    frozen_at INTEGER,
    content_sha256 TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (exam_id, session_slot)
);

CREATE TABLE qbank_paper_sets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES qbank_exam_sessions(id),
    set_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    question_count INTEGER NOT NULL,
    question_ids_json TEXT NOT NULL,
    UNIQUE (session_id, set_number)
);

CREATE TABLE qbank_paper_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    paper_set_id INTEGER NOT NULL REFERENCES qbank_paper_sets(id),
    version_label TEXT NOT NULL,
    questions_iv TEXT NOT NULL,
    questions_cipher TEXT NOT NULL,
    answer_key_iv TEXT NOT NULL,
    answer_key_cipher TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    UNIQUE (paper_set_id, version_label)
);

CREATE TABLE qbank_exam_students (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES qbank_exam_sessions(id),
    roll_number TEXT NOT NULL,
    full_name TEXT NOT NULL,
    room_code TEXT NOT NULL,
    row_number INTEGER,
    seat_number INTEGER,
    assigned_version TEXT NOT NULL,
    access_code_hash TEXT NOT NULL,
    login_state TEXT NOT NULL,
    active_token_jti TEXT,
    font_scale REAL NOT NULL DEFAULT 1.0,
    high_contrast INTEGER NOT NULL DEFAULT 0,
    accommodation_extra_minutes INTEGER NOT NULL DEFAULT 0,
    granted_extra_minutes INTEGER NOT NULL DEFAULT 0,
    last_question_index INTEGER NOT NULL DEFAULT 0,
    last_heartbeat_at INTEGER,
    finished_at INTEGER,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (session_id, roll_number)
);
CREATE INDEX idx_qbank_students_room ON qbank_exam_students(session_id, room_code);

CREATE TABLE qbank_student_responses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES qbank_exam_sessions(id),
    roll_number TEXT NOT NULL,
    question_position INTEGER NOT NULL,
    selected_option_index INTEGER,
    version_label TEXT NOT NULL,
    client_timestamp INTEGER,
    server_received_at INTEGER NOT NULL,
    UNIQUE (session_id, roll_number, question_position)
);
CREATE INDEX idx_qbank_responses_session ON qbank_student_responses(session_id, roll_number);

CREATE TABLE qbank_extra_time_grants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES qbank_exam_sessions(id),
    room_code TEXT,
    roll_number TEXT,
    extra_minutes INTEGER NOT NULL,
    reason TEXT NOT NULL,
    granted_by TEXT NOT NULL,
    granted_at INTEGER NOT NULL
);

CREATE TABLE qbank_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    must_change_password INTEGER NOT NULL DEFAULT 0,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE qbank_user_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES qbank_users(id),
    username TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE qbank_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    seq_no INTEGER NOT NULL UNIQUE,
    event_type TEXT NOT NULL,
    actor_username TEXT NOT NULL,
    actor_role TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    details_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    prev_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL
);
CREATE INDEX idx_qbank_audit_seq ON qbank_audit_log(seq_no DESC);
