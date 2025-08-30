
package net.skripthub.docstool.documentation
import ch.njol.skript.Skript
import ch.njol.skript.classes.ClassInfo
import ch.njol.skript.lang.SkriptEventInfo
import ch.njol.skript.lang.SyntaxElementInfo
import ch.njol.skript.lang.function.Functions
import ch.njol.skript.log.SkriptLogger
import ch.njol.skript.registrations.Classes
import net.skripthub.docstool.modals.AddonData
import net.skripthub.docstool.modals.AddonMetadata
import net.skripthub.docstool.modals.SyntaxData
import net.skripthub.docstool.utils.EventValuesGetter
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.io.*


class BuildDocs(private val instance: JavaPlugin, private val sender: CommandSender?) : Runnable{

    private var addonMap: HashMap<String, AddonData> = hashMapOf()

    
    private var addonPackageMap: HashMap<String, String> = hashMapOf(
        "org.skriptlang.skript" to "ch.njol.skript",
        "Skript" to "ch.njol.skript"
    )

    // Toggle deep debug dumps for unresolved SimpleEvent cases
    private val VERBOSE_DUMP: Boolean = true

    private val fileType: FileType = JsonFile(false)
    private val skriptDocsFileType: FileType = SkriptDocsAddonFile(false)

    fun load() {
        Bukkit.getScheduler().runTaskLaterAsynchronously(instance, this, 10L)
    }

