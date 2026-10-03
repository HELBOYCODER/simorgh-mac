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
    "ui/theme/Theme.kt",  # copied then hand-patched via script below
}

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
