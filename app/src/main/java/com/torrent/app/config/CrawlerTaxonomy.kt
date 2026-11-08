package com.torrent.app.config

/**
 * Names of the dht_crawler's ingest taxonomy — the vocabulary of the
 * `save_categories` / `save_tv_kinds` config keys (crawler side:
 * `category_filter_cleanup.c`'s `CATEGORY_NAMES` / `TV_KIND_NAMES`, which
 * mirror the codes in `dht_crawler-android/tparse/taxonomy.h`).
 *
 * Order matches the crawler's index order, so it is also the order the
 * settings form should render the chips in. Name matching on the crawler side
 * is case-insensitive; the app writes lowercase names.
 *
 * An empty selection means "save everything": both keys are omitted from
 * `config.ini` in that case.
 */
object CrawlerTaxonomy {

    /** The 12 `save_categories` names, in the crawler's index order. */
    val CATEGORIES: List<String> = listOf(
        "unknown", "movie", "tv", "anime", "music", "audiobook",
        "ebook", "comic", "software", "game", "image", "other",
    )

    /** The 7 `save_tv_kinds` names (only consulted for `tv`/`anime` rows). */
    val TV_KINDS: List<String> = listOf(
        "none", "episode", "multi_episode", "season_pack", "multi_season",
        "complete_series", "daily",
    )

    /**
     * The 16 movie-kind genre names, in the movie-kind manifest order
     * (`dht_crawler-android/models/v9/manifest.json`), which is also the bit
     * order of a row's `movie_kind_mask`. The search page renders its chips and
     * badges in this order, and the crawler's `movie_kind` filter param accepts
     * exactly these names (plus the literal `none`).
     */
    val MOVIE_KINDS: List<String> = listOf(
        "action", "comedy", "drama", "horror", "thriller", "scifi", "fantasy",
        "animation", "documentary", "romance", "crime", "adventure", "family",
        "mystery", "war", "western",
    )

    /** Display labels for the movie-kind chips/badges, keyed by genre name. */
    val MOVIE_KIND_LABELS: Map<String, String> = MOVIE_KINDS.associateWith { name ->
        when (name) {
            "scifi" -> "Sci-Fi"
            else -> name.replaceFirstChar { it.uppercase() }
        }
    }
}
