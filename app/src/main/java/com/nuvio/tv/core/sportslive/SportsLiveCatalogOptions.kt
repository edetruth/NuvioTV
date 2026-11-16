package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogOption

/** Which catalog extra the user can pin a value of, so the section loads only matching items. */
object SportsLiveCatalogOptions {
    private const val PREFERRED_EXTRA = "genre"

    /** The optional extra with a non-empty options list; `genre` if there are several, else the first. */
    fun eligibleExtra(catalog: CatalogDescriptor): CatalogExtra? {
        val eligible = catalog.extra.filter {
            !it.isRequired && it.name !in catalog.extraRequired && !it.options.isNullOrEmpty()
        }
        return eligible.firstOrNull { it.name == PREFERRED_EXTRA } ?: eligible.firstOrNull()
    }

    /**
     * The [stored] pin if [catalog] still offers it; null (All) when the catalog no longer has that
     * extra or that value among its options.
     */
    fun effectiveOption(catalog: CatalogDescriptor, stored: CatalogOption?): CatalogOption? {
        if (stored == null) return null
        val extra = eligibleExtra(catalog) ?: return null
        return stored.takeIf { it.extra == extra.name && it.value in extra.options.orEmpty() }
    }
}
