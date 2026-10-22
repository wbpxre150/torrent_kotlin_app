package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CrawlerDbSchema]: the constants must be a faithful, complete
 * replica of the crawler's DDL (`database.c:11-96`) — every statement
 * `;`-terminated, every table/index/trigger present, both FTS5 tables declared
 * external-content over the right tables, and the four trigger bodies left
 * intact.
 */
class CrawlerDbSchemaTest {

    @Test fun everyStatementEndsWithSemicolon() {
        for (statement in CrawlerDbSchema.statements) {
            assertTrue("missing ';': $statement", statement.trimEnd().endsWith(";"))
        }
    }

    @Test fun statementCountMatchesTheCConstants() {
        // 11 base (5 tables + 6 indices) + 2 FTS5 virtual tables + 4 triggers.
        assertEquals(11, CrawlerDbSchema.baseStatements.size)
        assertEquals(2, CrawlerDbSchema.ftsStatements.size)
        assertEquals(4, CrawlerDbSchema.triggerStatements.size)
        assertEquals(17, CrawlerDbSchema.statements.size)
        assertEquals(
            CrawlerDbSchema.baseStatements +
                CrawlerDbSchema.ftsStatements +
                CrawlerDbSchema.triggerStatements,
            CrawlerDbSchema.statements,
        )
    }

    @Test fun allStatementsAreDistinct() {
        assertEquals(CrawlerDbSchema.statements.size, CrawlerDbSchema.statements.toSet().size)
    }

    @Test fun baseTablesAreDeclared() {
        val ddl = CrawlerDbSchema.baseStatements.joinToString("\n")
        for (table in listOf(
            "torrents",
            "path_prefixes",
            "torrent_files",
            "torrent_class",
            "classifier_meta",
        )) {
            assertTrue("missing table $table", ddl.contains("CREATE TABLE IF NOT EXISTS $table ("))
        }
    }

    @Test fun indicesAreDeclared() {
        val ddl = CrawlerDbSchema.baseStatements.joinToString("\n")
        for (index in listOf(
            "idx_torrents_added",
            "idx_torrents_total_peers",
            "idx_files_torrent",
            "idx_files_prefix",
            "idx_class_cat",
            "idx_class_ver",
        )) {
            assertTrue("missing index $index", ddl.contains("CREATE INDEX IF NOT EXISTS $index ON "))
        }
    }

    @Test fun partialIndexOnAdultIsPreserved() {
        assertTrue(
            CrawlerDbSchema.baseStatements.any {
                it.contains("idx_class_cat ON torrent_class(category, tv_kind, torrent_id)") &&
                    it.contains("WHERE adult = 0;")
            },
        )
    }

    @Test fun ftsTablesAreExternalContentOverTheBaseTables() {
        val torrentSearch = CrawlerDbSchema.ftsStatements[0]
        assertTrue(torrentSearch.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS torrent_search USING fts5("))
        assertTrue(torrentSearch.contains("tokenize='porter unicode61'"))
        assertTrue(torrentSearch.contains("content='torrents'"))
        assertTrue(torrentSearch.contains("content_rowid='id'"))

        val fileSearch = CrawlerDbSchema.ftsStatements[1]
        assertTrue(fileSearch.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS file_search USING fts5("))
        assertTrue(fileSearch.contains("tokenize='trigram'"))
        assertTrue(fileSearch.contains("content='torrent_files'"))
        assertTrue(fileSearch.contains("content_rowid='id'"))
    }

    @Test fun triggerBodiesAreIntact() {
        val triggers = CrawlerDbSchema.triggerStatements
        assertEquals(
            listOf("torrents_ai", "torrents_ad", "files_ai", "files_ad"),
            triggers.map { it.removePrefix("CREATE TRIGGER IF NOT EXISTS ").substringBefore(' ') },
        )
        for (trigger in triggers) {
            assertTrue("no BEGIN: $trigger", trigger.contains(" BEGIN"))
            assertTrue("no END;: $trigger", trigger.endsWith("END;"))
            // Exactly two ';' — the trigger body's inner statement plus END;
            assertEquals("body split: $trigger", 2, trigger.count { it == ';' })
        }
        assertTrue(triggers[0].contains("INSERT INTO torrent_search(rowid, name) VALUES (new.id, new.name);"))
        assertTrue(
            triggers[1].contains(
                "INSERT INTO torrent_search(torrent_search, rowid, name) " +
                    "VALUES('delete', old.id, old.name);",
            ),
        )
        assertTrue(
            triggers[2].contains("INSERT INTO file_search(rowid, filename) VALUES (new.id, new.filename);"),
        )
        assertTrue(
            triggers[3].contains(
                "INSERT INTO file_search(file_search, rowid, filename) " +
                    "VALUES('delete', old.id, old.filename);",
            ),
        )
    }

    @Test fun columnListsMatchTheCDeclarations() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS torrents (    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    info_hash BLOB(20) NOT NULL UNIQUE,    name TEXT NOT NULL," +
                "    size_bytes INTEGER NOT NULL,    total_peers INTEGER DEFAULT 0," +
                "    added_timestamp INTEGER NOT NULL);",
            CrawlerDbSchema.baseStatements[0],
        )
        assertEquals(
            "CREATE TABLE IF NOT EXISTS classifier_meta (key TEXT PRIMARY KEY, value TEXT);",
            CrawlerDbSchema.baseStatements[10],
        )
    }

    @Test fun noStatementHasALineBreak() {
        // The C literals are newline-free; so are ours (the import relies on
        // nothing here, but it keeps the copy verifiably verbatim).
        for (statement in CrawlerDbSchema.statements) {
            assertTrue("contains newline: $statement", !statement.contains('\n'))
        }
    }
}
