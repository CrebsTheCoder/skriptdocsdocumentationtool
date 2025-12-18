package net.skripthub.docstool.documentation

import org.skriptlang.skript.registration.SyntaxInfo
import ch.njol.skript.classes.Changer.ChangeMode
import ch.njol.skript.classes.ClassInfo
import ch.njol.skript.doc.*
import ch.njol.skript.lang.SkriptEventInfo
import ch.njol.skript.lang.SyntaxElementInfo
import ch.njol.skript.lang.function.JavaFunction
import ch.njol.skript.registrations.Classes
import ch.njol.util.StringUtils
import net.skripthub.docstool.modals.DocumentationEntryNode
import net.skripthub.docstool.modals.SyntaxData
import net.skripthub.docstool.utils.EventValuesGetter
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.event.Cancellable
import org.skriptlang.skript.lang.entry.EntryValidator
import org.skriptlang.skript.lang.entry.EntryValidator.EntryValidatorBuilder
import org.skriptlang.skript.lang.structure.StructureInfo
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*

class GenerateSyntax {
    companion object {

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
        private fun getAllFields(clazz: Class<*>): List<Field> {
            val fields = ArrayList<Field>()
            var currentClass: Class<*>? = clazz
            while (currentClass != null && currentClass != Any::class.java) {
                fields.addAll(currentClass.declaredFields)
                currentClass = currentClass.superclass
            }
            return fields
        }

        private fun getFieldValue(obj: Any, fieldName: String): Any? {
            return findDeepField(obj.javaClass, fieldName)?.get(obj)
        }

        
        private fun unwrapSyntaxElementInfo(syntax: SyntaxInfo<*>): SyntaxElementInfo<*>? {
            try {
                val field = findDeepField(syntax.javaClass, "info")
                if (field != null) {
                    val value = field.get(syntax)
                    if (value is SyntaxElementInfo<*>) return value
                }
            } catch (_: Exception) {}
            return null
        }
        private fun getElementClassFromSyntaxInfo(syntax: SyntaxInfo<*>): Class<*>? {
            try {
                var field = findDeepField(syntax.javaClass, "elementClass")
                var cls = field?.get(syntax) as? Class<*>
                if (isValidDocClass(cls)) return cls

                field = findDeepField(syntax.javaClass, "c")
                cls = field?.get(syntax) as? Class<*>
                if (isValidDocClass(cls)) return cls

                val info = unwrapSyntaxElementInfo(syntax)
                if (info != null) {
                    if (isValidDocClass(info.elementClass)) return info.elementClass
                    val infoResult = scanForAnnotatedClass(info)
                    if (infoResult != null) return infoResult
                }

                val syntaxResult = scanForAnnotatedClass(syntax)
                if (syntaxResult != null) return syntaxResult

                return cls ?: info?.elementClass
            } catch (_: Exception) {}
            return null
        }

        private fun scanForAnnotatedClass(instance: Any): Class<*>? {
            val fields = getAllFields(instance.javaClass)
            for (f in fields) {
                if (Class::class.java.isAssignableFrom(f.type)) {
                    try {
                        f.isAccessible = true
                        val candidate = f.get(instance) as? Class<*>
                        if (isValidDocClass(candidate)) return candidate
                    } catch (_: Exception) {}
                }
            }
            return null
        }

        private fun isValidDocClass(clazz: Class<*>?): Boolean {
            if (clazz == null) return false
            return clazz.isAnnotationPresent(Name::class.java) ||
                    clazz.isAnnotationPresent(Description::class.java) ||
                    clazz.isAnnotationPresent(Examples::class.java)
        }

        fun <A : Annotation, R> grabAnnotation(source: Class<*>, annotation: Class<A>, supplier: (A) -> R?, default: R? = null): R? {
            if (!source.isAnnotationPresent(annotation)) return default
            return supplier(source.getAnnotation(annotation)) ?: default
        }

        fun <A : Annotation, R> grabAnnotationSafely(source: Class<*>, annotation: Class<A>, supplier: (A) -> R?): R? {
            return try {
                if (!source.isAnnotationPresent(annotation)) return null
                val annotationInstance = source.getAnnotation(annotation) ?: return null
                supplier(annotationInstance)
            } catch (_: Exception) { null }
        }

        fun cleanHTML(string: String?): String? {
            if (string.isNullOrBlank()) return string
            return string.replace("""<.+?>(.+?)</.+?>""".toRegex(), "$1")
                .replace("&lt;", "<").replace("&gt;", ">")
        }

        fun cleanHTML(strings: Array<String>?): Array<String>? {
            if (strings.isNullOrEmpty()) return null
            return strings.mapNotNull(::cleanHTML).toTypedArray()
        }

        fun getProperSourcePath(originClassPath: String?, elementClassName: String): String {
            return if (originClassPath != null && originClassPath.contains('.') && !originClassPath.matches(Regex("^[a-z-]+$"))) {
                originClassPath
            } else {
                elementClassName
            }
        }

        fun grabCodeName(classObj: Class<*>): String? {
            val expectedClass: Class<*> = if (classObj.isArray) classObj.componentType else classObj
            val classInfo = Classes.getExactClassInfo(expectedClass) ?: Classes.getSuperClassInfo(expectedClass) ?: return null
            val name = classInfo.name
            return if (classObj.isArray) name.plural else name.singular
        }

        fun cleanTypeUsageValue(value: String): String {
            return value.replace("\\[", "[").replace("\\]", "]")
                .replace("\\(", "(").replace("\\)", ")")
                .replace("?", "").replace("[", "").replace("]", "")
                .replace("(", "").replace(")", "")
                .trim().removePrefix("minecraft:")
                .lowercase(Locale.ROOT).replace("_", " ")
        }


        @Suppress("UNUSED_PARAMETER")
        fun generateSyntaxFromSyntaxInfo(syntax: SyntaxInfo<*>, getter: Any?, sender: Any?): SyntaxData? {
            val data = SyntaxData()
            val patternCollection = syntax.patterns()
            var extractedEntries: Array<DocumentationEntryNode>? = null

            if (syntax is StructureInfo) {
                try {
                    val validator = syntax.entryValidator
                    if (validator != null) {
                        extractedEntries = validator.entryData.map(DocumentationEntryNode::from).toTypedArray()
                    }
                } catch (_: Exception) {}
            }

            data.patterns = if (extractedEntries != null && extractedEntries.isNotEmpty()) {
                generateEnhancedPatterns(patternCollection.toTypedArray(), extractedEntries)
            } else {
                cleanSyntaxInfoPatterns(patternCollection.toTypedArray())
            }
            
            val firstPattern = patternCollection.firstOrNull()
            if (firstPattern != null) {
                data.name = generateCleanName(firstPattern)
            } else {
                data.name = syntax.toString()
            }

            val legacyInfo = unwrapSyntaxElementInfo(syntax)

            
            val syntaxElementClass = getElementClassFromSyntaxInfo(syntax)

            if (legacyInfo != null) {
                val descArr = getFieldValue(legacyInfo, "description") as? Array<String>
                data.description = cleanHTML(descArr)

                val exArr = getFieldValue(legacyInfo, "examples") as? Array<String>
                data.examples = cleanHTML(exArr)

                val sinceStr = getFieldValue(legacyInfo, "since") as? String
                if (!sinceStr.isNullOrBlank()) data.since = arrayOf(cleanHTML(sinceStr)!!)

                if (legacyInfo is SkriptEventInfo<*>) {
                    val reqPlugins = getFieldValue(legacyInfo, "requiredPlugins") as? Array<String>
                    data.requiredPlugins = cleanHTML(reqPlugins)

                    val events = getFieldValue(legacyInfo, "events") as? Array<Class<*>>
                    if (events != null) {
                        data.cancellable = events.filterNotNull().all { Cancellable::class.java.isAssignableFrom(it) }
                    }
                }
            }

            if (syntaxElementClass != null) {

                if (syntaxElementClass.isAnnotationPresent(NoDoc::class.java)) return null

                val annotatedName = grabAnnotation(syntaxElementClass, Name::class.java, { ann -> ann.value.ifBlank { null } }, null)
                if (annotatedName != null) {
                    data.name = annotatedName
                } else if (data.name.isNullOrBlank()) {
                    data.name = generateCleanName(firstPattern ?: syntaxElementClass.simpleName)
                }

                data.id = grabAnnotation(syntaxElementClass, DocumentationId::class.java, { ann -> ann.value.ifBlank { null } }, syntaxElementClass.simpleName)

                if (data.description == null) data.description = cleanHTML(grabAnnotation(syntaxElementClass, Description::class.java, { ann -> ann.value }))
                if (data.examples == null) data.examples = cleanSyntaxInfoExamples(syntaxElementClass)
                if (data.since == null) data.since = cleanHTML(grabAnnotationSafely(syntaxElementClass, Since::class.java) { ann -> ann.value })

                if (data.requiredPlugins == null) data.requiredPlugins = cleanHTML(grabAnnotation(syntaxElementClass, RequiredPlugins::class.java, { ann -> ann.value }))
                data.keywords = grabAnnotation(syntaxElementClass, Keywords::class.java, { ann -> ann.value })

                
                data.source = syntaxElementClass.name
                data.properSource = getProperSourcePath(legacyInfo?.originClassPath, syntaxElementClass.name)

            } else {
                data.id = data.name?.replace(Regex("[^A-Za-z0-9]"), "_")
                
                var rawClass: Class<*>? = null
                try {
                    var field = findDeepField(syntax.javaClass, "c")
                    rawClass = field?.get(syntax) as? Class<*>
                    
                    
                    if (rawClass == null) {
                        field = findDeepField(syntax.javaClass, "elementClass")
                        rawClass = field?.get(syntax) as? Class<*>
                    }
                } catch (_: Exception) {}

                val origin = syntax.origin()

                if (rawClass != null) {
                    data.source = rawClass.name
                    data.properSource = getProperSourcePath(legacyInfo?.originClassPath, rawClass.name)
                } else {
                    data.source = ""
                    data.properSource = origin.name()
                }
            }

            if (data.entries == null && syntax !is StructureInfo && legacyInfo != null) {
                data.entries = generateEntriesFromSyntaxElementInfo(legacyInfo, sender as? CommandSender)
            }

            return data
        }

        fun generateSyntaxFromEvent(info: SkriptEventInfo<*>, getter: EventValuesGetter?, sender: CommandSender?): SyntaxData? {
            try {
                if (info.description != null && info.description.contentEquals(SkriptEventInfo.NO_DOC)) return null

                val data = SyntaxData()
                data.name = info.getName()
                data.id = info.documentationID ?: info.id
                data.description = cleanHTML(info.description)
                data.examples = cleanHTML(info.examples)
                data.since = if (!info.since.isNullOrEmpty()) info.since?.map { cleanHTML(it).toString() }?.toTypedArray() else null
                data.cancellable = info.events.filterNotNull().all { Cancellable::class.java.isAssignableFrom(it) }
                data.patterns = cleanSyntaxInfoPatterns(info.patterns).map { "[on] $it" }.toTypedArray()
                data.requiredPlugins = info.requiredPlugins
                data.keywords = info.keywords
                data.entries = generateEntriesFromSyntaxElementInfo(info, sender)
                data.source = info.getElementClass().name
                data.properSource = getProperSourcePath(info.originClassPath, info.getElementClass().name)

                if (getter != null) {
                    val classes = getter.getEventValues(info.events)
                    if (classes != null && classes.isNotEmpty()) {
                        val time = arrayOf("past event-", "event-", "future event-")
                        val times = ArrayList<String>()
                        for (x in classes.indices) {
                            val clsArr = classes[x]
                            for (y in clsArr.indices) {
                                val code = grabCodeName(clsArr[y])
                                if (code != null) times.add(time[x] + code)
                            }
                        }
                        data.eventValues = times.sortedBy { it }.toTypedArray()
                    }
                }
                return data
            } catch (e: Exception) { return null }
        }

        fun generateSyntaxFromSyntaxElementInfo(info: SyntaxElementInfo<*>, getter: Any?, sender: Any?): SyntaxData? {
            val elementClass: Class<*>
            try {
                elementClass = info.getElementClass()
                if (elementClass.isAnnotationPresent(NoDoc::class.java)) return null
            } catch (e: Exception) { return null }

            val data = SyntaxData()
            val extractedEntries = generateEntriesFromSyntaxElementInfo(info, sender as? CommandSender)
            val patterns = info.patterns

            data.patterns = if (extractedEntries != null && extractedEntries.isNotEmpty()) {
                generateEnhancedPatterns(patterns, extractedEntries)
            } else {
                cleanSyntaxInfoPatterns(patterns)
            }

            val firstPattern = patterns.firstOrNull()
            if (firstPattern != null) {
                data.name = generateCleanName(firstPattern)
            } else {
                data.name = elementClass.simpleName
            }

            val annotatedName = grabAnnotation(elementClass, Name::class.java, { ann -> ann.value.ifBlank { null } }, null)
            if (annotatedName != null) {
                data.name = annotatedName
            } else if (!data.name.isNullOrBlank()) {
                data.name = generateCleanName(data.name!!)
            }

            data.id = grabAnnotation(elementClass, DocumentationId::class.java, { ann -> ann.value.ifBlank { null } }, elementClass.simpleName)

            val descArr = getFieldValue(info, "description") as? Array<String>
            data.description = cleanHTML(grabAnnotation(elementClass, Description::class.java, { ann -> ann.value }))
            if (data.description == null) data.description = cleanHTML(descArr)

            val exArr = getFieldValue(info, "examples") as? Array<String>
            data.examples = cleanSyntaxInfoExamples(elementClass)
            if (data.examples == null) data.examples = cleanHTML(exArr)

            val sinceStr = getFieldValue(info, "since") as? String
            data.since = cleanHTML(grabAnnotationSafely(elementClass, Since::class.java) { ann -> ann.value })
            if (data.since == null && !sinceStr.isNullOrBlank()) data.since = arrayOf(cleanHTML(sinceStr)!!)

            data.requiredPlugins = cleanHTML(grabAnnotation(elementClass, RequiredPlugins::class.java, { ann -> ann.value }))
            data.keywords = grabAnnotation(elementClass, Keywords::class.java, { ann -> ann.value })
            data.entries = extractedEntries
            
            data.source = elementClass.name 
            data.properSource = getProperSourcePath(info.originClassPath, elementClass.name)
            return data
        }

        @Suppress("UNCHECKED_CAST")
        fun generateSyntaxFromClassInfo(info: ClassInfo<*>) : SyntaxData? {
            try {
                if (info.docName != null && info.docName.equals(ClassInfo.NO_DOC)) return null
                val data = SyntaxData()
                data.name = info.docName ?: info.codeName

                data.id = when {
                    info.documentationID != null -> info.documentationID
                    info.c.simpleName.equals("Type") -> "${info.c.simpleName}${data.name?.replace(" ", "")}"
                    else -> info.c.simpleName
                }

                data.description = cleanHTML(info.description as? Array<String>)
                data.examples = cleanHTML(info.examples as? Array<String>)
                data.usage = cleanHTML(info.usage as? Array<String>)
                data.since = if (!info.since.isNullOrBlank()) arrayOf(cleanHTML(info.since)!!) else null

                val supplier = try { info.getSupplier() } catch (_: Exception) { null }
                if (supplier != null) {
                    try {
                        val iterator = supplier.get()
                        val results = mutableListOf<String>()
                        val parser = info.parser

                        while (iterator != null && iterator.hasNext()) {
                            val value = iterator.next()
                            if (value != null) {
                                val stringValue = try {
                                    (parser as? ch.njol.skript.classes.Parser<Any>)?.toString(value, 0)
                                } catch (_: Exception) {
                                    when {
                                        value is Enum<*> -> value.name
                                        value.javaClass.methods.any { it.name == "getName" && it.parameterTypes.isEmpty() } -> {
                                            try { value.javaClass.getMethod("getName").invoke(value) as? String } catch (_: Exception) { null }
                                        }
                                        else -> value.toString()
                                    }
                                }

                                if (!stringValue.isNullOrBlank()) {
                                    val cleaned = cleanTypeUsageValue(stringValue)
                                    if (cleaned.isNotBlank()) results.add(cleaned)
                                }
                            }
                        }
                        if (results.isNotEmpty()) data.typeUsage = results.toTypedArray()
                    } catch (_: Exception) { }
                }

                val changer = info.changer
                if (changer != null)
                    data.changers = ChangeMode.values()
                        .filter { changer.acceptChange(it) != null }
                        .map { it.name.lowercase(Locale.getDefault()).replace('_', ' ') }
                        .sorted().toTypedArray()

                if (!info.userInputPatterns.isNullOrEmpty()) {
                    val size = info.userInputPatterns!!.size
                    data.patterns = Array(size) { "" }
                    for (test in info.userInputPatterns!!.indices) {
                        data.patterns!![test] = info.userInputPatterns!![test].pattern()
                            .replace("\\([- ]\\|[- ]\\)".toRegex(), " ")
                            .replace("\\((.+?)\\)\\?".toRegex(), "[$1]")
                            .replace("(.)\\?".toRegex(), "[$1]")
                            .replace("  ", " ").trim()
                    }
                } else {
                    data.patterns = Array(1) { info.codeName }
                }

                var originPath: String? = null
                try {
                    val candidates = listOfNotNull(info.parser?.javaClass, info.serializer?.javaClass, info.changer?.javaClass)
                    for (c in candidates) {
                        val pkg = c.`package`?.name
                        if (!pkg.isNullOrBlank() && !pkg.startsWith("org.bukkit") && !pkg.startsWith("ch.njol.skript")) {
                            originPath = c.name
                            break
                        }
                        if (originPath == null) originPath = c.name
                    }
                } catch (_: Exception) {}

                if (originPath.isNullOrBlank()) {
                    val field = findDeepField(info.javaClass, "originClassPath")
                    originPath = field?.get(info) as? String
                }

                if (originPath.isNullOrBlank()) originPath = info.c.name

                data.source = originPath
                data.properSource = getProperSourcePath(originPath, info.c.name)
                return data
            } catch (e: Exception) { return null }
        }

        fun generateSyntaxFromFunctionInfo(info: JavaFunction<*>) : SyntaxData {
            val data = SyntaxData()
            data.name = info.name
            data.id = "function_" + info.name
            data.description = cleanHTML(info.description)
            data.examples = cleanHTML(info.examples)
            data.keywords = info.keywords
            val parametersString = StringBuilder("${info.name}(")
            if (!info.parameters.isNullOrEmpty()) {
                parametersString.append(StringUtils.join(info.parameters.map { it.toString() }.toTypedArray(), ", "))
            }
            parametersString.append(")")
            data.patterns = cleanSyntaxInfoPatterns(arrayOf(parametersString.toString()), true)
            if (info.since != null) data.since = arrayOf(cleanHTML(info.since)!!)

            val infoReturnType = info.returnType
            if (infoReturnType != null) {
                data.returnType = if (infoReturnType.docName.isNullOrBlank()) infoReturnType.codeName else infoReturnType.docName
            }
            data.source = info.javaClass.name
            return data
        }

        private fun generateEntriesFromSyntaxElementInfo(info: SyntaxElementInfo<*>, sender: CommandSender?) : Array<DocumentationEntryNode>? {
            if (info is StructureInfo) {
                val validator = info.entryValidator
                if (validator != null) {
                    return validator.entryData.map(DocumentationEntryNode::from).toTypedArray()
                }
            }

            val elementClass = info.getElementClass() ?: return null
            val fields = try { elementClass.declaredFields } catch (_: Exception) { return null }

            for (field in fields) {
                if (field.type.isAssignableFrom(EntryValidator::class.java)) {
                    try {
                        field.isAccessible = true
                        val entryValidator = if (Modifier.isStatic(field.modifiers)) {
                            field.get(null) as? EntryValidator
                        } else {
                            try {
                                val instance = elementClass.getDeclaredConstructor().newInstance()
                                field.get(instance) as? EntryValidator
                            } catch (e: Exception) { null }
                        }
                        if (entryValidator != null) return entryValidator.entryData.map(DocumentationEntryNode::from).toTypedArray()
                    } catch (_: Exception) {}
                } else if (field.type.isAssignableFrom(EntryValidatorBuilder::class.java)) {
                    try {
                        field.isAccessible = true
                        val builder = if (Modifier.isStatic(field.modifiers)) {
                            field.get(null) as? EntryValidatorBuilder
                        } else {
                            try {
                                val instance = elementClass.getDeclaredConstructor().newInstance()
                                field.get(instance) as? EntryValidatorBuilder
                            } catch (e: Exception) { null }
                        }
                        if (builder != null) return builder.build().entryData.map(DocumentationEntryNode::from).toTypedArray()
                    } catch (_: Exception) {}
                }
            }
            return null
        }

        private fun generateEnhancedPatterns(patterns: Array<String>, entries: Array<DocumentationEntryNode>): Array<String> {
            val enhancedPatterns = ArrayList<String>()
            for (pattern in patterns) {
                val cleanedPattern = cleanSyntaxInfoPatterns(arrayOf(pattern))[0]
                val enhancedPattern = StringBuilder(cleanedPattern)
                if (entries.isNotEmpty()) {
                    enhancedPattern.append(":")
                    for (entry in entries) {
                        enhancedPattern.append("\n\t${entry.name}:")
                        when {
                            entry.isSection -> enhancedPattern.append(if (entry.isRequired) " # Required section" else " # Optional section")
                            entry.isRequired -> enhancedPattern.append(" # Required value")
                            else -> enhancedPattern.append(" # Optional value")
                        }
                    }
                }
                enhancedPatterns.add(enhancedPattern.toString())
            }
            return if (enhancedPatterns.isEmpty()) cleanSyntaxInfoPatterns(patterns) else enhancedPatterns.toTypedArray()
        }

        private fun cleanSyntaxInfoExamples(syntaxInfoClass: Class<*>): Array<String>? {
            val combinedExamples = ArrayList<String?>()
            grabAnnotation(syntaxInfoClass, Examples::class.java, { ann -> ann.value })?.toCollection(combinedExamples)
            grabAnnotation(syntaxInfoClass, Example.Examples::class.java, { ann -> ann.value.map { it.value } })?.toCollection(combinedExamples)
            grabAnnotation(syntaxInfoClass, Example::class.java, { ann -> ann.value })?.let { combinedExamples.add(it) }
            return if (combinedExamples.filterNotNull().isEmpty()) null else cleanHTML(combinedExamples.filterNotNull().toTypedArray())
        }

        private fun cleanSyntaxInfoPatterns(patterns: Array<String>, isFunctionPattern: Boolean = false): Array<String> {
            if (patterns.isEmpty()) return patterns
            for (i in patterns.indices) {
                patterns[i] = patterns[i]
                    .replace("""\\([()])""".toRegex(), "$1")
                    .replace("""-?\d+Â¦""".toRegex(), "")
                    .replace("""-?\d+Ã‚Â¦""".toRegex(), "")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .replace("""%-(.+?)%""".toRegex()) { it.value.replace("-", "") }
                    .replace("""%~(.+?)%""".toRegex()) { it.value.replace("~", "") }
                    .replace("()", "")
                    .replace("""@-\d""".toRegex(), "").replace("""@\d""".toRegex(), "")
                    .replace("""\dÂ¦""".toRegex(), "")
                    .replace("""\([- ]\|[- ]\)""".toRegex(), " ")

                if (!isFunctionPattern) {
                    patterns[i] = patterns[i]
                        .replace("""(\w+):""".toRegex(), "")
                        .replace("""\[:""".toRegex(), "[")
                        .replace("""\(:""".toRegex(), "(")
                        .replace("""\|:""".toRegex(), "|")
                }
            }
            return patterns
        }

        private fun generateCleanName(pattern: String): String {
            var cleanName = pattern
                .replace("\\[", "[").replace("\\]", "]")
                .replace("\\(", "(").replace("\\)", ")")
                .replace("%([^%]+)%".toRegex()) { m ->
                    val inner = m.groupValues[1].trim()
                    val candidate = inner.split(Regex("[|/\\\\]")).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: inner
                    candidate.replace(Regex("[^A-Za-z0-9 ]"), " ").trim()
                }
                .replace("""^\d+:""".toRegex(), "")
                .replace("""\d+:\([^)]+\)""".toRegex(), "")
                .replace("""\d+:[^|)]+""".toRegex(), "")
                .replace("""\([^)]+\)""".toRegex()) { match ->
                    match.value.removeSurrounding("(", ")").split(Regex("[|/\\\\]")).firstOrNull { it.isNotBlank() }?.trim() ?: ""
                }
                .replace("""\|""".toRegex(), " ")

            cleanName = cleanName
                .replace("""\[[^\]]*\]""".toRegex(), "")
                .replace(":", "")
                .replace("""(print|with|from|to|of|in|on|at|the|a|an):""".toRegex(), "")
                .replace("""\(""".toRegex(), "").replace("""\)""".toRegex(), "")
                .replace("""[^\w\s]""".toRegex(), " ")
                .replace("""(\s)+""".toRegex(), " ")
                .trim()

            if (cleanName.length < 3) {
                val words = pattern.replace(Regex("""%[^%]*%"""), "").replace(Regex("""\[[^\]]*\]"""), "")
                    .replace(Regex("""[^\w\s]"""), " ").split(Regex("""\s+"""))
                    .filter { it.length > 2 && it.matches(Regex("""[a-zA-Z]+""")) }.take(3)
                if (words.isNotEmpty()) cleanName = words.joinToString(" ")
            }

            if (cleanName.isBlank()) cleanName = "Unknown Syntax"

            if (cleanName.length > 50 || cleanName.contains(Regex("[^a-zA-Z\\s]"))) {
                val words = cleanName.split(Regex("""\s+""")).filter { word ->
                    word.length > 2 && word.matches(Regex("[a-zA-Z]+")) &&
                            !setOf("the", "and", "or", "with", "from", "to", "of", "in", "on", "at", "is", "are", "as", "be").contains(word.lowercase())
                }.take(4)
                if (words.isNotEmpty()) cleanName = words.joinToString(" ")
            }

            return cleanName.split(" ").joinToString(" ") {
                if (it.isNotBlank()) it.replaceFirstChar { c -> c.titlecase() } else it
            }
                .replace("worldguard", "WorldGuard", ignoreCase = true)
                .replace("javaobject", "Java Object", ignoreCase = true)
                .replace("javatype", "Java Type", ignoreCase = true)
                .ifBlank { "Unknown Syntax" }
        }
    }
}