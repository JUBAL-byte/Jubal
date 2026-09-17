import 'dart:typed_data';

import 'package:flutter/cupertino.dart';
import 'package:flutter_hooks/flutter_hooks.dart';

/// Text-only build: this app renders **no artwork at all**.
///
/// The widget and its static [imageProvider] keep their original signatures so
/// every existing call site still compiles, but nothing is ever painted and —
/// more importantly — nothing is ever fetched. `cached_network_image` is no
/// longer referenced anywhere in the app, so album covers, artist photos,
/// playlist covers and podcast art are never downloaded, cached or decoded.
///
/// Call sites that still reserve layout space for an image are being collapsed
/// screen by screen; until then they simply render empty.
class UniversalImage extends HookWidget {
  final String path;
  final double? height;
  final double? width;
  final double scale;
  final String? placeholder;
  final BoxFit? fit;

  const UniversalImage({
    required this.path,
    this.height,
    this.width,
    this.placeholder,
    this.fit,
    this.scale = 1,
    super.key,
  });

  /// A 1x1 fully transparent PNG. Used wherever the widget tree demands an
  /// [ImageProvider] (e.g. `DecorationImage`, `Avatar.provider`) so those
  /// widgets keep working without any image being loaded from disk or network.
  static final Uint8List _transparentPixel = Uint8List.fromList(const [
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
    0x54, 0x78, 0xDA, 0x63, 0x60, 0x60, 0x60, 0x60,
    0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0x7A, 0xA8,
    0x57, 0x50, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
    0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
  ]);

  static final MemoryImage _blankProvider = MemoryImage(_transparentPixel);

  /// Always returns a blank, already-in-memory provider. Never touches the
  /// network, the asset bundle or the file system.
  static ImageProvider imageProvider(
    String path, {
    final double? height,
    final double? width,
    final double scale = 1,
  }) {
    return _blankProvider;
  }

  @override
  Widget build(BuildContext context) {
    return const SizedBox.shrink();
  }
}
