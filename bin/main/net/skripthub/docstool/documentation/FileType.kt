package net.skripthub.docstool.documentation
import net.skripthub.docstool.modals.AddonData
import java.io.BufferedWriter
import java.io.IOException
abstract class FileType(val extension: String) {
    @Throws(IOException::class)
    abstract fun write(writer: BufferedWriter, addon: AddonData)
}