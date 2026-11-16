package com.torrent.app.config

/**
 * Key knowledge for the dht_crawler's `config.ini`, derived from the C
 * crawler's `config_load_file` (`cpp/dht_crawler/src/config.c`).
 *
 * The settings form owns [FORM_MANAGED_KEYS]; the advanced-override textarea
 * accepts only [ALLOWED_ADVANCED_KEYS] = known keys minus path keys, backup
 * keys and form-managed keys. Backup keys are never user-settable: the config
 * writer pins them to 0.
 */
object CrawlerKeys {

    /** Every key the C crawler's `config_load_file` accepts. */
    val KNOWN_KEYS: Set<String> = setOf(
        // DHT / database / logging / process
        "dht_port", "db_path", "log_level",
        // HTTP API
        "http_port", "http_bind",
        // Process
        "nice_level",
        // Bloom filters
        "bloom_enabled", "bloom_capacity", "failure_bloom_capacity", "failure_strike_count",
        "bloom_error_rate", "bloom_persist", "bloom_path", "bloom_persist_interval_sec",
        // Batch writer
        "batch_size", "flush_interval",
        // Daily backup (pinned off on Android)
        "backup_enabled", "backup_path",
        // SSH incremental backup
        "ssh_backup_enabled", "ssh_host", "ssh_user", "ssh_dest_path", "ssh_key_path",
        "ssh_bookmark_path",
        // rclone incremental backup
        "rclone_backup_enabled", "rclone_remote", "rclone_dest_path", "rclone_bookmark_path",
        // Metadata fetcher
        "max_concurrent_connections", "tcp_connect_timeout_sec", "connection_timeout_sec",
        "max_connection_lifetime_sec",
        // Ingest-time classifier + adult handling (Stage 5/6)
        "classifier_enabled", "classifier_model_dir",
        "adult_mode", "adult_hide_threshold", "hard_block_file",
        // Classifier/adult backfill mode (`all` | `adult` | `off`). The config writer
        // pins it to `off` for this release and no form field renders it, so it is
        // known (parseAdvanced must not call it unknown) and advanced-overridable.
        "classify_backfill_mode",
        // Movie-kind classifier + backfill (Stage D). Storage-only: the app stages
        // the model dir (pinned) and renders no form field for these, so the three
        // tunables are settable from the advanced textarea (the
        // `movie_kind_enabled = 0` escape hatch depends on it).
        "movie_kind_enabled", "movie_kind_model_dir", "movie_kind_threads",
        "movie_kind_thermal_backoff_c",
        // Categories to save (empty list = save everything)
        "save_categories", "save_tv_kinds",
        // Language filter
        "language_filter_non_latin_threshold", "porn_filter_non_latin_threshold",
        // Thread trees
        "num_trees", "use_thread_trees",
        "use_keyspace_partitioning", "dead_partition_threshold", "max_trees_per_partition",
        "tree_find_node_workers", "tree_bep51_workers", "tree_infohash_queue_capacity",
        "tree_bep51_query_interval_ms", "tree_get_peers_workers", "tree_peers_queue_capacity",
        "tree_get_peers_timeout_ms", "tree_metadata_workers", "tree_tcp_connect_timeout_ms",
        "tree_get_peers_deadline_ms", "tree_get_peers_fanout", "tree_get_peers_max_queries",
        "tree_get_peers_round_idle_ms", "tree_get_peers_penalize_misses",
        "tree_parallel_peers", "udp_rate_limit_pps",
        "tree_metadata_fresh_peer_retries",
        "tree_metadata_timeout_ms",
        // Rate-based respawn
        "min_metadata_rate", "dynamic_rate_margin", "tree_rate_check_interval_sec",
        "tree_rate_grace_period_sec", "tree_min_lifetime_minutes", "tree_require_empty_queue",
        "tree_rate_ema_alpha",
        // Respawn overlapping
        "respawn_spawn_threshold", "respawn_drain_timeout_sec", "max_draining_trees",
        // Global bootstrap
        "global_bootstrap_target", "global_bootstrap_timeout_sec", "global_bootstrap_workers",
        // Refresh thread
        "refresh_retry_count", "refresh_bootstrap_sample_size", "refresh_routing_table_target",
        "refresh_ping_workers", "refresh_find_node_workers", "refresh_get_peers_workers",
        "refresh_request_queue_capacity", "refresh_get_peers_timeout_ms", "refresh_max_iterations",
        // BEP51 cache
        "bep51_cache_path", "bep51_cache_capacity", "bep51_cache_submit_percent",
        "bep51_node_cooldown_sec",
    )

