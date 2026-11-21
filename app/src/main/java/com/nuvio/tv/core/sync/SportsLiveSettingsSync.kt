package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Sports & Live settings in the account's profile settings (#141). Only an allow-list goes up: followed
 * teams, reminder-only teams, My sports, Scores and Score delay, "When I open a game", "When a game ends",
 * the alert switches (close-game alerts too), the Catch up row,
 * reminders (without their addon source keys, and only those not past), hidden titles and spoiler protection.
 * Addon catalog choices, source memory, hints and catch-up marks stay on the TV: nothing tied to an addon's
 * ids, URLs or hosts is ever exported.
 *
 * Conflicts: each setting is last-write-wins by the time it was changed; the list settings merge per entry
 * (a union), with removals kept as dated tombstones so an entry removed on one TV is removed on the others.
 * The times are kept in two local keys, written by [stamp] on every change made through
 * [com.nuvio.tv.data.local.SportsLivePreferencesDataStore]. A tie goes to the copy being merged in, so two
 * TVs converge.
 */
object SportsLiveSettingsSync {

    private enum class Kind { STRING, INT, BOOLEAN, STRING_SET }

    /** Last-write-wins settings, by preference key name. */
    private val scalars: Map<String, Kind> = linkedMapOf(
        "sports_live_scores_mode" to Kind.STRING,
        "sports_live_score_delay_seconds" to Kind.INT,
        "sports_live_real_time_scores" to Kind.BOOLEAN,
        "sports_live_open_game" to Kind.STRING,
        "sports_live_remind_my_teams" to Kind.BOOLEAN,
        "sports_live_score_alerts" to Kind.BOOLEAN,
        "sports_live_full_time_alerts" to Kind.BOOLEAN,
        "sports_live_close_game_alerts" to Kind.BOOLEAN,
        "sports_live_game_end_action" to Kind.STRING,
        "sports_live_ok_on_live" to Kind.STRING,
        "sports_live_tip_off" to Kind.STRING,
        "sports_live_catch_up_row" to Kind.BOOLEAN,
        "sports_live_my_teams_spoilers" to Kind.BOOLEAN,
        "sports_live_my_teams_results_hidden" to Kind.BOOLEAN,
        "sports_live_my_sports" to Kind.STRING_SET
        // Not synced, as this TV's own: picture-in-picture, data saver, hover preview, the section and Home row
        // switches, "Hide links this TV can't play" (sports_live_hide_unplayable_links: what plays is this TV's
        // player), the dual screen layout (sports_live_dual_layout) and first-run setup done (sports_live_setup_done).
    )

    /** A list setting merged per entry: how its entries are read, named and cleaned for upload. */
    private class ListField(
        val key: String,
        /** Stored as a string set (titles) rather than a JSON array of objects. */
        val stringSet: Boolean = false,
        val idOf: (JsonElement) -> String?,
        /** The entry as uploaded; null leaves it out (a reminder whose game is long past). */
        val export: (JsonElement, Long) -> JsonElement? = { item, _ -> item },
        /** The entry kept when the other copy wins: [local] is this TV's own, when it had one. */
        val adopt: (remote: JsonElement, local: JsonElement?) -> JsonElement = { remote, _ -> remote }
    )

    private const val FOLLOWED = "sports_live_followed_teams"
    private const val REMINDED_TEAMS = "sports_live_reminded_teams"
    private const val HIDDEN = "sports_live_hidden_titles"
    private const val REMINDERS = "sports_live_reminders"
    private const val OPT_OUTS = "sports_live_reminder_opt_outs"
    const val WATCH_LATER = "sports_live_watch_later"

    /** Local only: when each setting last changed (`{"key": ms}`). */
    const val STAMPS_KEY = "sports_live_sync_stamps"

    /** Local only: each list entry's last change (`{"list": {"id": [ms, 1 when removed]}}`). */
    const val ITEMS_KEY = "sports_live_sync_items"

    /** Tombstones and stamps of entries are dropped after this long. */
    private const val TOMBSTONE_MS = 60L * 24 * 60 * 60_000L

