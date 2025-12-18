package net.skripthub.docstool.documentation

import ch.njol.skript.Skript
import ch.njol.skript.classes.ClassInfo
import ch.njol.skript.lang.SkriptEventInfo
import ch.njol.skript.lang.SyntaxElementInfo
import ch.njol.skript.lang.function.Functions
import ch.njol.skript.registrations.Classes
import net.skripthub.docstool.modals.AddonData
import net.skripthub.docstool.modals.AddonMetadata
import net.skripthub.docstool.modals.SyntaxData
import net.skripthub.docstool.utils.EventValuesGetter
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.skriptlang.skript.bukkit.registration.BukkitRegistryKeys
import org.skriptlang.skript.registration.SyntaxInfo
import org.skriptlang.skript.registration.SyntaxRegistry
import java.io.*
import java.lang.reflect.Field
import java.util.*

class BuildDocs(private val instance: JavaPlugin, private val sender: CommandSender?) : Runnable {

    companion object {
        // Deep field finder for reflection
        private fun findDeepField(clazz: Class<*>, fieldName: String): Field? {
            var currentClass: Class<*>? = clazz
            while (currentClass != null) {
                try {
                    return currentClass.getDeclaredField(fieldName).apply { isAccessible = true }
                } catch (e: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                } catch (e: Exception) {
                    return null
                }
            }
            return null
        }
    }

    private var addonMap: HashMap<String, AddonData> = hashMapOf()
    private var addonPackageMap: HashMap<String, String> = hashMapOf(
        "org.skriptlang.skript" to "ch.njol.skript",
        "Skript" to "ch.njol.skript"
    )

    private val fileType: FileType = JsonFile(false)
    private val skriptDocsFileType: FileType = SkriptDocsAddonFile(false)

    fun load() {}

    override fun run() {
        if (Skript.isAcceptRegistrations()) return

        // 1. Initialize Addon Data
        addonMap[Skript::class.java.`package`.name] = AddonData("Skript", AddonMetadata(Skript.getVersion().toString()))
        for (addon in Skript.getAddons()) {
            val pkg = addon.plugin.javaClass.`package`?.name ?: continue
            addonMap[pkg] = AddonData(addon.name, AddonMetadata(addon.version.toString()))
        }

        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Docs Documentation Tool" + ChatColor.RESET + "] " + ChatColor.GREEN + "Detected ${addonMap.size} addons")
        
        val globalProcessedPatterns = mutableSetOf<String>()
        val counters = mutableMapOf(
            "events" to 0, "conditions" to 0, "effects" to 0, "expressions" to 0,
            "sections" to 0, "structures" to 0, "types" to 0, "functions" to 0
        )

        val eventValuesGetter = EventValuesGetter()
        val registry = Skript.instance().syntaxRegistry()

        // ========================================================================================
        // PHASE 1: Legacy API (Priority)
        // We run this FIRST because it provides the correct source classes (e.g. threeadd.packetEventsSK...)
        // ========================================================================================
        sender?.sendMessage("Collecting from Legacy API (Priority)...")

        try {
            for (eventInfo in Skript.getEvents()) {
                val syntaxData = GenerateSyntax.generateSyntaxFromEvent(eventInfo, eventValuesGetter, sender) ?: continue
                val patternKey = syntaxData.patterns?.joinToString("|") ?: continue

                if (patternKey !in globalProcessedPatterns) {
                    val addon = getAddon(eventInfo)
                    if (addon != null) {
                        if (registerSyntax(addon.events, syntaxData, globalProcessedPatterns))
                            counters["events"] = counters["events"]!! + 1
                    }
                }
            }
        } catch (_: Exception) {}

        try { for (info in Skript.getConditions()) processLegacyElement(info, "conditions", globalProcessedPatterns, counters) } catch (_: Exception) {}
        try { for (info in Skript.getEffects()) processLegacyElement(info, "effects", globalProcessedPatterns, counters) } catch (_: Exception) {}
        try { for (info in Skript.getExpressions()) processLegacyElement(info, "expressions", globalProcessedPatterns, counters) } catch (_: Exception) {}
        try { for (info in Skript.getSections()) processLegacyElement(info, "sections", globalProcessedPatterns, counters) } catch (_: Exception) {}
        try { for (info in Skript.getStructures()) processLegacyElement(info, "structures", globalProcessedPatterns, counters) } catch (_: Exception) {}

