#!/usr/bin/env python3
"""Port the Android UI sources to the desktop repo with mechanical rewrites."""
import os, re, shutil, sys

BASE = "/Users/ersaz/Documents/Qoder/2026-10-03/8eaf1ff0"
SRC = os.path.join(BASE, "upstream-zeronet/ZeroNet-Mobile/app/src/main/java/com/zeronet/mobile")
DST = os.path.join(BASE, "simorgh-mac/src/main/kotlin/com/simorgh/mac")

SKIP = {
    "ui/MainActivity.kt",
    "ui/AppController.kt",
    "ui/components/QrScanner.kt",
    "ui/components/Qr.kt",
    "ui/components/Glass.kt",
    "ui/theme/Theme.kt",
    "ui/settings/SettingsSearch.kt",  # rewritten for the generated string tables
}

DESKTOP_LAN = """@Composable
private fun rememberLanAddresses(enabled: Boolean): List<String> {
    val tick = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(enabled) {
        while (enabled) {
            tick.intValue = tick.intValue + 1
            kotlinx.coroutines.delay(5_000)
        }
    }
    val addresses = androidx.compose.runtime.produceState(emptyList<String>(), enabled, tick.intValue) {
        value = if (enabled) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.simorgh.mac.data.LanAddresses.list() } else emptyList()
    }.value
    return addresses
}
"""

