package com.example.features.searchplay

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.example.core.security.SecureKeyStore
import com.example.data.local.SearchRecipeDao
import com.example.data.local.SearchRecipeEntity
import com.example.domain.tools.AppOpenStatus
import com.example.domain.tools.AppResolver
import com.example.domain.tools.ToolExecutionResult
import com.example.features.safety.SafetyManager
import com.example.service.BabyAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

enum class SearchLaunchMode {
    MEDIA_PLAY_INTENT,
    DEEP_LINK_URI,
    APP_THEN_ACCESSIBILITY
}

data class SearchRecipeSpec(
    val appKey: String,
    val displayName: String,
    val packageName: String,
    val aliases: List<String>,
    val launchMode: SearchLaunchMode,
    val deepLinkTemplate: String,
    val searchHints: List<String>,
    val skipLabels: List<String>,
    val resultHints: List<String>,
    val tapFirstResultByDefault: Boolean = true,
    val requiresConfirmForSocialOrOrder: Boolean = false
) {
    fun toJsonString(): String = JSONObject().apply {
        put("app_key", appKey)
        put("display_name", displayName)
        put("package_name", packageName)
        put("aliases", JSONArray(aliases))
        put("launch_mode", launchMode.name)
        put("deep_link_template", deepLinkTemplate)
        put("search_hints", JSONArray(searchHints))
        put("skip_labels", JSONArray(skipLabels))
        put("result_hints", JSONArray(resultHints))
        put("tap_first_result_by_default", tapFirstResultByDefault)
        put("requires_confirm_for_social_or_order", requiresConfirmForSocialOrOrder)
    }.toString(2)
}

data class SearchPlayIntent(
    val appKey: String,
    val query: String,
    val autoPlay: Boolean
)

/**
 * Editable JSON recipe engine for Search-and-Play across 10 popular Indian Android apps.
 * Combines standard Android deep links / media intents with semantic Accessibility UI tree inspection
 * (zero fixed pixel coordinates, skips Ads/Sponsored/Shorts, retries once, enforces payment blocklist).
 */
