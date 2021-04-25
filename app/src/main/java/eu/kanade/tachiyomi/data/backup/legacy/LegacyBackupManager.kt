package eu.kanade.tachiyomi.data.backup.legacy

import android.content.Context
import android.net.Uri
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.registerTypeAdapter
import com.github.salomonbrys.kotson.registerTypeHierarchyAdapter
import com.github.salomonbrys.kotson.set
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.AbstractBackupManager
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_ANIMECATEGORY
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_ANIMECATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CHAPTER_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_EPISODE
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_EPISODE_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_HISTORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_TRACK_MASK
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.ANIMECATEGORIES
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.ANIMEEXTENSIONS
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CATEGORIES
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CHAPTERS
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CURRENT_VERSION
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.EPISODES
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.EXTENSIONS
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.HISTORY
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.MANGA
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.TRACK
import eu.kanade.tachiyomi.data.backup.legacy.models.DHistory
import eu.kanade.tachiyomi.data.backup.legacy.serializer.CategoryTypeAdapter
import eu.kanade.tachiyomi.data.backup.legacy.serializer.ChapterTypeAdapter
import eu.kanade.tachiyomi.data.backup.legacy.serializer.HistoryTypeAdapter
import eu.kanade.tachiyomi.data.backup.legacy.serializer.MangaTypeAdapter
import eu.kanade.tachiyomi.data.backup.legacy.serializer.TrackTypeAdapter
import eu.kanade.tachiyomi.data.database.models.*
import eu.kanade.tachiyomi.source.AnimeSource
import eu.kanade.tachiyomi.source.LocalAnimeSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.toSAnime
import eu.kanade.tachiyomi.source.model.toSManga
import timber.log.Timber
import kotlin.math.max

class LegacyBackupManager(context: Context, version: Int = CURRENT_VERSION) : AbstractBackupManager(context) {

    val parser: Gson = when (version) {
        2 -> GsonBuilder()
            .registerTypeAdapter<MangaImpl>(MangaTypeAdapter.build())
            .registerTypeHierarchyAdapter<ChapterImpl>(ChapterTypeAdapter.build())
            .registerTypeAdapter<CategoryImpl>(CategoryTypeAdapter.build())
            .registerTypeAdapter<DHistory>(HistoryTypeAdapter.build())
            .registerTypeHierarchyAdapter<TrackImpl>(TrackTypeAdapter.build())
            .create()
        else -> throw Exception("Unknown backup version")
    }

