package wasidremin.gmccpa.match

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.io.File
import java.util.regex.Pattern

/**
 * File-backed store for [IconMatcher.IconTemplate]s (Phase 3).
 *
 * Layout mirrors the calibration captures: `filesDir/icon_templates/<sanitized-name>/template.json`.
 * The store itself does no image work — callers canonicalize via
 * [IconMatcher.IconTemplate.canonicalize] (pure, unit-tested) and hand the store a validated
 * template. Invalid JSON on disk is skipped with a log line, never thrown: a hand-edited or
 * half-written template must not break listing.
 */
object IconTemplateStore {
    private val log = ProbeLog.sub("icontmpl")
    private val UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+")

    const val BUILTIN_ASSET_DIR = "vision/equinox_2754x966"
    private val builtInCache = java.util.concurrent.ConcurrentHashMap<String, IconMatcher.IconTemplate>()

    fun templatesRootDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "icon_templates")

    fun sanitizeName(raw: String?): String {
        val cleaned = (raw ?: "").trim().replace(UNSAFE.toRegex(), "_").trim('.', '_').take(64)
        return cleaned.ifEmpty { "template" }
    }

    /** Insert or replace [template] under its sanitized name. Returns the directory written. */
    fun saveTemplate(ctx: Context, template: IconMatcher.IconTemplate): File {
        val root = templatesRootDir(ctx.applicationContext)
        root.mkdirs()
        val dir = File(root, sanitizeName(template.name))
        dir.mkdirs()
        // Write the stored name back sanitized so load/save round-trips through one canonical name.
        val stored = template.copy(name = dir.name)
        File(dir, "template.json").writeText(stored.toJson().toString(2), Charsets.UTF_8)
        // Optional preview for a human browsing the files dir; absence changes nothing.
        runCatching { File(dir, ".source_capture").writeText(template.sourceCaptureId ?: "", Charsets.UTF_8) }
        log.i("saved icon template \"${stored.name}\" (${stored.width}x${stored.height}, threshold=${stored.threshold})")
        return dir
    }

    fun loadUserTemplate(ctx: Context, name: String): IconMatcher.IconTemplate? {
        val file = File(File(templatesRootDir(ctx.applicationContext), sanitizeName(name)), "template.json")
        if (!file.isFile) return null
        return runCatching {
            IconMatcher.IconTemplate.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrElse { t ->
            log.w("skipping unreadable icon template \"$name\": ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun listTemplates(ctx: Context): List<IconMatcher.IconTemplate> {
        val root = templatesRootDir(ctx.applicationContext)
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { dir -> loadUserTemplate(ctx, dir.name) }
    }

    /** User-saved template if present, else the built-in Equinox template, else null. */
    fun loadTemplate(ctx: Context, name: String): IconMatcher.IconTemplate? {
        loadUserTemplate(ctx, name)?.let { return it }
        return loadBuiltIn(ctx, name)
    }

    fun loadBuiltIn(ctx: Context, name: String): IconMatcher.IconTemplate? {
        val key = sanitizeName(name)
        builtInCache[key]?.let { return it }
        return runCatching {
            ctx.applicationContext.assets.open("$BUILTIN_ASSET_DIR/$key.json").use { s ->
                IconMatcher.IconTemplate.fromJson(JSONObject(s.readBytes().toString(Charsets.UTF_8)))
            }
        }.getOrNull()?.also {
            builtInCache[key] = it
            log.i("built-in template \"$key\" loaded (${it.width}x${it.height})")
        }
    }

    fun sourceOf(ctx: Context, name: String): String = when {
        loadUserTemplate(ctx, name) != null -> "user"
        loadBuiltIn(ctx, name) != null -> "built-in"
        else -> "missing"
    }

    fun deleteTemplate(ctx: Context, name: String): Boolean {
        val dir = File(templatesRootDir(ctx.applicationContext), sanitizeName(name))
        if (!dir.isDirectory) return false
        val ok = dir.deleteRecursively()
        if (ok) log.i("deleted icon template \"$name\"")
        return ok
    }
}
