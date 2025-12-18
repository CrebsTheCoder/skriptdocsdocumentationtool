package net.skripthub.docstool.documentation
import com.google.gson.*
import com.google.gson.JsonArray
import net.skripthub.docstool.modals.AddonData
import net.skripthub.docstool.modals.SyntaxData
import java.io.BufferedWriter
import java.io.IOException
class SkriptDocsAddonFile(raw: Boolean) : FileType("json") {
    private val gson: Gson
    init {
        val gson = GsonBuilder().disableHtmlEscaping()
        if (!raw)
            gson.enableComplexMapKeySerialization().setPrettyPrinting()
        this.gson = gson.create()
    }
    @Throws(IOException::class)
    override fun write(writer: BufferedWriter, addon: AddonData) {
        val addonObject = JsonObject()
        val addonContent = JsonObject()
        addCategoryIfNotEmpty(addonContent, "event", addon.events)
        addCategoryIfNotEmpty(addonContent, "condition", addon.conditions)
        addCategoryIfNotEmpty(addonContent, "effect", addon.effects)
        addCategoryIfNotEmpty(addonContent, "expression", addon.expressions)
        addCategoryIfNotEmpty(addonContent, "type", addon.types)
        addCategoryIfNotEmpty(addonContent, "function", addon.functions)
        addCategoryIfNotEmpty(addonContent, "section", addon.sections)
        addCategoryIfNotEmpty(addonContent, "structure", addon.structures)
        addonObject.add(addon.name, addonContent)
        gson.toJson(addonObject, writer)
    }
    private fun addCategoryIfNotEmpty(addonContent: JsonObject, categoryName: String, syntaxList: MutableList<SyntaxData>) {
        if (syntaxList.isNotEmpty()) {
            val categoryObject = JsonObject()
            for (syntax in syntaxList) {
                if (syntax.id != null && syntax.name != null) {
                    val syntaxEntry = JsonObject()
                    syntaxEntry.addProperty("name", syntax.name)
                    val syntaxPattern = if (syntax.patterns != null && syntax.patterns!!.isNotEmpty()) {
                        syntax.patterns!!.joinToString("\n")
                    } else {
                        ""
                    }
                    syntaxEntry.addProperty("syntax", syntaxPattern)
                    val description = syntax.description?.joinToString("\n") ?: ""
                    syntaxEntry.addProperty("description", description)
                    val since = syntax.since?.firstOrNull() ?: ""
                    syntaxEntry.addProperty("since", since)
                    val example = syntax.examples?.joinToString("\n") ?: ""
                    syntaxEntry.addProperty("example", example)
                    val sourceToUse = syntax.properSource ?: syntax.source ?: ""
                    syntaxEntry.addProperty("source", sourceToUse)

                    // Add event values array if present
                    val eventValues = syntax.eventValues
                    if (eventValues != null && eventValues.isNotEmpty()) {
                        val eventValuesArray = JsonArray()
                        eventValues.forEach { eventValuesArray.add(it) }
                        syntaxEntry.add("event values", eventValuesArray)
                    }


                    // Add usage array as 'type usage' (for types)
                    val usage = syntax.typeUsage
                    if (usage != null && usage.isNotEmpty()) {
                        val typeUsageArray = JsonArray()
                        usage.forEach { typeUsageArray.add(it) }
                        syntaxEntry.add("type usage", typeUsageArray)
                    }

                    // Add changers array if present (for expressions/types)
                    val changers = syntax.changers
                    if (changers != null && changers.isNotEmpty()) {
                        val changersArray = JsonArray()
                        changers.forEach { changersArray.add(it) }
                        syntaxEntry.add("changers", changersArray)
                    }

                    // Add return type if present (for functions/expressions)
                    if (syntax.returnType != null) {
                        syntaxEntry.addProperty("return type", syntax.returnType)
                    }

                    // Add cancellable if present (for events)
                    if (syntax.cancellable != null) {
                        syntaxEntry.addProperty("cancellable", syntax.cancellable!!)
                    }

                    val entryKey = syntax.name ?: syntax.id ?: "unknown"
                    categoryObject.add(entryKey, syntaxEntry)
                }
            }
            if (categoryObject.size() > 0) {
                addonContent.add(categoryName, categoryObject)
            }
        }
    }
}