        // ========================================================================================
        // PHASE 2: SyntaxRegistry (New API)
        // We run this SECOND to catch anything missing from Legacy. 
        // It will NOT overwrite existing entries because of 'globalProcessedPatterns'.
        // ========================================================================================
        sender?.sendMessage("Collecting from SyntaxRegistry API (Secondary)...")

        try { for (s in registry.syntaxes(BukkitRegistryKeys.EVENT)) processSyntaxInfo(s, globalProcessedPatterns, counters, "events", eventValuesGetter) } catch (_: Exception) {}
        try { for (s in registry.syntaxes(SyntaxRegistry.CONDITION)) processSyntaxInfo(s, globalProcessedPatterns, counters, "conditions", null) } catch (_: Exception) {}
        try { for (s in registry.syntaxes(SyntaxRegistry.EFFECT)) processSyntaxInfo(s, globalProcessedPatterns, counters, "effects", null) } catch (_: Exception) {}
        try { for (s in registry.syntaxes(SyntaxRegistry.EXPRESSION)) processSyntaxInfo(s, globalProcessedPatterns, counters, "expressions", null) } catch (_: Exception) {}
        try { for (s in registry.syntaxes(SyntaxRegistry.SECTION)) processSyntaxInfo(s, globalProcessedPatterns, counters, "sections", null) } catch (_: Exception) {}
        try { for (s in registry.syntaxes(SyntaxRegistry.STRUCTURE)) processSyntaxInfo(s, globalProcessedPatterns, counters, "structures", null) } catch (_: Exception) {}

        // ========================================================================================
        // PHASE 3: Types & Functions
        // ========================================================================================
        for (info in Classes.getClassInfos()) {
            val addon = getAddonByClassInfo(info) ?: continue
            val data = GenerateSyntax.generateSyntaxFromClassInfo(info)
            if (data != null) {
                addon.types.add(data)
                counters["types"] = counters["types"]!! + 1
            }
        }

        for (info in Functions.getJavaFunctions()) {
            val addon = getAddonByClass(info.javaClass) ?: continue
            val data = GenerateSyntax.generateSyntaxFromFunctionInfo(info)
            addon.functions.add(data)
            counters["functions"] = counters["functions"]!! + 1
        }

        sender?.sendMessage("Events: ${counters["events"]}, Conditions: ${counters["conditions"]}, Effects: ${counters["effects"]}")
        sender?.sendMessage("Expressions: ${counters["expressions"]}, Sections: ${counters["sections"]}, Structures: ${counters["structures"]}")
        sender?.sendMessage("Types: ${counters["types"]}, Functions: ${counters["functions"]}")

        // ========================================================================================
        // PHASE 4: Merge & Write
        // ========================================================================================
        for (addon in addonMap.keys) {
            val addonInfo = addonMap[addon] ?: continue
            val idSet: MutableSet<String> = mutableSetOf()
            
            attemptIDMerge(addonInfo.events, idCollisionTest(idSet, addonInfo.events))
            attemptIDMerge(addonInfo.conditions, idCollisionTest(idSet, addonInfo.conditions))
            attemptIDMerge(addonInfo.effects, idCollisionTest(idSet, addonInfo.effects))
            attemptIDMerge(addonInfo.expressions, idCollisionTest(idSet, addonInfo.expressions))
            attemptIDMerge(addonInfo.types, idCollisionTest(idSet, addonInfo.types))
            attemptIDMerge(addonInfo.functions, idCollisionTest(idSet, addonInfo.functions))
            attemptIDMerge(addonInfo.sections, idCollisionTest(idSet, addonInfo.sections))
            attemptIDMerge(addonInfo.structures, idCollisionTest(idSet, addonInfo.structures))
        }

        val docsDir = File(instance.dataFolder, "documentation/")
        if (docsDir.exists()) docsDir.listFiles()?.forEach { it.delete() } else docsDir.mkdirs()

