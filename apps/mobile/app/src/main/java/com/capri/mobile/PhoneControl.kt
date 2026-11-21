package com.capri.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Direct & AI-assisted Android phone control using [CapriAccess] (gestures, clicks, typing,
 * global navigation) and [PackageManager] app launching.
 */
object PhoneControl {
    sealed class DirectCmd {
        data object Back : DirectCmd()
        data object Home : DirectCmd()
        data object Recents : DirectCmd()
        data object Notifications : DirectCmd()
        data object ScrollDown : DirectCmd()
        data object ScrollUp : DirectCmd()
        data class OpenApp(val query: String) : DirectCmd()
        data class Click(val target: String) : DirectCmd()
        data class Type(val value: String) : DirectCmd()
    }

    data class ActionTag(val name: String, val arg: String)

    private val ACTION_RX = Regex("""\[ACTION:\s*([a-z_]+)(?:=([^\]]+))?\]""", RegexOption.IGNORE_CASE)

    fun parseDirectCommand(raw: String): DirectCmd? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val low = t.lowercase()

        when {
            low in setOf("back", "kembali", "mundur", "go back") -> return DirectCmd.Back
            low in setOf("home", "beranda", "ke home", "ke beranda", "go home") -> return DirectCmd.Home
            low in setOf("recents", "recent", "recent apps", "aplikasi terbaru") -> return DirectCmd.Recents
            low in setOf("notifications", "notifikasi", "buka notifikasi", "open notifications") -> return DirectCmd.Notifications
            low in setOf("scroll down", "scroll bawah", "scroll ke bawah", "gulir ke bawah", "geser ke bawah") -> return DirectCmd.ScrollDown
            low in setOf("scroll up", "scroll atas", "scroll ke atas", "gulir ke atas", "geser ke atas") -> return DirectCmd.ScrollUp
        }

