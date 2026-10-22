package com.torrent.app.backup

/**
 * The crawler database schema, replicated statement-by-statement from the C
 * source (`app/src/main/cpp/dht_crawler/src/database.c:11-96`, constants
 * `CREATE_TABLES_SQL`, `CREATE_FTS_SQL` and `CREATE_TRIGGERS_SQL`).
 *
 * The SQL text is copied verbatim — same tokens, same order, same 4-space
 * indentation inside the parentheses — so a DB built here is the DB the native
 * crawler expects to open. The C literals are split into individual statements
 * by hand on their top-level `;`, which keeps the four FTS trigger bodies
 * (`BEGIN … END;`) intact as single statements. Every statement therefore ends
 * with `;`.
 *
 * The three groups are also the natural execution phases of the Kotlin-side
 * import: create the base tables/indices, replay the row `INSERT`s, then create
 * the FTS5 tables and triggers and rebuild the indexes ([ftsStatements],
 * [triggerStatements]) once the rows are in.
 *
 * No Android imports — plain JUnit4 testable.
 */
object CrawlerDbSchema {

    /** `CREATE TABLE` / `CREATE INDEX` for the non-FTS tables (`database.c:11-64`). */
    val baseStatements: List<String> = listOf(
        // torrents
        "CREATE TABLE IF NOT EXISTS torrents (" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "    info_hash BLOB(20) NOT NULL UNIQUE," +
            "    name TEXT NOT NULL," +
            "    size_bytes INTEGER NOT NULL," +
            "    total_peers INTEGER DEFAULT 0," +
            "    added_timestamp INTEGER NOT NULL" +
            ");",
        "CREATE INDEX IF NOT EXISTS idx_torrents_added ON torrents(added_timestamp DESC);",
        "CREATE INDEX IF NOT EXISTS idx_torrents_total_peers ON torrents(total_peers DESC);",
        // path_prefixes
        "CREATE TABLE IF NOT EXISTS path_prefixes (" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "    prefix TEXT NOT NULL UNIQUE" +
            ");",
        // torrent_files
        "CREATE TABLE IF NOT EXISTS torrent_files (" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "    torrent_id INTEGER NOT NULL," +
            "    prefix_id INTEGER," +
            "    filename TEXT NOT NULL," +
            "    size_bytes INTEGER NOT NULL," +
            "    file_index SMALLINT NOT NULL," +
            "    FOREIGN KEY (torrent_id) REFERENCES torrents(id) ON DELETE CASCADE," +
            "    FOREIGN KEY (prefix_id) REFERENCES path_prefixes(id)" +
            ");",
        "CREATE INDEX IF NOT EXISTS idx_files_torrent ON torrent_files(torrent_id);",
        "CREATE INDEX IF NOT EXISTS idx_files_prefix ON torrent_files(prefix_id);",
        // torrent_class
        "CREATE TABLE IF NOT EXISTS torrent_class (" +
            "    torrent_id INTEGER PRIMARY KEY REFERENCES torrents(id) ON DELETE CASCADE," +
            "    category INTEGER NOT NULL," +
            "    tv_kind INTEGER," +
            "    adult INTEGER NOT NULL DEFAULT 0," +
            "    adult_p REAL," +
            "    season_lo INTEGER," +
            "    season_hi INTEGER," +
            "    episode_lo INTEGER," +
            "    episode_hi INTEGER," +
            "    resolution INTEGER," +
            "    confidence REAL," +
            "    clf_ver INTEGER NOT NULL" +
            ");",
        "CREATE INDEX IF NOT EXISTS idx_class_cat ON torrent_class(category, tv_kind, " +
            "torrent_id) WHERE adult = 0;",
        "CREATE INDEX IF NOT EXISTS idx_class_ver ON torrent_class(clf_ver);",
        // classifier_meta
        "CREATE TABLE IF NOT EXISTS classifier_meta (key TEXT PRIMARY KEY, value TEXT);",
    )

    /** The two FTS5 external-content virtual tables (`database.c:66-79`). */
    val ftsStatements: List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS torrent_search USING fts5(" +
            "    name," +
            "    tokenize='porter unicode61'," +
            "    content='torrents'," +
            "    content_rowid='id'" +
            ");",
        "CREATE VIRTUAL TABLE IF NOT EXISTS file_search USING fts5(" +
            "    filename," +
            "    tokenize='trigram'," +
            "    content='torrent_files'," +
            "    content_rowid='id'" +
            ");",
    )

    /** The four FTS sync triggers, bodies intact (`database.c:81-96`). */
    val triggerStatements: List<String> = listOf(
        "CREATE TRIGGER IF NOT EXISTS torrents_ai AFTER INSERT ON torrents BEGIN" +
            "    INSERT INTO torrent_search(rowid, name) VALUES (new.id, new.name);" +
            "END;",
        "CREATE TRIGGER IF NOT EXISTS torrents_ad AFTER DELETE ON torrents BEGIN" +
            "    INSERT INTO torrent_search(torrent_search, rowid, name) " +
            "VALUES('delete', old.id, old.name);" +
            "END;",
        "CREATE TRIGGER IF NOT EXISTS files_ai AFTER INSERT ON torrent_files BEGIN" +
            "    INSERT INTO file_search(rowid, filename) VALUES (new.id, new.filename);" +
            "END;",
        "CREATE TRIGGER IF NOT EXISTS files_ad AFTER DELETE ON torrent_files BEGIN" +
            "    INSERT INTO file_search(file_search, rowid, filename) " +
            "VALUES('delete', old.id, old.filename);" +
            "END;",
    )

    /** Every schema statement, in the order the crawler creates them. */
    val statements: List<String> = baseStatements + ftsStatements + triggerStatements
}
