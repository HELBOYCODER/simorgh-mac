#!/usr/bin/env python3
"""Generate R.kt + str/Strings.kt from the Android string resources (EN + FA)."""
import xml.etree.ElementTree as ET
import os, re

BASE = "/Users/ersaz/Documents/Qoder/2026-10-03/8eaf1ff0"
RES = os.path.join(BASE, "upstream-zeronet/ZeroNet-Mobile/app/src/main/res")
DST = os.path.join(BASE, "simorgh-mac/src/main/kotlin/com/simorgh/mac")

def unescape(s):
    # Android XML string escapes
    s = s.replace("\\'", "'").replace('\\"', '"')
    s = s.replace("\\n", "\n").replace("\\t", "\t")
    s = re.sub(r"@(\d+)\b", "", s)  # @ is a real Android escape only at start
    s = re.sub(r"\s+", " ", s).strip()
    s = s.replace("&#10;", "\n")
    return s

def parse_strings(path):
    out = {}
    if not os.path.exists(path):
        return out
    root = ET.parse(path).getroot()
    for el in root:
        if el.tag == "string":
            out[el.get("name")] = unescape("".join(el.itertext()))
        elif el.tag == "plurals":
            items = {}
            for it in el.findall("item"):
                items[it.get("quantity")] = unescape("".join(it.itertext()))
            out[el.get("name")] = items
    return out

en = parse_strings(os.path.join(RES, "values/strings.xml"))
fa = parse_strings(os.path.join(RES, "values-fa/strings.xml"))

plain_en = {k: v for k, v in en.items() if isinstance(v, str)}
plain_fa = {k: v for k, v in fa.items() if isinstance(v, str)}
plur_en = {k: v for k, v in en.items() if isinstance(v, dict)}
plur_fa = {k: v for k, v in fa.items() if isinstance(v, dict)}

def kt(v):
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'

string_names = sorted(plain_en.keys())
plural_names = sorted(plur_en.keys())

# R.kt
lines = ["package com.simorgh.mac", "", "// Generated from values/strings.xml by tools/gen_strings.py. Do not edit.", "// String ids are plain keys resolved at runtime by str/Strings.kt.", "object R {", "    object string {"]
for n in string_names:
    lines.append(f"        val {n}: String get() = \"{n}\"")
lines.append("    }")
lines.append("    object plurals {")
for n in plural_names:
    lines.append(f"        val {n}: String get() = \"{n}\"")
lines.append("    }")
lines.append("}")
os.makedirs(DST, exist_ok=True)
open(os.path.join(DST, "R.kt"), "w").write("\n".join(lines) + "\n")

# str/Strings.kt
lines = [
    "package com.simorgh.mac.str",
    "",
    "// Generated from values/strings.xml (EN) and values-fa/strings.xml (FA).",
    "import androidx.compose.runtime.Composable",
    "import androidx.compose.runtime.ReadOnlyComposable",
    "import com.simorgh.mac.model.AppLanguage",
    "import java.util.Locale",
    "",
    "object Strings {",
    f"    val EN: Map<String, String> = mapOf({len(plain_en)} entries)",
    f"    val FA: Map<String, String> = mapOf({len(plain_fa)} entries)",
    f"    val PLURALS_EN: Map<String, Map<String, String>> = mapOf({len(plur_en)} entries)",
    f"    val PLURALS_FA: Map<String, Map<String, String>> = mapOf({len(plur_fa)} entries)",
    "}",
    "",
    "/** The language the UI renders in; set from Settings on each recomposition. */",
    "object Lang {",
    "    @Volatile var language: AppLanguage = AppLanguage.System",
    "",
    "    fun isPersian(): Boolean = when (language) {",
    "        AppLanguage.Persian -> true",
    "        AppLanguage.English -> false",
    "        AppLanguage.System -> Locale.getDefault().language == \"fa\"",
    "        // Languages without a bundled table fall back to English on desktop.",
    "        else -> false",
    "    }",
    "",
    "    fun locale(): Locale = if (isPersian()) Locale(\"fa\") else Locale.ENGLISH",
    "}",
    "",
    "fun str(id: String, vararg args: Any?): String {",
    "    val table = if (Lang.isPersian()) Strings.FA else Strings.EN",
    "    var s = table[id] ?: Strings.EN[id] ?: id",
    "    if (args.isNotEmpty() || s.contains('%')) {",
    "        s = try { String.format(s, *args) } catch (e: Exception) { s }",
    "    }",
    "    return s",
    "}",
    "",
    "fun pluralStr(id: String, count: Int, vararg args: Any?): String {",
    "    fa = Lang.isPersian()".replace("fa =", 'val table ='),
    "    val items = (if (Lang.isPersian()) Strings.PLURALS_FA[id] else Strings.PLURALS_EN[id])",
    "        ?: Strings.PLURALS_EN[id]",
    "    val qty = when {",
    "        items == null -> \"other\"",
    "        !Lang.isPersian() && count == 1 && items.containsKey(\"one\") -> \"one\"",
    "        items.containsKey(\"other\") -> \"other\"",
    "        else -> items.keys.first()",
    "    }",
    "    val s = items?.get(qty) ?: id",
    "    return try { String.format(s, *args) } catch (e: Exception) { s }",
    "}",
    "",
    "@Composable @ReadOnlyComposable",
    "fun stringResource(id: String, vararg args: Any?): String = str(id, *args)",
    "",
    "@Composable @ReadOnlyComposable",
    "fun pluralStringResource(id: String, count: Int, vararg args: Any?): String = pluralStr(id, count, *args)",
    "",
    "/** A tiny stand-in for android.content.Context: only getString() is used by the UI. */",
    "class Ctx {",
    "    fun getString(id: String, vararg args: Any?): String = str(id, *args)",
    "}",
    "",
    "val LocalContext = androidx.compose.runtime.staticCompositionLocalOf { Ctx() }",
    "",
]