    /**
     * Create backup Json file from database
     *
     * @param uri path of Uri
     * @param isJob backup called from job
     */
    override fun createBackup(uri: Uri, flags: Int, isJob: Boolean): String? {
        // Create root object
        val root = JsonObject()

        // Create manga array
        val mangaEntries = JsonArray()

        // Create anime array
        val animeEntries = JsonArray()

        // Create category array
        val categoryEntries = JsonArray()

        // Create animecategory array
        val animecategoryEntries = JsonArray()

        // Create extension ID/name mapping
        val extensionEntries = JsonArray()

        // Create animeextension ID/name mapping
        val animeextensionEntries = JsonArray()

        // Add value's to root
        root[Backup.VERSION] = CURRENT_VERSION
        root[Backup.MANGAS] = mangaEntries
        root[CATEGORIES] = categoryEntries
        root[EXTENSIONS] = extensionEntries
        root[Backup.ANIMES] = animeEntries
        root[ANIMECATEGORIES] = animecategoryEntries
        root[ANIMEEXTENSIONS] = animeextensionEntries

        databaseHelper.inTransaction {
            val mangas = getFavoriteManga()

            val extensions: MutableSet<String> = mutableSetOf()

            // Backup library manga and its dependencies
            mangas.forEach { manga ->
                mangaEntries.add(backupMangaObject(manga, flags))

                // Maintain set of extensions/sources used (excludes local source)
                if (manga.source != LocalSource.ID) {
                    sourceManager.get(manga.source)?.let {
                        extensions.add("${manga.source}:${it.name}")
                    }
                }
            }

            // Backup categories
            if ((flags and BACKUP_CATEGORY_MASK) == BACKUP_CATEGORY) {
                backupCategories(categoryEntries)
            }

            // Backup extension ID/name mapping
            backupExtensionInfo(extensionEntries, extensions)

            val animes = getFavoriteAnime()

            val animeextensions: MutableSet<String> = mutableSetOf()

            // Backup library anime and its dependencies
            animes.forEach { anime ->
                animeEntries.add(backupAnimeObject(anime, flags))

                // Maintain set of extensions/sources used (excludes local source)
                if (anime.source != LocalAnimeSource.ID) {
                    sourceManager.get(anime.source)?.let {
                        extensions.add("${anime.source}:${it.name}")
                    }
                }
            }

            // Backup categories
            if ((flags and BACKUP_ANIMECATEGORY_MASK) == BACKUP_ANIMECATEGORY) {
                backupCategories(animecategoryEntries)
            }

            // Backup extension ID/name mapping
            backupExtensionInfo(animeextensionEntries, animeextensions)
        }

        try {
            val file: UniFile = (
                if (isJob) {
                    // Get dir of file and create
                    var dir = UniFile.fromUri(context, uri)
                    dir = dir.createDirectory("automatic")

                    // Delete older backups
                    val numberOfBackups = numberOfBackups()
                    val backupRegex = Regex("""tachiyomi_\d+-\d+-\d+_\d+-\d+.json""")
                    dir.listFiles { _, filename -> backupRegex.matches(filename) }
                        .orEmpty()
                        .sortedByDescending { it.name }
                        .drop(numberOfBackups - 1)
                        .forEach { it.delete() }

                    // Create new file to place backup
                    dir.createFile(Backup.getDefaultFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }
                )
                ?: throw Exception("Couldn't create backup file")

            file.openOutputStream().bufferedWriter().use {
                parser.toJson(root, it)
            }
            return file.uri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    private fun backupExtensionInfo(root: JsonArray, extensions: Set<String>) {
        extensions.sorted().forEach {
            root.add(it)
        }
    }

    /**
     * Backup the categories of library
     *
     * @param root root of categories json
     */
    internal fun backupCategories(root: JsonArray) {
        val categories = databaseHelper.getCategories().executeAsBlocking()
        categories.forEach { root.add(parser.toJsonTree(it)) }
    }

    /**
     * Backup the categories of library
     *
     * @param root root of categories json
     */
    internal fun backupAnimeCategories(root: JsonArray) {
        val categories = animedatabaseHelper.getCategories().executeAsBlocking()
        categories.forEach { root.add(parser.toJsonTree(it)) }
    }

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @return [JsonElement] containing manga information
     */
    internal fun backupMangaObject(manga: Manga, options: Int): JsonElement {
        // Entry for this manga
        val entry = JsonObject()

        // Backup manga fields
        entry[MANGA] = parser.toJsonTree(manga)

        // Check if user wants chapter information in backup
        if (options and BACKUP_CHAPTER_MASK == BACKUP_CHAPTER) {
            // Backup all the chapters
            val chapters = databaseHelper.getChapters(manga).executeAsBlocking()
            if (chapters.isNotEmpty()) {
                val chaptersJson = parser.toJsonTree(chapters)
                if (chaptersJson.asJsonArray.size() > 0) {
                    entry[CHAPTERS] = chaptersJson
                }
            }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            // Backup categories for this manga
            val categoriesForManga = databaseHelper.getCategoriesForManga(manga).executeAsBlocking()
            if (categoriesForManga.isNotEmpty()) {
                val categoriesNames = categoriesForManga.map { it.name }
                entry[CATEGORIES] = parser.toJsonTree(categoriesNames)
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
            val tracks = databaseHelper.getTracks(manga).executeAsBlocking()
            if (tracks.isNotEmpty()) {
                entry[TRACK] = parser.toJsonTree(tracks)
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
            val historyForManga = databaseHelper.getHistoryByMangaId(manga.id!!).executeAsBlocking()
            if (historyForManga.isNotEmpty()) {
                val historyData = historyForManga.mapNotNull { history ->
                    val url = databaseHelper.getChapter(history.chapter_id).executeAsBlocking()?.url
                    url?.let { DHistory(url, history.last_read) }
                }
                val historyJson = parser.toJsonTree(historyData)
                if (historyJson.asJsonArray.size() > 0) {
                    entry[HISTORY] = historyJson
                }
            }
        }

        return entry
    }

    /**
     * Convert a anime to Json
     *
     * @param anime anime that gets converted
     * @return [JsonElement] containing anime information
     */
    internal fun backupAnimeObject(anime: Anime, options: Int): JsonElement {
        // Entry for this anime
        val entry = JsonObject()

        // Backup anime fields
        entry[MANGA] = parser.toJsonTree(anime)

        // Check if user wants episode information in backup
        if (options and BACKUP_EPISODE_MASK == BACKUP_EPISODE) {
            // Backup all the episodes
            val episodes = animedatabaseHelper.getEpisodes(anime).executeAsBlocking()
            if (episodes.isNotEmpty()) {
                val episodesJson = parser.toJsonTree(episodes)
                if (episodesJson.asJsonArray.size() > 0) {
                    entry[EPISODES] = episodesJson
                }
            }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            // Backup categories for this anime
            val categoriesForAnime = animedatabaseHelper.getCategoriesForAnime(anime).executeAsBlocking()
            if (categoriesForAnime.isNotEmpty()) {
                val categoriesNames = categoriesForAnime.map { it.name }
                entry[CATEGORIES] = parser.toJsonTree(categoriesNames)
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
            val tracks = animedatabaseHelper.getTracks(anime).executeAsBlocking()
            if (tracks.isNotEmpty()) {
                entry[TRACK] = parser.toJsonTree(tracks)
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
            val historyForAnime = animedatabaseHelper.getHistoryByAnimeId(anime.id!!).executeAsBlocking()
            if (historyForAnime.isNotEmpty()) {
                val historyData = historyForAnime.mapNotNull { history ->
                    val url = animedatabaseHelper.getEpisode(history.episode_id).executeAsBlocking()?.url
                    url?.let { DHistory(url, history.last_seen) }
                }
                val historyJson = parser.toJsonTree(historyData)
                if (historyJson.asJsonArray.size() > 0) {
                    entry[HISTORY] = historyJson
                }
            }
        }

        return entry
    }

    fun restoreMangaNoFetch(manga: Manga, dbManga: Manga) {
        manga.id = dbManga.id
        manga.copyFrom(dbManga)
        manga.favorite = true
        insertManga(manga)
    }

    fun restoreAnimeNoFetch(anime: Anime, dbAnime: Anime) {
        anime.id = dbAnime.id
        anime.copyFrom(dbAnime)
        anime.favorite = true
        insertAnime(anime)
    }

    /**
     * Fetches manga information
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @return Updated manga.
     */
    suspend fun fetchManga(source: Source, manga: Manga): Manga {
        val networkManga = source.getMangaDetails(manga.toMangaInfo())
        return manga.also {
            it.copyFrom(networkManga.toSManga())
            it.favorite = true
            it.initialized = true
            it.id = insertManga(manga)
        }
    }

    /**
     * Fetches manga information
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @return Updated manga.
     */
    suspend fun fetchAnime(source: AnimeSource, manga: Anime): Anime {
        val networkManga = source.getAnimeDetails(manga.toAnimeInfo())
        return manga.also {
            it.copyFrom(networkManga.toSAnime())
            it.favorite = true
            it.initialized = true
            it.id = insertAnime(manga)
        }
    }

    /**
     * Restore the categories from Json
     *
     * @param jsonCategories array containing categories
     */
    internal fun restoreCategories(jsonCategories: JsonArray) {
        // Get categories from file and from db
        val dbCategories = databaseHelper.getCategories().executeAsBlocking()
        val backupCategories = parser.fromJson<List<CategoryImpl>>(jsonCategories)

        // Iterate over them
        backupCategories.forEach { category ->
            // Used to know if the category is already in the db
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category.id = dbCategory.id
                    found = true
                    break
                }
            }
            // If the category isn't in the db, remove the id and insert a new category
            // Store the inserted id in the category
            if (!found) {
                // Let the db assign the id
                category.id = null
                val result = databaseHelper.insertCategory(category).executeAsBlocking()
                category.id = result.insertedId()?.toInt()
            }
        }
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal fun restoreCategoriesForManga(manga: Manga, categories: List<String>) {
        val dbCategories = databaseHelper.getCategories().executeAsBlocking()
        val mangaCategoriesToUpdate = ArrayList<MangaCategory>(categories.size)
        for (backupCategoryStr in categories) {
            for (dbCategory in dbCategories) {
                if (backupCategoryStr == dbCategory.name) {
                    mangaCategoriesToUpdate.add(MangaCategory.create(manga, dbCategory))
                    break
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            databaseHelper.deleteOldMangasCategories(listOf(manga)).executeAsBlocking()
            databaseHelper.insertMangasCategories(mangaCategoriesToUpdate).executeAsBlocking()
        }
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal fun restoreHistoryForManga(history: List<DHistory>) {
        // List containing history to be updated
        val historyToBeUpdated = ArrayList<History>(history.size)
        for ((url, lastRead) in history) {
            val dbHistory = databaseHelper.getHistoryByChapterUrl(url).executeAsBlocking()
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory.apply {
                    last_read = max(lastRead, dbHistory.last_read)
                }
                historyToBeUpdated.add(dbHistory)
            } else {
                // If not in database create
                databaseHelper.getChapter(url).executeAsBlocking()?.let {
                    val historyToAdd = History.create(it).apply {
                        last_read = lastRead
                    }
                    historyToBeUpdated.add(historyToAdd)
                }
            }
        }
        databaseHelper.updateHistoryLastRead(historyToBeUpdated).executeAsBlocking()
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    internal fun restoreTrackForManga(manga: Manga, tracks: List<Track>) {
        // Get tracks from database
        val dbTracks = databaseHelper.getTracks(manga).executeAsBlocking()
        val trackToUpdate = ArrayList<Track>(tracks.size)

        tracks.forEach { track ->
            // Fix foreign keys with the current manga id
            track.manga_id = manga.id!!

            val service = trackManager.getService(track.sync_id)
            if (service != null && service.isLogged) {
                var isInDatabase = false
                for (dbTrack in dbTracks) {
                    if (track.sync_id == dbTrack.sync_id) {
                        // The sync is already in the db, only update its fields
                        if (track.media_id != dbTrack.media_id) {
                            dbTrack.media_id = track.media_id
                        }
                        if (track.library_id != dbTrack.library_id) {
                            dbTrack.library_id = track.library_id
                        }
                        dbTrack.last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read)
                        isInDatabase = true
                        trackToUpdate.add(dbTrack)
                        break
                    }
                }
                if (!isInDatabase) {
                    // Insert new sync. Let the db assign the id
                    track.id = null
                    trackToUpdate.add(track)
                }
            }
        }
        // Update database
        if (trackToUpdate.isNotEmpty()) {
            databaseHelper.insertTracks(trackToUpdate).executeAsBlocking()
        }
    }

    /**
     * Restore the chapters for manga if chapters already in database
     *
     * @param manga manga of chapters
     * @param chapters list containing chapters that get restored
     * @return boolean answering if chapter fetch is not needed
     */
    internal fun restoreChaptersForManga(manga: Manga, chapters: List<Chapter>): Boolean {
        val dbChapters = databaseHelper.getChapters(manga).executeAsBlocking()

        // Return if fetch is needed
        if (dbChapters.isEmpty() || dbChapters.size < chapters.size) {
            return false
        }

        for (chapter in chapters) {
            val pos = dbChapters.indexOf(chapter)
            if (pos != -1) {
                val dbChapter = dbChapters[pos]
                chapter.id = dbChapter.id
                chapter.copyFrom(dbChapter)
                break
            }

            chapter.manga_id = manga.id
        }

        // Filter the chapters that couldn't be found.
        updateChapters(chapters.filter { it.id != null })

        return true
    }
}