        Regex("""^(?:buka|bukain|open|launch|jalankan)\s+(?:aplikasi\s+|app\s+)?(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(t)?.let { m ->
                val target = m.groupValues[1].trim()
                if (target.isNotEmpty() && !target.contains("notifikasi", ignoreCase = true)) {
                    return DirectCmd.OpenApp(target)
                }
            }

        Regex("""^(?:klik|tap|tekan|pencet)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(t)?.let { m ->
                val target = m.groupValues[1].trim()
                if (target.isNotEmpty()) return DirectCmd.Click(target)
            }

        Regex("""^(?:ketik|ketikkan|tulis|type|isi)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(t)?.let { m ->
                val value = m.groupValues[1].trim()
                if (value.isNotEmpty()) return DirectCmd.Type(value)
            }

        return null
    }

    fun handleDirect(ctx: Context, text: String): String? {
        val cmd = parseDirectCommand(text) ?: return null
        val access = CapriAccess.instance
        return when (cmd) {
            is DirectCmd.OpenApp -> {
                if (openApp(ctx, cmd.query)) "Oke, membuka ${cmd.query}."
                else "Aplikasi '${cmd.query}' tidak ditemukan di HP ini."
            }
            is DirectCmd.Back -> {
                if (access == null) needAccessibility()
                else if (access.back()) "Oke, kembali."
                else "Gagal menekan tombol kembali."
            }
            is DirectCmd.Home -> {
                if (access == null) needAccessibility()
                else if (access.home()) "Oke, ke layar utama."
                else "Gagal kembali ke beranda."
            }
            is DirectCmd.Recents -> {
                if (access == null) needAccessibility()
                else if (access.recents()) "Oke, membuka aplikasi terbaru."
                else "Gagal membuka daftar aplikasi terbaru."
            }
            is DirectCmd.Notifications -> {
                if (access == null) needAccessibility()
                else if (access.notifications()) "Oke, membuka panel notifikasi."
                else "Gagal membuka notifikasi."
            }
            is DirectCmd.ScrollDown -> {
                if (access == null) needAccessibility()
                else {
                    access.scrollDown()
                    "Oke, scroll ke bawah."
                }
            }
            is DirectCmd.ScrollUp -> {
                if (access == null) needAccessibility()
                else {
                    access.scrollUp()
                    "Oke, scroll ke atas."
                }
            }
            is DirectCmd.Click -> {
                if (access == null) needAccessibility()
                else if (access.clickText(cmd.target)) "Oke, menekan '${cmd.target}'."
                else "Tidak menemukan tombol atau teks '${cmd.target}' di layar."
            }
            is DirectCmd.Type -> {
                if (access == null) needAccessibility()
                else if (access.typeText(cmd.value)) "Oke, sudah diketik."
                else "Tidak ada kolom teks yang aktif di layar."
            }
        }
    }

    private fun needAccessibility(): String =
        "Aktifkan izin Accessibility Capri dulu di Pengaturan agar aku bisa mengontrol HP-mu."

    fun openApp(ctx: Context, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val pm = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        val exact = apps.firstOrNull {
            it.loadLabel(pm).toString().trim().equals(q, ignoreCase = true)
        }
        val partial = exact ?: apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            val pkg = it.activityInfo.packageName.lowercase()
            label.contains(q) || pkg.contains(q)
        } ?: return false
        val launch = pm.getLaunchIntentForPackage(partial.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(launch); true }.getOrDefault(false)
    }

    fun looksLikePhoneTask(text: String): Boolean {
        val rx = Regex(
            """\b(bantu|tolong|bukain|carikan|kirim|pencet|klik|ketik|scroll|gulir|geser|hapus|nyalakan|matikan|tutup|cari|balas|baca|cek|lihat|hp|ponsel|layar|aplikasi|wa|whatsapp|youtube|tiktok|instagram|ig|spotify|chrome|setting|pengaturan|open|click|tap|type|press|help|control)\b""",
            RegexOption.IGNORE_CASE,
        )
        return rx.containsMatchIn(text)
    }

    fun buildPhoneContextPrompt(userText: String, access: CapriAccess): String {
        val screen = access.dumpVisibleScreen(35)
        val fg = CapriAccess.foreground.ifBlank { "unknown" }
        return buildString {
            append(userText)
            append("\n\n[PHONE CONTROL CONTEXT]")
            append("\nForeground app: ").append(fg)
            if (screen.isNotBlank()) {
                append("\nVisible UI elements:\n").append(screen)
            }
            append(
                "\nIf the user wants you to act on the phone, include one or more action tags at the end of your reply: " +
                    "[ACTION: open_app=AppName], [ACTION: click=ButtonText], [ACTION: type=Text], " +
                    "[ACTION: scroll_down], [ACTION: scroll_up], [ACTION: back], [ACTION: home], [ACTION: notifications]. " +
                    "Keep your spoken reply short and natural."
            )
        }
    }

    fun extractActions(reply: String): List<ActionTag> =
        ACTION_RX.findAll(reply).map { m ->
            ActionTag(m.groupValues[1].trim().lowercase(), m.groupValues[2].trim())
        }.toList()

    fun executeAndStripActions(ctx: Context, reply: String): String {
        val actions = extractActions(reply)
        val access = CapriAccess.instance
        for (a in actions) {
            when (a.name) {
                "open_app" -> if (a.arg.isNotBlank()) openApp(ctx, a.arg)
                "click" -> if (a.arg.isNotBlank()) access?.clickText(a.arg)
                "type" -> if (a.arg.isNotBlank()) access?.typeText(a.arg)
                "scroll_down" -> access?.scrollDown()
                "scroll_up" -> access?.scrollUp()
                "back" -> access?.back()
                "home" -> access?.home()
                "recents" -> access?.recents()
                "notifications" -> access?.notifications()
            }
        }
        return ACTION_RX.replace(reply, "").replace(Regex("""\s{2,}"""), " ").trim()
    }
}
