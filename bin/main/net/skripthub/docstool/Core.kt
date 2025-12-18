package net.skripthub.docstool
import net.skripthub.docstool.commands.GenerateDocsCommand
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
class Core: JavaPlugin() {
    companion object {
        lateinit var plugin: JavaPlugin
            private set
    }
    override fun onEnable() {
        server.consoleSender.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Docs Documentation Tool"
                + ChatColor.RESET + "] " + ChatColor.RED + "For development servers only! Do not run "
                + "this in production!")
        plugin = this
        getCommand("gendocs")?.setExecutor(GenerateDocsCommand())
        server.consoleSender.sendMessage("[" + ChatColor.DARK_AQUA + "Skript Docs Documentation Tool"
                + ChatColor.RESET + "] " + ChatColor.GREEN + "Started successfully!")
    }
}