        for (addon in addonMap.values) {
            addon.sortLists()
            val file = File(docsDir, "${addon.name}.${fileType.extension}")
            ensureFile(file)
            try { BufferedWriter(OutputStreamWriter(FileOutputStream(file), "UTF-8")).use { fileType.write(it, addon) } } catch (e: Exception) { e.printStackTrace() }

            val skriptDocsFile = File(docsDir, "skriptdocs-${addon.name}.${skriptDocsFileType.extension}")
            ensureFile(skriptDocsFile)
            try { BufferedWriter(OutputStreamWriter(FileOutputStream(skriptDocsFile), "UTF-8")).use { skriptDocsFileType.write(it, addon) } } catch (e: Exception) { e.printStackTrace() }
        }

        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Docs Documentation Tool" + ChatColor.RESET + "] " + ChatColor.GREEN + "Docs have been generated!")
    }

    // --- Processing Helpers ---

    private fun processLegacyElement(info: SyntaxElementInfo<*>, typeKey: String, processed: MutableSet<String>, counters: MutableMap<String, Int>) {
        val syntaxData = GenerateSyntax.generateSyntaxFromSyntaxElementInfo(info, null, sender) ?: return
        if (registerSyntax(getListForType(getAddon(info), typeKey), syntaxData, processed)) {
            counters[typeKey] = counters[typeKey]!! + 1
        }
    }

    private fun processSyntaxInfo(syntaxInfo: SyntaxInfo<*>, processedPatterns: MutableSet<String>, counters: MutableMap<String, Int>, typeKey: String, eventGetter: Any?) {
        val addon = getAddonBySyntaxInfo(syntaxInfo) ?: return
        val syntaxData = GenerateSyntax.generateSyntaxFromSyntaxInfo(syntaxInfo, eventGetter, sender) ?: return
        val patternKey = syntaxData.patterns?.joinToString("|") ?: return

        if (typeKey == "events" && (syntaxData.name?.startsWith("On ") == true || syntaxData.name?.startsWith("on ") == true)) {
            if (patternKey !in processedPatterns) {
                addSyntax(addon.events, syntaxData)
                processedPatterns.add(patternKey)
                counters[typeKey] = counters[typeKey]!! + 1
            }
        } else if (typeKey != "events") {
            if (registerSyntax(getListForType(addon, typeKey), syntaxData, processedPatterns)) {
                counters[typeKey] = counters[typeKey]!! + 1
            }
        }
    }

    private fun getListForType(addon: AddonData?, typeKey: String): MutableList<SyntaxData>? {
        if (addon == null) return null
        return when(typeKey) {
            "conditions" -> addon.conditions
            "effects" -> addon.effects
            "expressions" -> addon.expressions
            "sections" -> addon.sections
            "structures" -> addon.structures
            else -> null
        }
    }

    private fun registerSyntax(list: MutableList<SyntaxData>?, data: SyntaxData, processed: MutableSet<String>): Boolean {
        if (list == null) return false
        val patternKey = data.patterns?.joinToString("|") ?: return false
        if (patternKey !in processed) {
            addSyntax(list, data)
            processed.add(patternKey)
            return true
        }
        return false
    }

    private fun addSyntax(list: MutableList<SyntaxData>, syntax: SyntaxData?) {
        if (syntax != null && !syntax.name.isNullOrEmpty() && !syntax.patterns.isNullOrEmpty()) {
            list.add(syntax)
        }
    }

    private fun ensureFile(file: File) {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            try { file.createNewFile() } catch (ignored: IOException) {}
        }
    }

    private fun idCollisionTest(idSet: MutableSet<String>, listOfSyntaxData: MutableList<SyntaxData>): MutableList<String> {
        val idCollisions = mutableListOf<String>()
        for (syntax in listOfSyntaxData) {
            val id = syntax.id ?: continue
            if (!idSet.add(id)) idCollisions.add(id)
        }
        return idCollisions
    }

    private fun attemptIDMerge(listOfSyntaxData: MutableList<SyntaxData>, ids: MutableList<String>) {
        for (id in ids) attemptTypeMerge(listOfSyntaxData, id)
    }

    private fun attemptTypeMerge(listOfSyntaxData: MutableList<SyntaxData>, id: String) {
        val idCollisionList = ArrayList<SyntaxData>()
        val iterator = listOfSyntaxData.listIterator()

        // Extract all entries with the conflicting ID
        while (iterator.hasNext()) {
            val syntax = iterator.next()
            if (id == syntax.id) {
                idCollisionList.add(syntax)
                iterator.remove()
            }
        }

        if (idCollisionList.isEmpty()) return

        // Take the first one as the master
        val firstInstance = idCollisionList[0]
        val firstDesc = firstInstance.description

        for (i in 1 until idCollisionList.size) {
            val syntaxToMerge = idCollisionList[i]
            val mergeDesc = syntaxToMerge.description

            // Check if descriptions match (Safe comparison)
            val isSameDesc = if (firstDesc == null && mergeDesc == null) true
            else if (firstDesc != null && mergeDesc != null) firstDesc.contentEquals(mergeDesc)
            else false

            if (isSameDesc && syntaxToMerge.name == firstInstance.name) {
                // True Duplicate: Merge patterns
                if (!syntaxToMerge.patterns.isNullOrEmpty()) {
                    firstInstance.patterns = firstInstance.patterns?.plus(syntaxToMerge.patterns!!)
                }
            } else {
                // Different functionality, same ID.
                // WE MUST CHANGE THE ID so it doesn't get overwritten in JSON.
                syntaxToMerge.id = "${syntaxToMerge.id}_$i"
                listOfSyntaxData.add(syntaxToMerge)
            }
        }
        
        // Add the master back
        listOfSyntaxData.add(firstInstance)
    }

    // --- ADDON IDENTIFICATION LOGIC ---

    private fun getAddonByClass(classObj: Class<*>): AddonData? {
        var name = classObj.`package`?.name ?: return null
        val bestAlias = addonPackageMap.keys.filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length }
        if (bestAlias != null) name = name.replaceFirst(bestAlias, addonPackageMap[bestAlias]!!)
        val bestMatch = addonMap.keys.filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length }
        return if (bestMatch != null) addonMap[bestMatch] else null
    }

    private fun getAddonByOrigin(origin: String): AddonData? {
        var name = origin
        val bestAlias = addonPackageMap.keys.filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length }
        if (bestAlias != null) name = name.replaceFirst(bestAlias, addonPackageMap[bestAlias]!!)
        val bestMatch = addonMap.keys.filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length }
        if (bestMatch != null) return addonMap[bestMatch]
        return addonMap.values.firstOrNull { it.name.equals(origin, ignoreCase = true) }
    }

    private fun getAddonBySyntaxInfo(syntaxInfo: SyntaxInfo<*>): AddonData? {
        try {
            val origin = syntaxInfo.origin()
            try {
                val originName = origin.name()
                if (!originName.isNullOrBlank() && originName != "Skript") {
                    getAddonByOrigin(originName)?.let { return it }
                }
            } catch (_: Exception) { }
        } catch (_: Exception) { }

        try {
            var field = findDeepField(syntaxInfo.javaClass, "elementClass")
            var clazz = field?.get(syntaxInfo) as? Class<*>
            if (clazz == null) {
                field = findDeepField(syntaxInfo.javaClass, "c")
                clazz = field?.get(syntaxInfo) as? Class<*>
            }
            if (clazz != null) return getAddonByClass(clazz)
        } catch (_: Exception) { }

        return null
    }

    private fun getAddonByClassInfo(info: ClassInfo<*>): AddonData? {
        return when {
            info.parser != null -> getAddonByClass(info.parser!!::class.java)
            info.serializer != null -> getAddonByClass(info.serializer!!::class.java)
            info.changer != null -> getAddonByClass(info.changer!!::class.java)
            else -> getAddonByClass(info.c)
        }
    }

    private fun getAddon(info: SkriptEventInfo<*>): AddonData? {
        try {
            val possibleFieldNames = listOf("events", "c", "classes", "eventClasses")
            for (fieldName in possibleFieldNames) {
                try {
                    val field = findDeepField(info.javaClass, fieldName) ?: continue
                    val fieldValue = field.get(info) ?: continue
                    var targetEventClass: Class<*>? = null
                    if (fieldValue is Array<*> && fieldValue.isArrayOf<Class<*>>()) targetEventClass = (fieldValue as Array<Class<*>>).firstOrNull()
                    else if (fieldValue is Class<*>) targetEventClass = fieldValue

                    if (targetEventClass != null) {
                        if (targetEventClass.name.startsWith("org.bukkit") || targetEventClass.name.startsWith("io.papermc")) continue
                        val eventClassLoader = targetEventClass.classLoader
                        for (addon in Skript.getAddons()) {
                            if (addon.plugin.javaClass.classLoader == eventClassLoader) {
                                val pkg = addon.plugin.javaClass.`package`?.name
                                if (pkg != null) return addonMap[pkg]
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        
        return getAddonByClass(info.elementClass)
    }
    
    private fun getAddon(info: SyntaxElementInfo<*>): AddonData? {
        return getAddonByClass(info.elementClass)
    }
}