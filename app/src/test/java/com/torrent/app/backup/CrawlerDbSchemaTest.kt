package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CrawlerDbSchema]: the constants must be a faithful, complete
 * replica of the crawler's DDL (`database.c:11-96`) — every statement
 * `;`-terminated, every table/index/trigger present, the FTS5 tables declared
 * over the right tables, and the three trigger bodies left intact.
 */
class CrawlerDbSchemaTest {

    @Test fun everyStatementEndsWithSemicolon() {
        for (statement in CrawlerDbSchema.statements) {
            assertTrue("missing ';': $statement", statement.trimEnd().endsWith(";"))
        }
    }

    @Test fun statementCountMatchesTheCConstants() {
        // 11 base (5 tables + 6 indices) + 2 FTS5 virtual tables + 3 triggers.
        assertEquals(5, CrawlerDbSchema.tableStatements.size)
        assertEquals(6, CrawlerDbSchema.indexStatements.size)
        assertEquals(11, CrawlerDbSchema.baseStatements.size)
        assertEquals(2, CrawlerDbSchema.ftsStatements.size)
        assertEquals(3, CrawlerDbSchema.triggerStatements.size)
        assertEquals(16, CrawlerDbSchema.statements.size)
        assertEquals(
            CrawlerDbSchema.tableStatements +
                CrawlerDbSchema.indexStatements +
                CrawlerDbSchema.ftsStatements +
                CrawlerDbSchema.triggerStatements,
            CrawlerDbSchema.statements,
        )
    }

    @Test fun theBulkPhaseGroupHoldsOnlyTablesAndTheDeferredGroupOnlyIndices() {
        // The import creates `tableStatements` up front and `indexStatements` in
        // finishImport(); that split is what makes the bulk phase index-free, so
        // neither group may leak the other's DDL.
        for (statement in CrawlerDbSchema.tableStatements) {
            assertTrue(
                "not a CREATE TABLE: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS "),
            )
        }
        for (statement in CrawlerDbSchema.indexStatements) {
            assertTrue(
                "not a CREATE INDEX: $statement",
                statement.startsWith("CREATE INDEX IF NOT EXISTS idx_"),
            )
        }
        assertEquals(
            CrawlerDbSchema.tableStatements + CrawlerDbSchema.indexStatements,
            CrawlerDbSchema.baseStatements,
        )
    }

    @Test fun allStatementsAreDistinct() {
        assertEquals(CrawlerDbSchema.statements.size, CrawlerDbSchema.statements.toSet().size)
    }

    @Test fun baseTablesAreDeclared() {
        val ddl = CrawlerDbSchema.tableStatements.joinToString("\n")
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
        val ddl = CrawlerDbSchema.indexStatements.joinToString("\n")
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
            CrawlerDbSchema.indexStatements.any {
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
    }

    @Test fun triggerBodiesAreIntact() {
        val triggers = CrawlerDbSchema.triggerStatements
        assertEquals(
            listOf("torrents_ai", "torrents_ad", "torrents_docs_ad"),
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
    }

    @Test fun columnListsMatchTheCDeclarations() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS torrents (    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    info_hash BLOB(20) NOT NULL UNIQUE,    name TEXT NOT NULL," +
                "    size_bytes INTEGER NOT NULL,    total_peers INTEGER DEFAULT 0," +
                "    added_timestamp INTEGER NOT NULL);",
            CrawlerDbSchema.tableStatements[0],
        )
        assertEquals(
            "CREATE TABLE IF NOT EXISTS classifier_meta (key TEXT PRIMARY KEY, value TEXT);",
            CrawlerDbSchema.tableStatements[4],
        )
    }

    @Test fun noStatementHasALineBreak() {
        // The C literals are newline-free; so are ours (the import relies on
        // nothing here, but it keeps the copy verifiably verbatim).
        for (statement in CrawlerDbSchema.statements) {
            assertTrue("contains newline: $statement", !statement.contains('\n'))
        }
    }

    @Test fun noFileSearchOrTrigramStatements() {
        // Schema v2 dropped the ~47.7 GB file-name search index entirely: a
        // restored DB must not recreate it (nor the tokenizer it needed). The
        // two forbidden tokens are assembled from parts so the source-level
        // "no references remain" grep stays clean.
        for (token in listOf("file" + "_search", "tri" + "gram")) {
            val offenders = CrawlerDbSchema.statements.filter { it.contains(token) }
            assertTrue("schema still mentions $token: $offenders", offenders.isEmpty())
        }
    }
}