    /** A reminder is uploaded while its game started under this long ago (as [com.nuvio.tv.core.sportslive.alerts.GameReminders] keeps it). */
    private const val REMINDER_KEEP_MS = 6 * 60 * 60_000L

    private val teamId: (JsonElement) -> String? = { item -> lower(item.field("name"))?.let { "$it|${lower(item.field("sport")).orEmpty()}" } }

    private val lists: List<ListField> = listOf(
        ListField(FOLLOWED, idOf = teamId),
        // Reminder-only teams (#154): stored as the follows are.
        ListField(REMINDED_TEAMS, idOf = teamId),
        ListField(HIDDEN, stringSet = true, idOf = { item -> lower((item as? JsonPrimitive)?.contentOrNull) }),
        reminders(REMINDERS),
        reminders(OPT_OUTS),
        ListField(WATCH_LATER, idOf = { item -> item.field("eventId")?.takeIf { it.isNotBlank() } })
    )

    private fun reminders(key: String) = ListField(
        key,
        idOf = { item ->
            item.field("start")?.let { start ->
                val sides = listOfNotNull(lower(item.field("home")), lower(item.field("away"))).sorted()
                if (sides.size == 2) "$start|${sides.joinToString("|")}" else "$start|${lower(item.field("title")).orEmpty()}"
            }
        },
        // The source keys are the addon's ids: never uploaded.
        export = { item, now ->
            val start = item.field("start")?.toLongOrNull()
            if (start == null || now - start > REMINDER_KEEP_MS) null
            else JsonObject((item as JsonObject).filterKeys { it != "keys" } + ("keys" to JsonPrimitive("")))
        },
        // A reminder this TV already has keeps its own source keys.
        adopt = { remote, local ->
            val keys = (local as? JsonObject)?.get("keys")
            if (keys == null || remote !is JsonObject) remote else JsonObject(remote + ("keys" to keys))
        }
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [after] with the times of what changed since [before] recorded at [nowMs]: a setting's stamp, and each
     * list entry added, changed or removed. Unchanged when nothing synced changed.
     */
    fun stamp(before: Preferences, after: Preferences, nowMs: Long): Preferences {
        val changedScalars = scalars.keys.filter { valueOf(before, it) != valueOf(after, it) }
        val listChanges = lists.mapNotNull { field ->
            val old = itemsOf(before, field)
            val new = itemsOf(after, field)
            if (old == new) return@mapNotNull null
            val changes = HashMap<String, Boolean>()
            new.forEach { (id, item) -> if (old[id] != item) changes[id] = false }
            old.keys.filterNot { it in new }.forEach { changes[it] = true }
            field.key to changes
        }
        if (changedScalars.isEmpty() && listChanges.isEmpty()) return after
        val prefs = after.toMutablePreferences()
        if (changedScalars.isNotEmpty()) {
            val stamps = stampsOf(prefs).toMutableMap()
            changedScalars.forEach { stamps[it] = nowMs }
            writeStamps(prefs, stamps)
        }
        if (listChanges.isNotEmpty()) {
            val meta = itemMetaOf(prefs).mapValues { it.value.toMutableMap() }.toMutableMap()
            listChanges.forEach { (key, changes) ->
                val entries = meta.getOrPut(key) { mutableMapOf() }
                changes.forEach { (id, removed) -> entries[id] = Meta(nowMs, removed) }
            }
            writeItemMeta(prefs, meta, nowMs)
        }
        return prefs
    }

    /** The feature's upload: the allow-listed settings with their times, and the lists' entries and tombstones. */
    fun export(prefs: Preferences, nowMs: Long): JsonObject {
        val stamps = stampsOf(prefs)
        val meta = itemMetaOf(prefs)
        // Nothing to sync exports {} (no "fields"/"lists"), as a cloud copy without Sports & Live reads: equal
        // signatures, so a pull is never echoed back as a push.
        val fieldsOut = buildJsonObject {
                scalars.keys.forEach { key ->
                    val value = valueOf(prefs, key)
                    val at = stamps[key]
                    if (value == null && at == null) return@forEach
                    put(key, buildJsonObject {
                        put("at", at ?: 0L)
                        put("value", value ?: JsonNull)
                    })
                }
        }
        val listsOut = buildJsonObject {
                lists.forEach { field ->
                    val items = itemsOf(prefs, field)
                    val entries = meta[field.key].orEmpty()
                    val out = buildJsonObject {
                        items.forEach { (id, item) ->
                            val exported = field.export(item, nowMs) ?: return@forEach
                            put(id, buildJsonObject {
                                put("at", entries[id]?.at ?: 0L)
                                put("item", exported)
                            })
                        }
                        entries.forEach { (id, entry) ->
                            if (!entry.removed || id in items) return@forEach
                            put(id, buildJsonObject {
                                put("at", entry.at)
                                put("removed", true)
                            })
                        }
                    }
                    if (out.isNotEmpty()) put(field.key, out)
                }
        }
        return buildJsonObject {
            if (fieldsOut.isNotEmpty()) put("fields", fieldsOut)
            if (listsOut.isNotEmpty()) put("lists", listsOut)
        }
    }

    /**
     * Merges [remote] (another TV's [export]) into [prefs]: per setting the later change wins (a tie: the
     * remote one), per list entry likewise, so the lists end up the union of both, less removed entries.
     * A malformed upload changes nothing.
     */
    fun merge(prefs: MutablePreferences, remote: JsonObject, nowMs: Long) {
        val fields = remote["fields"] as? JsonObject
        val remoteLists = remote["lists"] as? JsonObject
        if (fields != null) {
            val stamps = stampsOf(prefs).toMutableMap()
            fields.forEach { (key, element) ->
                val kind = scalars[key] ?: return@forEach
                val entry = element as? JsonObject ?: return@forEach
                val at = (entry["at"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                val localAt = stamps[key] ?: if (valueOf(prefs, key) != null) 0L else -1L
                if (at < localAt) return@forEach
                val value = entry["value"]
                if (value == null || value is JsonNull) {
                    prefs.remove(stringPreferencesKey(key))
                    prefs.remove(intPreferencesKey(key))
                    prefs.remove(booleanPreferencesKey(key))
                    prefs.remove(stringSetPreferencesKey(key))
                } else if (!write(prefs, key, kind, value)) {
                    return@forEach
                }
                stamps[key] = at
            }
            writeStamps(prefs, stamps)
        }
        if (remoteLists != null) {
            val meta = itemMetaOf(prefs).mapValues { it.value.toMutableMap() }.toMutableMap()
            lists.forEach { field ->
                val incoming = remoteLists[field.key] as? JsonObject ?: return@forEach
                val local = itemsOf(prefs, field)
                val entries = meta.getOrPut(field.key) { mutableMapOf() }
                val merged = LinkedHashMap(local)
                incoming.forEach { (id, element) ->
                    val entry = element as? JsonObject ?: return@forEach
                    val at = (entry["at"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val removed = (entry["removed"] as? JsonPrimitive)?.booleanOrNull == true
                    val localAt = entries[id]?.at ?: if (id in local) 0L else -1L
                    if (at < localAt) return@forEach
                    if (removed) {
                        merged.remove(id)
                    } else {
                        val item = entry["item"] ?: return@forEach
                        if (field.idOf(item) != id) return@forEach
                        merged[id] = field.adopt(item, local[id])
                    }
                    entries[id] = Meta(at, removed)
                }
                if (merged != local) writeItems(prefs, field, merged.values.toList())
            }
            writeItemMeta(prefs, meta, nowMs)
        }
    }

    /** [json] with its object keys sorted at every level: equal settings give equal text, whatever the key order. */
    fun canonical(json: JsonElement): String = when (json) {
        is JsonObject -> json.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonical(v)}" }
        is JsonArray -> json.joinToString(",", "[", "]") { canonical(it) }
        else -> json.toString()
    }

    private data class Meta(val at: Long, val removed: Boolean)

    private fun valueOf(prefs: Preferences, key: String): JsonElement? = when (scalars[key]) {
        Kind.STRING -> prefs[stringPreferencesKey(key)]?.let(::JsonPrimitive)
        Kind.INT -> prefs[intPreferencesKey(key)]?.let(::JsonPrimitive)
        Kind.BOOLEAN -> prefs[booleanPreferencesKey(key)]?.let(::JsonPrimitive)
        Kind.STRING_SET -> prefs[stringSetPreferencesKey(key)]?.let { set -> JsonArray(set.sorted().map(::JsonPrimitive)) }
        null -> null
    }

    private fun write(prefs: MutablePreferences, key: String, kind: Kind, value: JsonElement): Boolean {
        when (kind) {
            Kind.STRING -> prefs[stringPreferencesKey(key)] = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            Kind.INT -> prefs[intPreferencesKey(key)] = (value as? JsonPrimitive)?.intOrNull ?: return false
            Kind.BOOLEAN -> prefs[booleanPreferencesKey(key)] = (value as? JsonPrimitive)?.booleanOrNull ?: return false
            Kind.STRING_SET -> prefs[stringSetPreferencesKey(key)] =
                (value as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull } ?: return false
        }
        return true
    }

    /** [field]'s entries by id, in stored order; an unreadable value reads as none. */
    private fun itemsOf(prefs: Preferences, field: ListField): Map<String, JsonElement> {
        val items: List<JsonElement> = if (field.stringSet) {
            prefs[stringSetPreferencesKey(field.key)].orEmpty().map(::JsonPrimitive)
        } else {
            val raw = prefs[stringPreferencesKey(field.key)]
            if (raw.isNullOrBlank()) emptyList() else runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull().orEmpty()
        }
        val out = LinkedHashMap<String, JsonElement>()
        items.forEach { item -> field.idOf(item)?.let { id -> out.putIfAbsent(id, item) } }
        return out
    }

    private fun writeItems(prefs: MutablePreferences, field: ListField, items: List<JsonElement>) {
        if (field.stringSet) {
            prefs[stringSetPreferencesKey(field.key)] = items.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull }
        } else {
            prefs[stringPreferencesKey(field.key)] = JsonArray(items).toString()
        }
    }

    private fun stampsOf(prefs: Preferences): Map<String, Long> {
        val raw = prefs[stringPreferencesKey(STAMPS_KEY)] ?: return emptyMap()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }.toMap()
    }

    private fun writeStamps(prefs: MutablePreferences, stamps: Map<String, Long>) {
        if (stamps.isEmpty()) return
        prefs[stringPreferencesKey(STAMPS_KEY)] = JsonObject(stamps.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()
    }

    private fun itemMetaOf(prefs: Preferences): Map<String, Map<String, Meta>> {
        val raw = prefs[stringPreferencesKey(ITEMS_KEY)] ?: return emptyMap()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            val entries = (value as? JsonObject)?.mapNotNull { (id, entry) ->
                val pair = entry as? JsonArray ?: return@mapNotNull null
                val at = (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                id to Meta(at, (pair.getOrNull(1) as? JsonPrimitive)?.intOrNull == 1)
            }?.toMap() ?: return@mapNotNull null
            key to entries
        }.toMap()
    }

    /** Writes the entries' times, dropping tombstones over [TOMBSTONE_MS] old. */
    private fun writeItemMeta(prefs: MutablePreferences, meta: Map<String, Map<String, Meta>>, nowMs: Long) {
        val kept = meta.mapValues { (_, entries) ->
            entries.filter { (_, m) -> !m.removed || nowMs - m.at < TOMBSTONE_MS }
        }.filterValues { it.isNotEmpty() }
        if (kept.isEmpty()) {
            prefs.remove(stringPreferencesKey(ITEMS_KEY))
            return
        }
        prefs[stringPreferencesKey(ITEMS_KEY)] = JsonObject(kept.toSortedMap().mapValues { (_, entries) ->
            JsonObject(entries.toSortedMap().mapValues { (_, m) -> JsonArray(listOf(JsonPrimitive(m.at), JsonPrimitive(if (m.removed) 1 else 0))) })
        }).toString()
    }

    private fun JsonElement.field(name: String): String? = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun lower(value: String?): String? =
        value?.trim()?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() }
}