def emit_map(name, data):
    lines.extend([f"    val {name}: Map<String, String> = mapOf("])
    for k in sorted(data):
        lines.append(f"        {kt(k)} to {kt(data[k])},")
    lines.append("    )")
    return lines

# rebuild: replace placeholder map declarations with real ones
head = [
    "package com.simorgh.mac.str",
    "",
    "// Generated from values/strings.xml (EN) and values-fa/strings.xml (FA) by tools/gen_strings.py.",
    "import androidx.compose.runtime.Composable",
    "import androidx.compose.runtime.ReadOnlyComposable",
    "import com.simorgh.mac.model.AppLanguage",
    "import java.util.Locale",
    "",
    "object Strings {",
]
emit = []
def emit_plain(var, data):
    emit.append(f"    val {var}: Map<String, String> = mapOf(")
    for k in sorted(data):
        emit.append(f"        {kt(k)} to {kt(data[k])},")
    emit.append("    )")
def emit_plur(var, data):
    emit.append(f"    val {var}: Map<String, Map<String, String>> = mapOf(")
    for k in sorted(data):
        inner = ", ".join(f"{kt(q)} to {kt(v)}" for q, v in sorted(data[k].items()))
        emit.append(f"        {kt(k)} to mapOf({inner}),")
    emit.append("    )")
emit_plain("EN", plain_en)
emit_plain("FA", plain_fa)
emit_plur("PLURALS_EN", plur_en)
emit_plur("PLURALS_FA", plur_fa)
emit.append("}")
emit.append("")

tail = [
    "/** The language the UI renders in; synced from Settings. */",
    "object Lang {",
    "    @Volatile var language: AppLanguage = AppLanguage.System",
    "",
    "    fun isPersian(): Boolean = when (language) {",
    "        AppLanguage.Persian -> true",
    "        AppLanguage.English -> false",
    "        AppLanguage.System -> Locale.getDefault().language == \"fa\"",
    "        else -> false",
    "    }",
    "",
    "    fun locale(): Locale = if (isPersian()) Locale(\"fa\") else Locale.ENGLISH",
    "}",
    "",
    "fun str(id: String, vararg args: Any?): String {",
    "    val table = if (Lang.isPersian()) Strings.FA else Strings.EN",
    "    var s = table[id] ?: Strings.EN[id] ?: id",
    "    if (args.isNotEmpty() || s.contains('%')) {",
    "        s = try { String.format(s, *args) } catch (e: Exception) { s }",
    "    }",
    "    return s",
    "}",
    "",
    "fun pluralStr(id: String, count: Int, vararg args: Any?): String {",
    "    val items = (if (Lang.isPersian()) Strings.PLURALS_FA[id] else Strings.PLURALS_EN[id])",
    "        ?: Strings.PLURALS_EN[id]",
    "    val qty = when {",
    "        items == null -> \"other\"",
    "        !Lang.isPersian() && count == 1 && items.containsKey(\"one\") -> \"one\"",
    "        items.containsKey(\"other\") -> \"other\"",
    "        else -> items.keys.first()",
    "    }",
    "    val s = items?.get(qty) ?: id",
    "    return try { String.format(s, *args) } catch (e: Exception) { s }",
    "}",
    "",
    "@Composable @ReadOnlyComposable",
    "fun stringResource(id: String, vararg args: Any?): String = str(id, *args)",
    "",
    "@Composable @ReadOnlyComposable",
    "fun pluralStringResource(id: String, vararg args: Any?): String = pluralStr(id, (args.firstOrNull() as? Int) ?: 0, *args.drop(1).toTypedArray())",
    "",
    "/** A tiny stand-in for android.content.Context: the copied UI only needs getString(). */",
    "class Ctx {",
    "    fun getString(id: String, vararg args: Any?): String = str(id, *args)",
    "    fun getString(id: String, count: Int, vararg args: Any?): String = pluralStr(id, count, *args)",
    "    /** Desktop stand-in for Android's per-app files dir. */",
    "    val filesDir: java.io.File get() = com.simorgh.mac.Paths.dataDir",
    "}",
    "",
    "val LocalContext = androidx.compose.runtime.staticCompositionLocalOf { Ctx() }",
]

out = head + emit + tail
os.makedirs(os.path.join(DST, "str"), exist_ok=True)
open(os.path.join(DST, "str/Strings.kt"), "w").write("\n".join(out) + "\n")
print(f"EN={len(plain_en)} FA={len(plain_fa)} plurals={len(plur_en)}/{len(plur_fa)}")
