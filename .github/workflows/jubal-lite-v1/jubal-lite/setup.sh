#!/usr/bin/env bash
#
# Assemble the buildable project from this directory's sources.
#
# Only what is written by hand lives in the repository: the Dart, the Kotlin
# bridge, the dependency list and the patches. The Android scaffolding —
# gradle wrappers, manifests, launcher boilerplate — is generated fresh by
# `flutter create`, so it always matches the Flutter version doing the build
# instead of being a checked-in copy that quietly rots.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/.build}"

echo "==> generating the Android project in $out"
rm -rf "$out"
flutter create \
  --org com.jubal \
  --project-name jubal_lite \
  --platforms=android \
  --no-pub \
  "$out" >/dev/null

echo "==> applying Jubal's own sources"
cp "$here/pubspec.yaml" "$out/pubspec.yaml"
rm -rf "$out/lib"
cp -r "$here/lib" "$out/lib"

kotlin_dir="$out/android/app/src/main/kotlin/com/jubal/jubal_lite"
mkdir -p "$kotlin_dir"
# flutter create writes its own MainActivity; ours replaces it.
cp "$here"/android/*.kt "$kotlin_dir/"
echo "  kotlin: $(ls "$kotlin_dir" | tr '\n' ' ')"

echo "==> patching the Android build"
python3 "$here/patch_android.py" "$out"

echo "==> resolving packages"
(cd "$out" && flutter pub get)

echo "==> ready: $out"
