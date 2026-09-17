import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:palette_generator/palette_generator.dart';

/// Text-only build: colours are never derived from artwork.
///
/// The original implementation downloaded the album cover and ran
/// [PaletteGenerator] over it. That was a second, easy-to-miss artwork network
/// path alongside `UniversalImage`. Both hooks now return a neutral colour
/// taken from the active theme, so the player and lyrics screens still get a
/// readable accent without any image being fetched or decoded.
///
/// Signatures are unchanged so every call site still compiles.

PaletteColor usePaletteColor(String imageUrl, WidgetRef ref) {
  final context = useContext();
  final theme = Theme.of(context);

  return useMemoized(
    () => PaletteColor(
      theme.brightness == Brightness.light
          ? Colors.gray[200]
          : Colors.gray[800],
      0,
    ),
    [theme.brightness],
  );
}

PaletteGenerator usePaletteGenerator(String imageUrl) {
  return useMemoized(() => PaletteGenerator.fromColors([]), const []);
}