@Singleton
class SearchPlayEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchRecipeDao: SearchRecipeDao?,
    private val appResolver: AppResolver,
    private val keyStore: SecureKeyStore
) {
    fun observeRecipes(): Flow<List<SearchRecipeEntity>>? = searchRecipeDao?.observeAll()

    suspend fun ensureDefaultRecipesSeeded() {
        val dao = searchRecipeDao ?: return
        val existing = dao.getAllSnapshot()
        if (existing.size < DEFAULT_RECIPES.size) {
            dao.insertMissing(
                DEFAULT_RECIPES.map { spec ->
                    SearchRecipeEntity(
                        appKey = spec.appKey,
                        displayName = spec.displayName,
                        packageName = spec.packageName,
                        recipeJson = spec.toJsonString()
                    )
                }
            )
        }
    }

    suspend fun getAllSpecs(): List<SearchRecipeSpec> {
        ensureDefaultRecipesSeeded()
        val entities = searchRecipeDao?.getAllSnapshot().orEmpty()
        if (entities.isEmpty()) return DEFAULT_RECIPES
        return entities.mapNotNull { parseRecipeJson(it.recipeJson).getOrNull() }
    }

    suspend fun saveRecipeJson(appKey: String, rawJson: String): Result<SearchRecipeSpec> {
        val parsed = parseRecipeJson(rawJson)
        val spec = parsed.getOrElse { return Result.failure(it) }
        searchRecipeDao?.upsert(
            SearchRecipeEntity(
                appKey = appKey.lowercase().trim(),
                displayName = spec.displayName,
                packageName = spec.packageName,
                recipeJson = spec.toJsonString(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return Result.success(spec)
    }

    suspend fun resetRecipeToDefault(appKey: String): Boolean {
        val defaultSpec = DEFAULT_RECIPES.firstOrNull { it.appKey.equals(appKey, ignoreCase = true) } ?: return false
        searchRecipeDao?.upsert(
            SearchRecipeEntity(
                appKey = defaultSpec.appKey,
                displayName = defaultSpec.displayName,
                packageName = defaultSpec.packageName,
                recipeJson = defaultSpec.toJsonString(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun executeSearchAndPlay(
        appQuery: String,
        query: String,
        autoPlay: Boolean = true
    ): ToolExecutionResult {
        val cleanQuery = query.trim()
        val cleanApp = appQuery.trim()
        if (cleanQuery.isBlank()) {
            return ToolExecutionResult(false, "Kya search karna hai? Query empty hai.")
        }
        if (isBlockedBankingOrPaymentTarget(cleanApp, cleanQuery)) {
            return ToolExecutionResult(
                false,
                "Safety guard: Banking aur payment apps/queries par automated Search-and-Play blocked hai."
            )
        }

        ensureDefaultRecipesSeeded()
        val specs = getAllSpecs()
        val spec = resolveRecipeSpec(cleanApp, specs)
            ?: return fallbackGenericAppSearch(cleanApp, cleanQuery)

        // Check foreground sensitive/payment block before touching screen
        BabyAccessibilityService.instance?.let { acc ->
            val pkg = acc.getActivePackageName()
            SafetyManager.isBlockedScreen(pkg, null)?.let { blocked ->
                return ToolExecutionResult(false, "Safety guard: $blocked screen par automation blocked hai.")
            }
        }

        // Launch via Media Play Intent or Deep-link URI first when recipe supports it
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val deepLinkOk = when (spec.launchMode) {
            SearchLaunchMode.MEDIA_PLAY_INTENT -> tryLaunchMediaPlayIntent(spec, cleanQuery)
            SearchLaunchMode.DEEP_LINK_URI -> tryLaunchDeepLink(spec, encoded)
            SearchLaunchMode.APP_THEN_ACCESSIBILITY -> false
        }

        if (!deepLinkOk) {
            val openOutcome = appResolver.openApp(spec.packageName.ifBlank { spec.displayName })
            if (!openOutcome.ok && openOutcome.status == AppOpenStatus.NOT_FOUND) {
                return ToolExecutionResult(false, "${spec.displayName} phone mein install nahi mila.")
            }
            if (!openOutcome.ok && openOutcome.status == AppOpenStatus.BLOCKED) {
                return ToolExecutionResult(false, openOutcome.message)
            }
        }

        // If recipe only opens search results (e.g., Amazon / Flipkart / Maps / Play Store when !autoPlay),
        // or Accessibility isn't enabled, communicate cleanly!
        val acc = BabyAccessibilityService.instance
        if (acc == null) {
            return if (deepLinkOk) {
                ToolExecutionResult(
                    true,
                    "${spec.displayName} mein \"$cleanQuery\" khol diya. Auto-tap ke liye Accessibility Permission ON karein."
                )
            } else {
                ToolExecutionResult(
                    false,
                    "${spec.displayName} khol diya, lekin \"$cleanQuery\" search aur play karne ke liye Accessibility Service ON karni hogi."
                )
            }
        }

        // Wait briefly for window settle & guard active screen
        delay(900L)
        SafetyManager.isBlockedScreen(acc.getActivePackageName(), null)?.let { blocked ->
            return ToolExecutionResult(false, "Safety guard: $blocked screen par automation rok diya.")
        }

        // Perform Accessibility semantic search if deep-link didn't pre-load query
        if (!deepLinkOk || spec.launchMode == SearchLaunchMode.APP_THEN_ACCESSIBILITY) {
            var searched = acc.performSemanticSearchInput(spec.searchHints, cleanQuery)
            if (!searched) {
                delay(850L)
                searched = acc.performSemanticSearchInput(spec.searchHints, cleanQuery)
            }
            if (!searched) {
                return ToolExecutionResult(
                    false,
                    "${spec.displayName} khula lekin search bar nahi mila (app layout alag ho sakta hai)."
                )
            }
            delay(1_100L)
        }

        val shouldTapResult = autoPlay && spec.tapFirstResultByDefault
        if (!shouldTapResult) {
            return ToolExecutionResult(
                true,
                "${spec.displayName} par \"$cleanQuery\" ke results dikh rahe hain."
            )
        }

        // Try tapping first clean non-Ad/non-Shorts result with 1 retry
        var tappedTitle = acc.tapFirstCleanResultNode(
            query = cleanQuery,
            resultHints = spec.resultHints,
            skipLabels = spec.skipLabels
        )
        if (tappedTitle == null) {
            delay(900L)
            tappedTitle = acc.tapFirstCleanResultNode(
                query = cleanQuery,
                resultHints = spec.resultHints,
                skipLabels = spec.skipLabels
            )
        }

        return if (tappedTitle != null) {
            ToolExecutionResult(
                true,
                "${spec.displayName} par \"$cleanQuery\" chala diya ($tappedTitle)."
            )
        } else {
            ToolExecutionResult(
                true,
                "${spec.displayName} par \"$cleanQuery\" search ho gaya hai (first result manually tap kar sakte hain)."
            )
        }
    }

    private suspend fun fallbackGenericAppSearch(appQuery: String, query: String): ToolExecutionResult {
        val openOutcome = appResolver.openApp(appQuery)
        if (!openOutcome.ok) return ToolExecutionResult(false, openOutcome.message)
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(true, "$appQuery khol diya (in-app search ke liye Accessibility ON karein).")
        delay(900L)
        val searched = acc.performSemanticSearchInput(listOf("search", "khoj", "find"), query)
        return if (searched) {
            ToolExecutionResult(true, "$appQuery mein \"$query\" search kar diya.")
        } else {
            ToolExecutionResult(false, "$appQuery khol diya, lekin search box nahi mila.")
        }
    }

    private fun tryLaunchMediaPlayIntent(spec: SearchRecipeSpec, query: String): Boolean {
        if (spec.packageName.isNotBlank() && !isPackageInstalled(spec.packageName)) return false
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            if (spec.packageName.isNotBlank()) setPackage(spec.packageName)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            val encoded = URLEncoder.encode(query, "UTF-8")
            tryLaunchDeepLink(spec, encoded)
        }
    }

    private fun tryLaunchDeepLink(spec: SearchRecipeSpec, encodedQuery: String): Boolean {
        if (spec.deepLinkTemplate.isBlank()) return false
        val uriString = spec.deepLinkTemplate.replace("{query}", encodedQuery)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            if (spec.packageName.isNotBlank() && isPackageInstalled(spec.packageName)) {
                setPackage(spec.packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    companion object {
        private val BLOCKED_PAYMENT_KEYWORDS = listOf(
            "gpay", "google pay", "phonepe", "paytm", "bhim", "upi",
            "netbanking", "bank", "sbi", "hdfc", "icici", "axis", "kotak", "cred"
        )

        fun isBlockedBankingOrPaymentTarget(appQuery: String, query: String): Boolean {
            val combined = "$appQuery $query".lowercase()
            return BLOCKED_PAYMENT_KEYWORDS.any { combined.contains(it) }
        }

        fun resolveRecipeSpec(
            appQuery: String,
            recipes: List<SearchRecipeSpec> = DEFAULT_RECIPES
        ): SearchRecipeSpec? {
            val norm = appQuery.lowercase().trim()
            if (norm.isBlank()) return null
            return recipes.firstOrNull { spec ->
                spec.appKey.equals(norm, ignoreCase = true) ||
                    spec.displayName.equals(norm, ignoreCase = true) ||
                    spec.packageName.equals(norm, ignoreCase = true) ||
                    spec.aliases.any { it.equals(norm, ignoreCase = true) || norm.contains(it, ignoreCase = true) }
            }
        }

        fun parseRecipeJson(rawJson: String): Result<SearchRecipeSpec> = runCatching {
            val obj = JSONObject(rawJson)
            val appKey = obj.getString("app_key").trim()
            require(appKey.isNotEmpty()) { "app_key missing" }
            val displayName = obj.getString("display_name").trim()
            val pkg = obj.optString("package_name", "").trim()
            val modeStr = obj.optString("launch_mode", SearchLaunchMode.DEEP_LINK_URI.name)
            val launchMode = runCatching { SearchLaunchMode.valueOf(modeStr) }
                .getOrDefault(SearchLaunchMode.DEEP_LINK_URI)

            fun jsonStringList(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }
                }
            }

            SearchRecipeSpec(
                appKey = appKey,
                displayName = displayName,
                packageName = pkg,
                aliases = jsonStringList("aliases"),
                launchMode = launchMode,
                deepLinkTemplate = obj.optString("deep_link_template", ""),
                searchHints = jsonStringList("search_hints"),
                skipLabels = jsonStringList("skip_labels"),
                resultHints = jsonStringList("result_hints"),
                tapFirstResultByDefault = obj.optBoolean("tap_first_result_by_default", true),
                requiresConfirmForSocialOrOrder = obj.optBoolean("requires_confirm_for_social_or_order", false)
            )
        }

        /**
         * Parses natural Hinglish/English Search-and-Play phrases like:
         * - "YouTube par Arijit Singh ka gaana bajao"
         * - "Spotify pe Kesariya chalao"
         * - "Amazon par wireless earbuds dhoondo"
         * - "Maps par India Gate ka route dikhao"
         */
        fun extractSearchPlayIntent(command: String): SearchPlayIntent? {
            val trimmed = command.trim()
            if (trimmed.length < 6) return null
            val lower = trimmed.lowercase()

            val pattern = Regex(
                """^([a-z0-9\s]+?)\s+(?:par|pe|mein|me|on|in)\s+(.+?)\s+(?:ka\s+gaana\s+bajao|ka\s+song\s+bajao|ka\s+route\s+dikhao|route\s+dikhao|gaana\s+bajao|bajao|chalao|chala\s+do|play\s+karo|play|dhoondo|dhundo|search\s+karo|search|dikhao|find)$""",
                RegexOption.IGNORE_CASE
            )
            val m = pattern.find(trimmed) ?: return null
            val appCandidate = m.groupValues[1].trim()
            var queryCandidate = m.groupValues[2].trim()
            queryCandidate = queryCandidate
                .removeSuffix(" ka gaana")
                .removeSuffix(" ka song")
                .removeSuffix(" ka route")
                .removeSuffix(" ka video")
                .trim()
            if (queryCandidate.isBlank()) return null

            val spec = resolveRecipeSpec(appCandidate) ?: return null
            val autoPlay = lower.contains("bajao") || lower.contains("chalao") ||
                lower.contains("chala do") || lower.contains("play") || lower.contains("route")
            return SearchPlayIntent(
                appKey = spec.appKey,
                query = queryCandidate,
                autoPlay = autoPlay
            )
        }

        val DEFAULT_RECIPES: List<SearchRecipeSpec> = listOf(
            SearchRecipeSpec(
                appKey = "youtube",
                displayName = "YouTube",
                packageName = "com.google.android.youtube",
                aliases = listOf("youtube", "yutub", "yt", "you tube"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "https://www.youtube.com/results?search_query={query}",
                searchHints = listOf("search", "search youtube"),
                skipLabels = listOf("Ad", "Sponsored", "Promoted", "Shorts", "People also watched"),
                resultHints = listOf("minutes", "views", "play", "watch"),
                tapFirstResultByDefault = true
            ),
            SearchRecipeSpec(
                appKey = "youtube_music",
                displayName = "YouTube Music",
                packageName = "com.google.android.apps.youtube.music",
                aliases = listOf("youtube music", "yt music", "ytm"),
                launchMode = SearchLaunchMode.MEDIA_PLAY_INTENT,
                deepLinkTemplate = "https://music.youtube.com/search?q={query}",
                searchHints = listOf("search"),
                skipLabels = listOf("Ad", "Sponsored"),
                resultHints = listOf("song", "play", "artist"),
                tapFirstResultByDefault = true
            ),
            SearchRecipeSpec(
                appKey = "spotify",
                displayName = "Spotify",
                packageName = "com.spotify.music",
                aliases = listOf("spotify", "spotifi", "gaana spotify"),
                launchMode = SearchLaunchMode.MEDIA_PLAY_INTENT,
                deepLinkTemplate = "spotify:search:{query}",
                searchHints = listOf("search", "what do you want to play"),
                skipLabels = listOf("Ad", "Sponsored"),
                resultHints = listOf("song", "track", "play"),
                tapFirstResultByDefault = true
            ),
            SearchRecipeSpec(
                appKey = "instagram",
                displayName = "Instagram",
                packageName = "com.instagram.android",
                aliases = listOf("instagram", "insta", "ig"),
                launchMode = SearchLaunchMode.APP_THEN_ACCESSIBILITY,
                deepLinkTemplate = "",
                searchHints = listOf("search", "search_tab", "explore"),
                skipLabels = listOf("Sponsored", "Ad"),
                resultHints = listOf("reel", "profile", "account"),
                tapFirstResultByDefault = false,
                requiresConfirmForSocialOrOrder = true
            ),
            SearchRecipeSpec(
                appKey = "facebook",
                displayName = "Facebook",
                packageName = "com.facebook.katana",
                aliases = listOf("facebook", "fb"),
                launchMode = SearchLaunchMode.APP_THEN_ACCESSIBILITY,
                deepLinkTemplate = "",
                searchHints = listOf("search facebook", "search"),
                skipLabels = listOf("Sponsored", "Ad"),
                resultHints = listOf("profile", "page", "video"),
                tapFirstResultByDefault = false,
                requiresConfirmForSocialOrOrder = true
            ),
            SearchRecipeSpec(
                appKey = "amazon",
                displayName = "Amazon",
                packageName = "in.amazon.mShop.android.shopping",
                aliases = listOf("amazon", "amzn", "amazon india"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "https://www.amazon.in/s?k={query}",
                searchHints = listOf("search amazon", "search"),
                skipLabels = listOf("Sponsored", "Ad"),
                resultHints = listOf("stars", "price", "rs", "₹"),
                tapFirstResultByDefault = false,
                requiresConfirmForSocialOrOrder = true
            ),
            SearchRecipeSpec(
                appKey = "flipkart",
                displayName = "Flipkart",
                packageName = "com.flipkart.android",
                aliases = listOf("flipkart", "flipcart", "fk"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "https://www.flipkart.com/search?q={query}",
                searchHints = listOf("search for products", "search"),
                skipLabels = listOf("Sponsored", "Ad"),
                resultHints = listOf("₹", "rating"),
                tapFirstResultByDefault = false,
                requiresConfirmForSocialOrOrder = true
            ),
            SearchRecipeSpec(
                appKey = "maps",
                displayName = "Google Maps",
                packageName = "com.google.android.apps.maps",
                aliases = listOf("maps", "google maps", "map", "rasta", "route"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "geo:0,0?q={query}",
                searchHints = listOf("search here", "search"),
                skipLabels = listOf("Ad", "Sponsored"),
                resultHints = listOf("directions", "start", "route", "min"),
                tapFirstResultByDefault = true
            ),
            SearchRecipeSpec(
                appKey = "play_store",
                displayName = "Play Store",
                packageName = "com.android.vending",
                aliases = listOf("play store", "playstore", "google play"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "market://search?q={query}&c=apps",
                searchHints = listOf("search apps", "search"),
                skipLabels = listOf("Ad", "Sponsored"),
                resultHints = listOf("install", "rating", "mb"),
                tapFirstResultByDefault = false
            ),
            SearchRecipeSpec(
                appKey = "chrome",
                displayName = "Chrome",
                packageName = "com.android.chrome",
                aliases = listOf("chrome", "browser", "google chrome"),
                launchMode = SearchLaunchMode.DEEP_LINK_URI,
                deepLinkTemplate = "https://www.google.com/search?q={query}",
                searchHints = listOf("search or type web address", "search"),
                skipLabels = listOf("Sponsored", "Ad"),
                resultHints = listOf("http", "www"),
                tapFirstResultByDefault = false
            )
        )
    }
}