    override fun run() {
        if (Skript.isAcceptRegistrations()) {
            // Skript is still registering addons/events — reschedule shortly to avoid missing entries
            println("[DEBUG] BuildDocs: Skript still accepting registrations, scheduling retry in 100 ticks")
            sender?.sendMessage("[Skript Hub Docs Tool] Skript still registering events; will retry shortly.")
            Bukkit.getScheduler().runTaskLaterAsynchronously(instance, this, 100L)
            return
        }
        addonMap[Skript::class.java.`package`.name] = AddonData(
                "Skript", AddonMetadata(Skript.getVersion().toString()))
        // Also add canonical alias entries for known Skript package variations so core elements are always found
        for (canonical in addonPackageMap.values) {
            if (!addonMap.containsKey(canonical)) {
                addonMap[canonical] = addonMap[Skript::class.java.`package`.name]!!
            }
        }
        for (addon in Skript.getAddons())
            addonMap[addon.plugin.javaClass.`package`.name] = AddonData(
                    addon.name, AddonMetadata(addon.version.toString()))

        // Events
        val getter = EventValuesGetter()
        for (eventInfoClassUnsafe in Skript.getEvents()){
            val eventInfoClass = eventInfoClassUnsafe as SkriptEventInfo<*>
            val addonEvents = getAddon(eventInfoClass)?.events ?: continue
            // TODO Throw error when null
            addSyntax(addonEvents, GenerateSyntax.generateSyntaxFromEvent(eventInfoClass, getter, sender))
        }

        // Conditions
        for (syntaxElementInfo in Skript.getConditions()) {
            val addonConditions = getAddon(syntaxElementInfo)?.conditions ?: continue
            addSyntax(addonConditions, GenerateSyntax.generateSyntaxFromSyntaxElementInfo(syntaxElementInfo, sender))
        }

        // Effects
        for (syntaxElementInfo in Skript.getEffects()) {
            val addonEffects = getAddon(syntaxElementInfo)?.effects ?: continue
            addSyntax(addonEffects, GenerateSyntax.generateSyntaxFromSyntaxElementInfo(syntaxElementInfo, sender))
        }

        // Expressions
        // A LogHandler for expressions since it catch the changers, which can throw errors in console
        // such as "Expression X can only be used in event Y"
        val log = SkriptLogger.startParseLogHandler()
        for (info in Skript.getExpressions()) {
            val addonExpressions = getAddon(info)?.expressions ?: continue
            addSyntax(addonExpressions, GenerateSyntax.generateSyntaxFromExpression(info, sender))
        }
        log.clear()
        log.stop()

        // Types
        for (syntaxElementInfo in Classes.getClassInfos()) {
            val addonTypes = getAddon(syntaxElementInfo)?.types ?: continue
            addSyntax(addonTypes, GenerateSyntax.generateSyntaxFromClassInfo(syntaxElementInfo))
        }

        // Functions
        for (info in Functions.getJavaFunctions()) {
            val addonFunctions = getAddon(info.javaClass)?.functions ?: continue
            addSyntax(addonFunctions, GenerateSyntax.generateSyntaxFromFunctionInfo(info))
        }

        // Sections
        for (syntaxElementInfo in Skript.getSections()) {
            val addonSections = getAddon(syntaxElementInfo)?.sections ?: continue
            addSyntax(addonSections, GenerateSyntax.generateSyntaxFromSyntaxElementInfo(syntaxElementInfo, sender))
        }

        // Structures
        for (syntaxElementInfo in Skript.getStructures()) {
            val addonStructures = getAddon(syntaxElementInfo)?.structures ?: continue
            addSyntax(addonStructures, GenerateSyntax.generateSyntaxFromSyntaxElementInfo(syntaxElementInfo, sender))
        }

        // Error Check Each Addon! (No id collisions)
        for (addon in addonMap.keys){
            val addonInfo = addonMap[addon] ?: continue
            val idSet : MutableSet<String> = mutableSetOf()
            // Get results from test and attempt merge
            attemptIDMerge(addonInfo.events, idCollisionTest(idSet, addonInfo.events, addon))
            attemptIDMerge(addonInfo.conditions, idCollisionTest(idSet, addonInfo.conditions, addon))
            attemptIDMerge(addonInfo.effects, idCollisionTest(idSet, addonInfo.effects, addon))
            attemptIDMerge(addonInfo.expressions, idCollisionTest(idSet, addonInfo.expressions, addon))
            attemptIDMerge(addonInfo.types, idCollisionTest(idSet, addonInfo.types, addon))
            attemptIDMerge(addonInfo.functions, idCollisionTest(idSet, addonInfo.functions, addon))
            attemptIDMerge(addonInfo.sections, idCollisionTest(idSet, addonInfo.sections, addon))
            attemptIDMerge(addonInfo.structures, idCollisionTest(idSet, addonInfo.structures, addon))
        }

        // Write to JSON
        // Before, lets delete old files...
        val docsDir = File(instance.dataFolder, "documentation/")
        if (docsDir.exists()) {
            val files = docsDir.listFiles()
            if (files != null)
                for (f in files)
                    f.delete()
        } else
            docsDir.mkdirs()
        // Done, now let's write them all into files
        for (addon in addonMap.values) {
            addon.sortLists()

            // Write original JSON format (SkriptHub standard)
            val file = File(docsDir, "${addon.name}.${fileType.extension}")
            if (!file.exists()) {
                file.parentFile.mkdirs()
                try {
                    file.createNewFile()
                } catch (ignored: IOException) {}
            }
            try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file), "UTF-8")).use { writer -> fileType.write(writer, addon) }
            } catch (io: IOException) {
                io.printStackTrace()
            }

            // Write skriptdocs format
            val skriptDocsFile = File(docsDir, "skriptdocs-${addon.name}.${skriptDocsFileType.extension}")
            if (!skriptDocsFile.exists()) {
                skriptDocsFile.parentFile.mkdirs()
                try {
                    skriptDocsFile.createNewFile()
                } catch (ignored: IOException) {}
            }
            try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(skriptDocsFile), "UTF-8")).use { writer -> skriptDocsFileType.write(writer, addon) }
            } catch (io: IOException) {
                io.printStackTrace()
            }
        }

        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                + ChatColor.RESET + "] " + ChatColor.GREEN + "Docs have been generated!")
    }

    private fun idCollisionTest(idSet: MutableSet<String>, listOfSyntaxData: MutableList<SyntaxData>, addon: String): MutableList<String>{
        val idCollisions = mutableListOf<String>()
        for (syntax in listOfSyntaxData){
            val id = syntax.id ?: continue
            val result = idSet.add(id)
            if (!result){
                // PANIC!!! ID COLLISION!!!!
                idCollisionErrorMessage(addon, id)
                idCollisions.add(id)
            }
        }
        return idCollisions
    }

    private fun attemptIDMerge(listOfSyntaxData: MutableList<SyntaxData>, ids: MutableList<String>) {
        // Only merge from like Syntax Types
        for(id in ids){
            attemptTypeMerge(listOfSyntaxData, id)
        }
    }

    private fun attemptTypeMerge(listOfSyntaxData: MutableList<SyntaxData>, id: String) {
        // Message attempting merge
        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                + ChatColor.RESET + "] " + ChatColor.GREEN + "Attempting merge of $id")
        val idCollisionList: ArrayList<SyntaxData> = ArrayList()
        val iterator = listOfSyntaxData.listIterator()
        while (iterator.hasNext()) {
            val syntax = iterator.next()
            val syntaxId = syntax.id ?: continue
            if(id == syntaxId){
                idCollisionList.add(syntax)
                iterator.remove()
            }
        }
        // No collision, might be a different syntax type
        if(idCollisionList.size < 2){
            listOfSyntaxData.addAll(idCollisionList)
            sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                    + ChatColor.RESET + "] " + ChatColor.GOLD + "Merge of $id unsuccessful, conflict might be "
                    + "between two different syntax types or it might have already been resolved")
            return
        }
        // Use first instance as a template and try to merge into first instance
        val firstInstance = idCollisionList[0]
        var repairedCount = 0
        for (i in 1 until idCollisionList.size){
            val syntaxToMerge = idCollisionList[i]
            val syntaxToMergeDesc = firstInstance.description
            if (syntaxToMergeDesc != null && syntaxToMerge.description != null && syntaxToMerge.patterns != null) {
                if(syntaxToMerge.name == firstInstance.name
                        && syntaxToMerge.description!!.contentEquals(syntaxToMergeDesc.clone())){
                    // Match found, add to firstInstance
                    firstInstance.patterns = firstInstance.patterns?.plus(syntaxToMerge.patterns!!)
                    repairedCount += 1
                    continue
                }
            }
            // Failed add back to master list
            listOfSyntaxData.add(syntaxToMerge)
        }
        // Add first instance back to list
        listOfSyntaxData.add(firstInstance)

        if(repairedCount > 0){
            sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                    + ChatColor.RESET + "] " + ChatColor.GREEN + "Merged ${repairedCount + 1} out of ${idCollisionList.size} "
                    + "instances of $id")
            return
        }
        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                + ChatColor.RESET + "] " + ChatColor.RED + "Unable to merge ${idCollisionList.size} "
                + "instances of $id")
    }

    private fun idCollisionErrorMessage(addon: String, id: String){
        sender?.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Hub Docs Tool"
                + ChatColor.RESET + "] " + ChatColor.RED + "ID COLLISION DETECTED!\n" +
                "Plugin: $addon\nMultiple syntax elements with the same id: $id")
    }

    private fun addSyntax(list: MutableList<SyntaxData>, syntax: SyntaxData?) {
        if (syntax == null) return
        if (syntax.name.isNullOrEmpty()) return
        if (syntax.patterns.isNullOrEmpty()) return
        val type = when (list) {
            addonMap.values.flatMap { it.events } -> "event"
            addonMap.values.flatMap { it.conditions } -> "condition"
            addonMap.values.flatMap { it.effects } -> "effect"
            addonMap.values.flatMap { it.expressions } -> "expression"
            addonMap.values.flatMap { it.types } -> "type"
            addonMap.values.flatMap { it.functions } -> "function"
            addonMap.values.flatMap { it.sections } -> "section"
            addonMap.values.flatMap { it.structures } -> "structure"
            else -> "unknown"
        }
        val addonName = addonMap.entries.find { it.value.events === list || it.value.conditions === list || it.value.effects === list || it.value.expressions === list || it.value.types === list || it.value.functions === list || it.value.sections === list || it.value.structures === list }?.key ?: "unknown"
        println("[DEBUG] addSyntax: Added $type to $addonName: ${syntax.name} (${syntax.patterns?.joinToString()})")
        list.add(syntax)
    }

    private fun getAddon(info: ClassInfo<*>): AddonData? {
        return when {
            info.parser != null -> getAddon(info.parser!!::class.java)
            info.serializer != null -> getAddon(info.serializer!!::class.java)
            info.changer != null -> getAddon(info.changer!!::class.java)
            else -> getAddon(info.javaClass)
        }
    }

    private fun getAddon(skriptEventInfo: SyntaxElementInfo<*>): AddonData? {

        var name = skriptEventInfo.getElementClass().`package`.name
        val originClassPath = skriptEventInfo.originClassPath

        // DEBUG: initial
        println("[DEBUG] getAddon: Event class package: $name, originClassPath: $originClassPath")

        // If registered via SimpleEvent (utility), try to discover the real event's package
        if (name == "ch.njol.skript.lang.util") {
            // First, if this SyntaxElementInfo is actually a SkriptEventInfo, prefer its `events` array
            try {
                if (skriptEventInfo is SkriptEventInfo<*>) {
                    val evs = skriptEventInfo.events
                    println("[DEBUG] getAddon: SkriptEventInfo.events = ${evs?.map { it?.name }}")
                    if (evs != null) {
                        for (e in evs) {
                            try {
                                if (e != null
                                    && e != skriptEventInfo.getElementClass()
                                    && e != ch.njol.skript.lang.util.SimpleEvent::class.java
                                    && org.bukkit.event.Event::class.java.isAssignableFrom(e)) {
                                    name = e.`package`.name
                                    println("[DEBUG] getAddon: used SkriptEventInfo.events -> $name (class=${e.name})")
                                    break
                                }
                            } catch (_: Exception) {
                                // ignore per-event failures
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore casting/access errors and fall back to other heuristics
            }

            // If we've already found a non-default package via events, skip the heavier reflection work
            if (name != "ch.njol.skript.lang.util") {
                // continue normal flow below (mapping, lookup)
            } else {
                // 1) Prefer originClassPath when provided — but skip it if it points to Skript's SimpleEvent
                if (!originClassPath.isNullOrEmpty() && originClassPath.contains('.')) {
                    val ocp = originClassPath
                    val pointsToSimpleEvent = ocp.contains("SimpleEvent") || ocp.endsWith(".SimpleEvent") || ocp.endsWith("SimpleEvent")
                    if (pointsToSimpleEvent) {
                        println("[DEBUG] getAddon: originClassPath points to Skript SimpleEvent, skipping as insufficient (originClassPath=$originClassPath)")
                    } else {
                        name = originClassPath.substringBeforeLast('.')
                        println("[DEBUG] getAddon: used originClassPath -> $name (originClassPath=$originClassPath)")
                        // used originClassPath, skip heavier reflection
                        // continue normal flow below (mapping, lookup)
                    }
                } else {
                    // 2) Fallback A: reflectively search for a Class field whose value is an Event subclass
                    var discovered: Class<*>? = null
                    var cls: Class<*>? = skriptEventInfo.javaClass
                    while (cls != null) {
                        for (field in cls.declaredFields) {
                            try {
                                field.isAccessible = true
                                val valObj = field.get(skriptEventInfo)
                                when (valObj) {
                                    is Class<*> -> {
                                        val candidate = valObj
                                        if (candidate != skriptEventInfo.getElementClass()
                                                && candidate != ch.njol.skript.lang.util.SimpleEvent::class.java
                                                && org.bukkit.event.Event::class.java.isAssignableFrom(candidate)) {
                                            discovered = candidate
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // ignore
                            }
                        }
                        if (discovered != null) break
                        cls = cls.superclass
                    }

                    // 2) Fallback B: try methods that return a Class
                    if (discovered == null) {
                        cls = skriptEventInfo.javaClass
                        while (cls != null) {
                            for (m in cls.declaredMethods) {
                                try {
                                    if (m.parameterCount == 0 && m.returnType == Class::class.java) {
                                        m.isAccessible = true
                                        val ret = m.invoke(skriptEventInfo) as? Class<*>
                                        if (ret != null
                                                && ret != skriptEventInfo.getElementClass()
                                                && ret != ch.njol.skript.lang.util.SimpleEvent::class.java
                                                && org.bukkit.event.Event::class.java.isAssignableFrom(ret)) {
                                            discovered = ret
                                            break
                                        }
                                    }
                                } catch (_: Exception) {
                                    // ignore
                                }
                            }
                            if (discovered != null) break
                            cls = cls.superclass
                        }
                    }

                    // 3) Fallback C: search String fields that contain a class name and match known addon packages
                    if (discovered == null) {
                        cls = skriptEventInfo.javaClass
                        searchLoop@ while (cls != null) {
                            for (field in cls.declaredFields) {
                                try {
                                    field.isAccessible = true
                                    val valObj = field.get(skriptEventInfo)
                                    if (valObj is String && valObj.contains('.')) {
                                        val candidateStr = valObj
                                        val match = addonMap.keys.firstOrNull { candidateStr.startsWith(it) }
                                        if (match != null) {
                                            name = candidateStr.substringBeforeLast('.')
                                            println("[DEBUG] getAddon: discovered origin string field -> $name (value=$candidateStr)")
                                            break@searchLoop
                                        }
                                    }
                                } catch (_: Exception) {
                                    // ignore
                                }
                            }
                            cls = cls.superclass
                        }
                    }

                    if (discovered != null) {
                        name = discovered.`package`.name
                        println("[DEBUG] getAddon: discovered event class via reflection -> $name (class=${discovered.name})")
                    } else {
                        println("[DEBUG] getAddon: could not discover real event class via originClassPath or reflection; leaving as $name")
                                        if (VERBOSE_DUMP) {
                                            try {
                                                // verboseDumpToFile is a private class-level helper (defined below)
                                                verboseDumpToFile(skriptEventInfo)
                                            } catch (ex: Exception) {
                                                println("[DEBUG] getAddon: verboseDump failed: ${ex.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        }

        // Check to see if we need to remap the package to the addon root package.
        val mappedPackageNode = addonPackageMap.entries.firstOrNull { name.startsWith(it.key) }
        if (mappedPackageNode != null) {
            name = mappedPackageNode.value
        }

        // Try to find the addon
        val found = addonMap.entries.firstOrNull { name.startsWith(it.key) }?.value
        if (found != null) {
            return found
        }

        // If we can't find the addon and this is from ch.njol.skript, it might be Skript core
        if (name.startsWith("ch.njol.skript")) {
            val skriptAddon = addonMap.entries.firstOrNull { it.key == "ch.njol.skript" }?.value
            return skriptAddon
        }

        // Last resort - return null to skip this syntax element
        return null
    }

    private fun getAddon(classObj: Class<*>): AddonData? {
        var name = classObj.`package`.name
        // If null, bail and throw error

        // Check to see if we need to remap the package to the addon root package.
        val mappedPackageNode = addonPackageMap.entries.firstOrNull { name.startsWith(it.key) }
        if (mappedPackageNode != null) {
            name = mappedPackageNode.value
        }

        return addonMap.entries
                .firstOrNull { name.startsWith(it.key) }
                ?.value
    }

    // Writes a verbose runtime dump of an object (used when heuristics fail to discover the real event class)
    private fun verboseDumpToFile(info: Any?) {
        if (info == null) return
        val cls = info.javaClass
        val sb = StringBuilder()
        sb.append("Dumping SyntaxElementInfo instance of ${cls.name}\n")
        var c: Class<*>? = cls
        while (c != null) {
            sb.append("Class: ${c.name}\n")
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = try { f.get(info) } catch (e: Exception) { "<unreadable: ${e.javaClass.simpleName}>" }
                    val sval = when (v) {
                        null -> "null"
                        is Class<*> -> "Class(${v.name})"
                        is String -> "String(${v})"
                        else -> v.toString()
                    }
                    sb.append("Field: ${f.name} : ${f.type.name} = ${sval}\n")
                } catch (e: Throwable) {
                    sb.append("Field: ${f.name} unreadable: ${e.message}\n")
                }
            }
            for (m in c.declaredMethods) {
                try {
                    sb.append("Method: ${m.name} -> ${m.returnType.name} (params=${m.parameterCount})\n")
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }

        // write to file under plugin data folder
        try {
            val docsDir = File(instance.dataFolder, "documentation/")
            if (!docsDir.exists()) docsDir.mkdirs()
            val out = File(docsDir, "verboseDump-${'$'}{System.currentTimeMillis()}.log")
            BufferedWriter(OutputStreamWriter(FileOutputStream(out), "UTF-8")).use { w -> w.write(sb.toString()) }
            println("[DEBUG] getAddon: verbose dump written to ${out.absolutePath}")
            // Also notify the sender if available so it's easy to spot when running in-game
            sender?.sendMessage("[Skript Hub Docs Tool] Verbose dump written: ${out.absolutePath}")
        } catch (ex: Exception) {
            println("[DEBUG] getAddon: failed to write verbose dump: ${ex.message}")
        }
    }
}
