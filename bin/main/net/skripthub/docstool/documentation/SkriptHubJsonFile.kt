package net.skripthub.docstool.documentation
import com.google.gson.*
import net.skripthub.docstool.modals.AddonData
import net.skripthub.docstool.modals.SyntaxData
import java.io.BufferedWriter
import java.io.IOException
class SkriptHubJsonFile(raw: Boolean) : FileType("json") {
    private val gson: Gson
    init {
        val gson = GsonBuilder().disableHtmlEscaping()
        if (!raw)
            gson.enableComplexMapKeySerialization().setPrettyPrinting()
        this.gson = gson.create()
    }
    @Throws(IOException::class)
    override fun write(writer: BufferedWriter, addon: AddonData) {
        val rootObject = JsonObject()
        val metadata = JsonObject()
        metadata.addProperty("version", addon.metadata.version)
        rootObject.add("metadata", metadata)
        addCategoryArray(rootObject, "events", addon.events)
        addCategoryArray(rootObject, "conditions", addon.conditions)
        addCategoryArray(rootObject, "effects", addon.effects)
        addCategoryArray(rootObject, "expressions", addon.expressions)
        addCategoryArray(rootObject, "types", addon.types)
        addCategoryArray(rootObject, "functions", addon.functions)
        addCategoryArray(rootObject, "sections", addon.sections)
        addCategoryArray(rootObject, "structures", addon.structures)
        gson.toJson(rootObject, writer)
    }
    private fun addCategoryArray(rootObject: JsonObject, categoryName: String, syntaxList: MutableList<SyntaxData>) {
        if (syntaxList.isNotEmpty()) {
            val categoryArray = JsonArray()
            for (syntax in syntaxList) {
                if (syntax.id != null && syntax.name != null) {
                    val syntaxEntry = JsonObject()
                    syntaxEntry.addProperty("id", syntax.id)
                    syntaxEntry.addProperty("name", syntax.name)
                    if (syntax.description != null && syntax.description!!.isNotEmpty()) {
                        val descArray = JsonArray()
                        for (desc in syntax.description!!) {
                            descArray.add(desc)
                        }
                        syntaxEntry.add("description", descArray)
                    }
                    if (syntax.examples != null && syntax.examples!!.isNotEmpty()) {
                        val examplesArray = JsonArray()
                        for (example in syntax.examples!!) {
                            examplesArray.add(example)
                        }
                        syntaxEntry.add("examples", examplesArray)
                    }
                    if (syntax.since != null && syntax.since!!.isNotEmpty()) {
                        val sinceArray = JsonArray()
                        for (since in syntax.since!!) {
                            sinceArray.add(since)
                        }
                        syntaxEntry.add("since", sinceArray)
                    }
                    if (syntax.patterns != null && syntax.patterns!!.isNotEmpty()) {
                        val patternsArray = JsonArray()
                        for (pattern in syntax.patterns!!) {
                            patternsArray.add(pattern)
                        }
                        syntaxEntry.add("patterns", patternsArray)
                    }
                    if (syntax.eventValues != null && syntax.eventValues!!.isNotEmpty()) {
                        val eventValuesArray = JsonArray()
                        for (eventValue in syntax.eventValues!!) {
                            eventValuesArray.add(eventValue)
                        }
                        syntaxEntry.add("event values", eventValuesArray)
                    }
                    if (syntax.cancellable != null) {
                        syntaxEntry.addProperty("cancellable", syntax.cancellable!!)
                    }
                    if (syntax.returnType != null) {
                        syntaxEntry.addProperty("return type", syntax.returnType)
                    }
                    if (syntax.changers != null && syntax.changers!!.isNotEmpty()) {
                        val changersArray = JsonArray()
                        for (changer in syntax.changers!!) {
                            changersArray.add(changer)
                        }
                        syntaxEntry.add("changers", changersArray)
                    }
                    if (syntax.usage != null && syntax.usage!!.isNotEmpty()) {
                        val usageArray = JsonArray()
                        for (usage in syntax.usage!!) {
                            usageArray.add(usage)
                        }
                        syntaxEntry.add("usage", usageArray)
                    }
                    if (syntax.typeUsage != null && syntax.typeUsage!!.isNotEmpty()) {
                        val typeUsageArray = JsonArray()
                        for (typeUsage in syntax.typeUsage!!) {
                            typeUsageArray.add(typeUsage)
                        }
                        syntaxEntry.add("type usage", typeUsageArray)
                    }
                    categoryArray.add(syntaxEntry)
                }
            }
            if (categoryArray.size() > 0) {
                rootObject.add(categoryName, categoryArray)
            }
        }
    }
}