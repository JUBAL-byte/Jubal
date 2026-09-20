"""Fit the generated Android project out for the extractor.

`flutter create` produces a plain Android app. Four things are missing for it
to reach YouTube: the JitPack repository the extractor is published to, the
extractor and its JavaScript engine as dependencies, a minimum SDK new enough
for them, and permission to use the network.

Everything here is additive. Gradle merges repeated `android`, `dependencies`
and `allprojects` blocks, so appending is safer than rewriting a generated
file whose exact shape changes between Flutter versions.
"""

import re
import sys
from pathlib import Path

EXTRACTOR = "v0.26.5"

REPOSITORIES = """

// Jubal: the extractor is published through JitPack.
allprojects {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
"""

REPOSITORIES_GROOVY = """

// Jubal: the extractor is published through JitPack.
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
"""

APP_KTS = f"""

// Jubal: reaching YouTube's audio.
android {{
    defaultConfig {{
        // The extractor uses java.time; 26 is where that stops needing
        // desugaring, and every device this app targets is well past it.
        minSdk = 26
    }}
    compileOptions {{
        isCoreLibraryDesugaringEnabled = true
    }}
}}

dependencies {{
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("com.github.teamnewpipe:NewPipeExtractor:{EXTRACTOR}")
    // The extractor runs YouTube's own player script to undo its signature
    // scrambling, and Rhino is what runs it.
    implementation("org.mozilla:rhino:1.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}}
"""

APP_GROOVY = f"""

// Jubal: reaching YouTube's audio.
android {{
    defaultConfig {{
        minSdk 26
    }}
    compileOptions {{
        coreLibraryDesugaringEnabled true
    }}
}}

dependencies {{
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'
    implementation 'com.github.teamnewpipe:NewPipeExtractor:{EXTRACTOR}'
    implementation 'org.mozilla:rhino:1.9.1'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}}
"""

PROGUARD = """# Rhino is reached by reflection while running YouTube's player script.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
"""


def pick(directory: Path, name: str):
    """Return (path, is_kotlin_dsl) for whichever dialect Flutter generated."""
    kts = directory / f"{name}.kts"
    if kts.exists():
        return kts, True
    groovy = directory / name
    if groovy.exists():
        return groovy, False
    raise SystemExit(f"neither {kts} nor {groovy} exists")


def append(path: Path, text: str) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(text)
    print(f"  extended {path.name}")


def add_internet_permission(manifest: Path) -> None:
    text = manifest.read_text(encoding="utf-8")
    if "android.permission.INTERNET" in text:
        print("  manifest already allows the network")
        return
    text = re.sub(
        r"(<manifest[^>]*>)",
        r'\1\n    <uses-permission android:name="android.permission.INTERNET" />',
        text,
        count=1,
    )
    manifest.write_text(text, encoding="utf-8")
    print("  manifest now allows the network")


def main() -> None:
    project = Path(sys.argv[1]).resolve()
    android = project / "android"
    app = android / "app"

    root_gradle, root_is_kts = pick(android, "build.gradle")
    append(root_gradle, REPOSITORIES if root_is_kts else REPOSITORIES_GROOVY)

    app_gradle, app_is_kts = pick(app, "build.gradle")
    append(app_gradle, APP_KTS if app_is_kts else APP_GROOVY)

    (app / "proguard-rules.pro").write_text(PROGUARD, encoding="utf-8")
    print("  wrote proguard-rules.pro")

    add_internet_permission(app / "src" / "main" / "AndroidManifest.xml")


if __name__ == "__main__":
    main()