    /** Path keys — the app owns the internal-storage layout; never user-overridable. */
    val PATH_KEYS: Set<String> = setOf(
        "db_path", "bloom_path", "bep51_cache_path",
        "backup_path", "ssh_dest_path", "ssh_key_path", "ssh_bookmark_path",
        "rclone_dest_path", "rclone_bookmark_path",
        "classifier_model_dir", "movie_kind_model_dir", "hard_block_file",
    )

    /** Backup keys — backups are off on Android and pinned to 0 by the writer. */
    val BACKUP_KEYS: Set<String> = setOf(
        "backup_enabled", "backup_path",
        "ssh_backup_enabled", "ssh_host", "ssh_user", "ssh_dest_path", "ssh_key_path",
        "ssh_bookmark_path",
        "rclone_backup_enabled", "rclone_remote", "rclone_dest_path", "rclone_bookmark_path",
    )

    /** Keys the settings form renders — use the form, not the advanced textarea. */
    val FORM_MANAGED_KEYS: Set<String> = setOf(
        "dht_port", "log_level", "nice_level",
        "num_trees",
        "tree_find_node_workers", "tree_bep51_workers", "tree_get_peers_workers",
        "tree_metadata_workers", "tree_infohash_queue_capacity", "tree_peers_queue_capacity",
        "language_filter_non_latin_threshold", "save_categories", "save_tv_kinds",
        "classifier_enabled", "adult_mode", "adult_hide_threshold",
        "bloom_capacity", "failure_bloom_capacity", "bloom_error_rate",
        "http_port", "http_bind",
    )

    /** Advanced textarea allow-list: known keys minus path, backup and form-managed keys. */
    val ALLOWED_ADVANCED_KEYS: Set<String> =
        KNOWN_KEYS - PATH_KEYS - BACKUP_KEYS - FORM_MANAGED_KEYS

    /** Log level names accepted by the C crawler (`config.c`). */
    val LOG_LEVELS = listOf("DEBUG", "INFO", "WARN", "ERROR")

    /** `adult_mode` values accepted by the C crawler's `config.c` (case-sensitive there). */
    val ADULT_MODES = listOf("tag", "drop")

    /**
     * `classify_backfill_mode` values accepted by the C crawler's `config.c`
     * (case-sensitive there): run the full classify pass, run the token-gated adult
     * pass only, or run nothing at all.
     */
    val CLASSIFY_BACKFILL_MODES = listOf("all", "adult", "off")

    /**
     * Fixed worker-thread overhead on top of `num_trees × Σ(per-tree workers)`
     * (main, supervisor, HTTP, batch writer, refresh thread, ...).
     */
    const val THREAD_OVERHEAD = 64

    /** Estimated thread count above which the UI should warn. */
    const val THREAD_WARN_THRESHOLD = 3000

    /**
     * Estimated worker-thread count for [s]: `num_trees × (find_node + bep51 +
     * get_peers + metadata)` + a fixed overhead. The 32-tree
     * default config estimates to 1216.
     */
    fun estimateThreads(s: CrawlerSettings): Int =
        s.numTrees * (s.treeFindNodeWorkers + s.treeBep51Workers + s.treeGetPeersWorkers +
            s.treeMetadataWorkers) + THREAD_OVERHEAD

    /** Result of [parseAdvanced]: parsed entries plus any per-line errors. */
    data class AdvancedParse(
        val ok: Boolean,
        val entries: Map<String, String> = emptyMap(),
        val errors: List<String> = emptyList(),
    )

    /**
     * Parses the advanced-override textarea (`key=value` lines). `#`/`;`
     * comments and blank lines are ignored. Errors are reported per line for:
     * malformed lines (no `=`), unknown keys, path keys, backup keys, and
     * form-managed keys (with a hint to use the form).
     */
    fun parseAdvanced(text: String): AdvancedParse {
        val entries = LinkedHashMap<String, String>()
        val errors = mutableListOf<String>()
        var lineNum = 0
        for (raw in text.split("\n")) {
            lineNum++
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                errors.add("line $lineNum: malformed (expected key=value): \"$line\"")
                continue
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when {
                key in BACKUP_KEYS ->
                    errors.add("line $lineNum: backup key \"$key\" is not allowed (backups are disabled on Android)")
                key in PATH_KEYS ->
                    errors.add("line $lineNum: path key \"$key\" is not allowed (managed by the app)")
                key in FORM_MANAGED_KEYS ->
                    errors.add("line $lineNum: \"$key\" is managed by the form — use the corresponding field")
                key !in KNOWN_KEYS ->
                    errors.add("line $lineNum: unknown key \"$key\"")
                else -> entries[key] = value
            }
        }
        return AdvancedParse(errors.isEmpty(), entries, errors)
    }
}