def convert(text, rel):
    # package / import rename
    text = text.replace("com.zeronet.mobile", "com.simorgh.mac")
    # R import disappears (we ship a shim R object in com.simorgh.mac)
    text = text.replace("import com.simorgh.mac.R\n", "import com.simorgh.mac.R\n")
    # Context / LocalContext shims
    text = text.replace("import android.content.Context\n", "import com.simorgh.mac.str.Ctx as Context\n")
    text = text.replace("import androidx.compose.ui.platform.LocalContext\n", "import com.simorgh.mac.str.LocalContext\n")
    # lifecycle
    text = text.replace("import androidx.lifecycle.compose.collectAsStateWithLifecycle\n", "import androidx.compose.runtime.collectAsState\n")
    text = text.replace("collectAsStateWithLifecycle(", "collectAsState(")
    # string res imports -> shim
    text = text.replace("import androidx.compose.ui.res.stringResource\n", "import com.simorgh.mac.str.stringResource\n")
    text = text.replace("import androidx.compose.ui.res.pluralStringResource\n", "import com.simorgh.mac.str.pluralStringResource\n")
    text = text.replace("androidx.compose.ui.res.pluralStringResource(", "pluralStringResource(")
    text = text.replace("androidx.compose.ui.res.stringResource(", "stringResource(")
    # LocalConfiguration -> shim locale provider (currentLocale rewritten in Format.kt)
    text = text.replace("import androidx.compose.ui.platform.LocalConfiguration\n", "")
    text = re.sub(
        r"fun currentLocale\(\): Locale \{.*?\n\}",
        "fun currentLocale(): Locale = com.simorgh.mac.str.Lang.locale()",
        text, flags=re.S,
    )
    # lifecycle -> keepOn shim (compat package)
    for imp in (
        "import androidx.lifecycle.Lifecycle\n",
        "import androidx.lifecycle.compose.LocalLifecycleOwner\n",
        "import androidx.lifecycle.repeatOnLifecycle\n",
        "import androidx.lifecycle.LifecycleOwner\n",
        "import androidx.lifecycle.lifecycleScope\n",
    ):
        text = text.replace(imp, "")
    text = text.replace("import com.simorgh.mac.compat.keepOn\n", "")
    text = text.replace("androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle", "Unit")
    text = text.replace("LocalLifecycleOwner.current.lifecycle", "Unit")
    text = re.sub(r"\.repeatOnLifecycle\((androidx\.lifecycle\.)?Lifecycle\.State\.\w+\)", ".keepOn()", text)
    text = text.replace("android.os.SystemClock.uptimeMillis()", "System.currentTimeMillis()")
    # haptics no-ops
    text = text.replace("import android.view.HapticFeedbackConstants\n", "")
    text = text.replace("import androidx.compose.ui.platform.LocalView\n", "")
    text = text.replace("val view = LocalView.current\n", "")
    text = re.sub(r"view\.performHapticFeedback\([^()]*\)", "Unit", text)
    text = re.sub(r"HapticFeedbackConstants\.[A-Z_]+", "0", text)
    text = re.sub(r"Build\.VERSION\.SDK_INT\s*>=\s*\d+", "true", text)
    text = re.sub(r"Build\.VERSION\.SDK_INT\s*<\s*\d+", "false", text)
    text = re.sub(r"Build\.VERSION\.SDK_INT\s*>=\s*Build\.VERSION_CODES\.\w+", "false", text)
    text = text.replace("import android.os.Build\n", "")
    # activity back handlers -> compat shims
    text = text.replace("import androidx.activity.compose.BackHandler\n", "import com.simorgh.mac.compat.BackHandler\n")
    text = text.replace("import androidx.activity.compose.PredictiveBackHandler\n", "import com.simorgh.mac.compat.PredictiveBackHandler\n")
    # android settings provider (animator scale)
    text = re.sub(r"import android\.provider\.Settings as SystemSettings\n", "", text)
    text = re.sub(
        r"runCatching \{ SystemSettings\.Global\.getFloat\([^)]*\)[^}]*\}\n\s*\.getOrDefault\(false\)",
        "false",
        text,
    )
    # compat import injection for keepOn users
    if ".keepOn()" in text:
        text = text.replace("import ", "import com.simorgh.mac.compat.keepOn\nimport ", 1)
    text = text.replace("apkSize", "dmgSize").replace("apkName", "dmgName").replace("apkUrl", "dmgUrl")
    # service-package rewrites (Diagnostics/EngineLog/Engine constants moved)
    text = text.replace("com.simorgh.mac.service.Diagnostics", "com.simorgh.mac.platform.Diagnostics")
    text = text.replace("com.simorgh.mac.service.EngineLog", "com.simorgh.mac.engine.EngineLog")
    text = text.replace("com.simorgh.mac.service.Engine.", "com.simorgh.mac.engine.EngineConst.")
    # string-id helpers that Android typed as Int are String keys here
    text = text.replace("fun paletteName(p: Palette): Int = when", "fun paletteName(p: Palette): String = when")
    text = text.replace("private fun ConnectionProfile.label(): Int = when", "private fun ConnectionProfile.label(): String = when")
    text = text.replace("private fun ConnectionProfile.tag(): Int = when", "private fun ConnectionProfile.tag(): String = when")
    text = text.replace("private fun ConnectionProfile.hint(): Int = when", "private fun ConnectionProfile.hint(): String = when")
    text = text.replace("private fun orbLabel(conn: ConnState): Int = when", "private fun orbLabel(conn: ConnState): String = when")
    text = text.replace("fun headline(problem: Problem): Int = when", "fun headline(problem: Problem): String = when")
    text = text.replace("fun action(problem: Problem, finished: Boolean): Int? = if", "fun action(problem: Problem, finished: Boolean): String? = if")
    # GlobeData: classpath resources instead of Android assets; no telephony on desktop
    if "context.assets.open(" in text:
        text = text.replace("context.assets.open(", "assetStream(")
        text += "\n/** Resources packaged with the app (formerly Android assets). */\nprivate fun assetStream(path: String): java.io.InputStream =\n    object {}.javaClass.classLoader.getResourceAsStream(path)\n        ?: throw java.io.FileNotFoundException(path)\n"
    text = text.replace("import android.telephony.TelephonyManager\n", "")
    text = text.replace("""    val tm = context.getSystemService(TelephonyManager::class.java)
    val candidates = listOf(
        runCatching { tm?.networkCountryIso }.getOrNull(),
        runCatching { tm?.simCountryIso }.getOrNull(),
        Locale.getDefault().country,
        "IR",
    )""", """    val candidates = listOf(
        // No SIM on a Mac: the OS region, then the app's own fallback.
        System.getProperty("user.country"),
        Locale.getDefault().country,
        "IR",
    )""".replace("PLACEHOLDER", ""))
    # LocalResources does not exist on desktop: route through the Ctx shim
    text = text.replace("import androidx.compose.ui.platform.LocalResources\n", "import com.simorgh.mac.str.LocalContext\n")
    text = text.replace("LocalResources.current", "com.simorgh.mac.str.LocalContext.current")
    text = text.replace("androidx.compose.ui.platform.LocalContext.current", "com.simorgh.mac.str.LocalContext.current")
    if rel.startswith("ui/settings/"):
        # search keys are String ids now, not resource ints
        text = text.replace("intArrayOf(", "arrayOf(")
        text = text.replace(": IntArray", ": Array<String>")
        text = text.replace("IntArray)", "Array<String>)")
        text = text.replace("titleRes: Int, keywordRes: Int", "titleRes: String, keywordRes: String")
        text = text.replace("SettingsSearchIndex(context)", "SettingsSearchIndex()")
        text = text.replace("fun show(", "fun show(")
        text = text.replace("ids: Int)", "ids: String)")
        text = text.replace("vararg ids: Int", "vararg ids: String")
        text = text.replace("id: Int", "id: String")
        text = text.replace("import android.content.pm.PackageManager\n", "")
        text = text.replace("import android.content.Intent\n", "")
        text = text.replace("import androidx.core.graphics.drawable.toBitmap\n", "")
        # desktop: no ConnectivityManager listener; LAN addresses poll the interfaces
        text = re.sub(
            r"@Composable\nprivate fun rememberLanAddresses\(enabled: Boolean\): List<String> \{.*?\n\}\n",
            DESKTOP_LAN,
            text, flags=re.S,
        )
        text = text.replace("import android.net.ConnectivityManager\n", "")
        text = text.replace("import android.net.Network\n", "")
        text = text.replace("import android.net.Uri\n", "")
    # app picker: /Applications bundles on desktop
    text = re.sub(
        r"/\*\* Launchable apps .*?\n\}\n",
        "/** Launchable apps: the Mac's /Applications bundles (split tunnelling is Android-only;\n *  the choice persists and is shown, it does not steer the macOS engine). */\nprivate fun loadApps(): List<AppEntry> =\n    (java.io.File\"/Applications\".listFiles { f -> f.extension == \"app\" }?.toList() ?: emptyList())\n        .map { AppEntry(it.name, it.nameWithoutExtension) }\n        .distinctBy { it.pkg }\n        .sortedBy { it.label.lowercase() }\n}\n",
        text, flags=re.S,
    )
    text = text.replace(
        "value = withContext(Dispatchers.IO) { runCatching { loadApps(context.packageManager, context.packageName) }.getOrDefault(emptyList()) }",
        "value = withContext(Dispatchers.IO) { loadApps() }",
    )
    text = re.sub(
        r"    val icon by produceState<ImageBitmap\?>\(null, pkg\) \{\n(?:.*?\n)?    \}",
        "    val icon: androidx.compose.ui.graphics.ImageBitmap? = null",
        text, flags=re.S,
    )
    # log share sheet: clipboard on desktop
    text = text.replace("import android.content.Intent\n", "")
    text = text.replace("""                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "ZeroNet log")
                            .putExtra(Intent.EXTRA_TEXT, lines.takeLast(200_000))
                        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }""",
"""                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(lines.takeLast(200_000)), null)
                        }""")
    # dedupe import lines (LocalContext may arrive twice)
    seen_imports = set()
    kept = []
    for line in text.splitlines(keepends=True):
        if line.startswith("import "):
            if line in seen_imports:
                continue
            seen_imports.add(line)
        kept.append(line)
    text = "".join(kept)
    # ensure the str shims are imported wherever used
    if "stringResource(" in text and "import com.simorgh.mac.str.stringResource" not in text:
        text = text.replace("import ", "import com.simorgh.mac.str.stringResource\nimport ", 1)
    if "pluralStringResource(" in text and "import com.simorgh.mac.str.pluralStringResource" not in text:
        text = text.replace("import ", "import com.simorgh.mac.str.pluralStringResource\nimport ", 1)
    # model defaults for the desktop: proxy mode is the default here
    if rel.startswith("model/"):
        text = text.replace("val mode: ConnectionMode = ConnectionMode.Vpn", "val mode: ConnectionMode = ConnectionMode.Proxy")
        text = text.replace(
            "val logs: Boolean = false,",
            "val logs: Boolean = false,\n    /** Desktop only: route the macOS system proxy through the runtime while connected. */\n    val useSystemProxyDesktop: Boolean = true,",
        )
        text = text.replace(
            '.put("logs", logs)',
            '.put("logs", logs).put("useSystemProxyDesktop", useSystemProxyDesktop)',
        )
        text = text.replace(
            'logs = o.optBoolean("logs", d.logs),',
            'logs = o.optBoolean("logs", d.logs),\n                useSystemProxyDesktop = o.optBoolean("useSystemProxyDesktop", d.useSystemProxyDesktop),',
        )
    return text

def main():
    copied = []
    for root, dirs, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".kt"):
                continue
            rel = os.path.relpath(os.path.join(root, f), SRC)
            top = rel.split(os.sep)[0]
            if top not in ("ui", "model"):
                continue
            if rel in SKIP:
                continue
            src_path = os.path.join(SRC, rel)
            dst_path = os.path.join(DST, rel)
            os.makedirs(os.path.dirname(dst_path), exist_ok=True)
            with open(src_path, encoding="utf-8") as fh:
                text = fh.read()
            text = convert(text, rel)
            with open(dst_path, "w", encoding="utf-8") as fh:
                fh.write(text)
            copied.append(rel)
    print(f"copied {len(copied)} files")

if __name__ == "__main__":
    main()
