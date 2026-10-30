@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY

class SChapterImpl : SChapter {

    // Not lateinit: an extension that parses a chapter without a url or a name used to crash the
    // whole chapter list with UninitializedPropertyAccessException. The sync drops entries without
    // a url, so an empty default reaches the app as "this chapter can't be opened" instead.
    override var url: String = ""

    override var name: String = ""

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var date_upload: Long = 0

    override var memo: JsonObject = JsonObject.EMPTY
}
