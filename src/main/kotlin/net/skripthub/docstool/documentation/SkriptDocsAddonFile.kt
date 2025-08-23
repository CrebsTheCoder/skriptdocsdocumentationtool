package net.skripthub.docstool.documentation

import com.google.gson.*
import net.skripthub.docstool.modals.AddonData
import net.skripthub.docstool.modals.SyntaxData
import java.io.BufferedWriter
import java.io.IOException

/**
 * Generates JSON files in the skriptdocs-addon-name.json format
 * with nested structure: Addon -> Category -> Syntax entries
 */
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
        
        // Add each category if it has entries
        addCategoryIfNotEmpty(addonContent, "event", addon.events)
        addCategoryIfNotEmpty(addonContent, "condition", addon.conditions)
        addCategoryIfNotEmpty(addonContent, "effect", addon.effects)
        addCategoryIfNotEmpty(addonContent, "expression", addon.expressions)
        addCategoryIfNotEmpty(addonContent, "type", addon.types)
        addCategoryIfNotEmpty(addonContent, "function", addon.functions)
        addCategoryIfNotEmpty(addonContent, "section", addon.sections)
        addCategoryIfNotEmpty(addonContent, "structure", addon.structures)
        
        // Add the addon content to the main object
        addonObject.add(addon.name, addonContent)
        
        gson.toJson(addonObject, writer)
    }

    private fun addCategoryIfNotEmpty(addonContent: JsonObject, categoryName: String, syntaxList: MutableList<SyntaxData>) {
        if (syntaxList.isNotEmpty()) {
            val categoryObject = JsonObject()
            
            for (syntax in syntaxList) {
                if (syntax.id != null && syntax.name != null) {
                    val syntaxEntry = JsonObject()
                    
                    // name
                    syntaxEntry.addProperty("name", syntax.name)
                    
                    // syntax - use the first pattern if available
                    val syntaxPattern = syntax.patterns?.firstOrNull() ?: ""
                    syntaxEntry.addProperty("syntax", syntaxPattern)
                    
                    // description - join description array with newlines
                    val description = syntax.description?.joinToString("\n") ?: ""
                    syntaxEntry.addProperty("description", description)
                    
                    // since - use the first since value if available
                    val since = syntax.since?.firstOrNull() ?: ""
                    syntaxEntry.addProperty("since", since)
                    
                    // examples - join examples array with newlines
                    val examples = syntax.examples?.joinToString("\n") ?: ""
                    syntaxEntry.addProperty("examples", examples)
                    
                    // source - the file location where this syntax is defined
                    syntaxEntry.addProperty("source", syntax.source ?: "")
                    
                    // Use the syntax name as the key, or fall back to ID if name is somehow null
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