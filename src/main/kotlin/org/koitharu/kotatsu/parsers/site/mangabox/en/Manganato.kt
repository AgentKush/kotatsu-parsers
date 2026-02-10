package org.koitharu.kotatsu.parsers.site.mangabox.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGANATO", "Manganato", "en")
internal class Manganato(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGANATO, 24) {

	override val configKeyDomain = ConfigKey.Domain(
		"www.natomanga.com",
		"www.manganato.gg",
		"natomanga.com",
		"manganato.gg",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search/story/")
					append(normalizeSearchQuery(filter.query))
					append("?page=")
					append(page)
				}

				else -> {
					if (filter.tags.isNotEmpty()) {
						append("/genre/")
						append(filter.tags.first().key)
						append("?page=")
						append(page)
						append("&type=")
						append(
							when (order) {
								SortOrder.POPULARITY -> "topview"
								SortOrder.UPDATED -> "latest"
								SortOrder.NEWEST -> "newest"
								else -> "latest"
							},
						)
						if (filter.states.isNotEmpty()) {
							append("&state=")
							append(
								when (filter.states.oneOrThrowIfMany()) {
									MangaState.ONGOING -> "ongoing"
									MangaState.FINISHED -> "completed"
									else -> "all"
								},
							)
						}
					} else {
						append("/manga-list/")
						append(
							when (order) {
								SortOrder.POPULARITY -> "hot-manga"
								SortOrder.NEWEST -> "newest-manga"
								else -> "latest-manga"
							},
						)
						append("?page=")
						append(page)
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select(
			"div.truyen-list > div.list-truyen-item-wrap, div.comic-list > .list-comic-item-wrap",
		).ifEmpty {
			doc.select(".panel_story_list .story_item, div.search-story-item, div.content-genres-item")
		}.map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = div.selectFirst("h3 a, h3")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genre").parseHtml()
		val tags = doc.select("div.panel-genres-list a:not(.genres-select)").drop(1)
		return tags.mapToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
			val name = a.attr("title").replace(" Manga", "").ifEmpty { a.text() }
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val slug = manga.url.removeSuffix("/").substringAfterLast("/")
		val chapters = fetchChaptersFromApi(slug)

		val infoElement = doc.selectFirst("div.manga-info-top, div.panel-story-info")

		val statusText = infoElement?.select("li:contains(Status), td:containsOwn(Status) + td")?.text().orEmpty()

		return manga.copy(
			title = infoElement?.selectFirst("h1, h2")?.text() ?: manga.title,
			altTitles = setOfNotNull(
				doc.selectFirst(".story-alternative, tr:has(.info-alternative) h2")?.text()
					?.replace("Alternative :", "")?.trim()?.nullIfEmpty(),
			),
			authors = (infoElement?.select("li:contains(author) a, td:contains(author) + td a") ?: emptyList())
				.mapToSet { it.text() },
			description = doc.selectFirst("div#noidungm, div#panel-story-info-description, div#contentBox")?.html(),
			tags = (
				infoElement?.select("div.manga-info-top li:contains(genres) a, td:containsOwn(genres) + td a")
					?: emptyList()
				).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("category=").substringBefore("&")
						.ifEmpty { a.attr("href").removeSuffix("/").substringAfterLast("/") },
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			state = when {
				statusText.contains("ongoing", true) -> MangaState.ONGOING
				statusText.contains("completed", true) -> MangaState.FINISHED
				else -> null
			},
			chapters = chapters,
		)
	}

	private suspend fun fetchChaptersFromApi(slug: String): List<MangaChapter> {
		val allChapters = mutableListOf<MangaChapter>()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ENGLISH).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		var offset = 0
		val limit = 1000

		while (true) {
			val apiUrl = "https://$domain/api/manga/$slug/chapters?limit=$limit&offset=$offset"
			val response = webClient.httpGet(apiUrl).parseJson()

			if (!response.optBoolean("success", false)) break

			val data = response.getJSONObject("data")
			val chaptersArray = data.getJSONArray("chapters")

			chaptersArray.mapJSON { obj ->
				val chapterSlug = obj.getString("chapter_slug")
				val url = "/manga/$slug/$chapterSlug"
				MangaChapter(
					id = generateUid(url),
					title = obj.getString("chapter_name"),
					number = obj.optDouble("chapter_num", 0.0).toFloat(),
					volume = 0,
					url = url,
					uploadDate = dateFormat.parseSafe(obj.optString("updated_at", "")),
					source = source,
					scanlator = null,
					branch = null,
				)
			}.let { allChapters.addAll(it) }

			val pagination = data.optJSONObject("pagination")
			if (pagination == null || !pagination.optBoolean("has_more", false)) break
			offset += limit
		}

		return allChapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val scriptContent = doc.select("script:containsData(cdns =)").joinToString("\n") { it.data() }

		val cdns = extractJsArray(scriptContent, "cdns") + extractJsArray(scriptContent, "backupImage")
		val chapterImages = extractJsArray(scriptContent, "chapterImages")

		if (cdns.isNotEmpty() && chapterImages.isNotEmpty()) {
			val cdnBase = cdns.first().removeSuffix("/")
			return chapterImages.map { imagePath ->
				val imageUrl = "$cdnBase/${imagePath.removePrefix("/")}"
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		}

		// Fallback: try direct img tags
		return doc.select("div.container-chapter-reader > img, div#vungdoc img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun extractJsArray(scriptContent: String, arrayName: String): List<String> {
		val pattern = Regex("""$arrayName\s*=\s*\[([^\]]+)]""")
		val match = pattern.find(scriptContent) ?: return emptyList()
		return match.groupValues[1].split(",").map { value ->
			value.trim()
				.removeSurrounding("\"")
				.removeSurrounding("'")
				.replace("\\/", "/")
				.removeSuffix("/")
		}.filter { it.isNotBlank() }
	}

	private fun normalizeSearchQuery(query: String): String {
		var str = query.lowercase()
		str = str.replace("[àáạảãâầấậẩẫăằắặẳẵ]".toRegex(), "a")
		str = str.replace("[èéẹẻẽêềếệểễ]".toRegex(), "e")
		str = str.replace("[ìíịỉĩ]".toRegex(), "i")
		str = str.replace("[òóọỏõôồốộổỗơờớợởỡ]".toRegex(), "o")
		str = str.replace("[ùúụủũưừứựửữ]".toRegex(), "u")
		str = str.replace("[ỳýỵỷỹ]".toRegex(), "y")
		str = str.replace("đ".toRegex(), "d")
		str = str.replace("""[!@%^*()+\=<>?/,.:;'"&#\[\]~\-$|_ ]""".toRegex(), "_")
		str = str.replace("_+".toRegex(), "_")
		str = str.replace("""^_+|_+$""".toRegex(), "")
		return str
	}
}
