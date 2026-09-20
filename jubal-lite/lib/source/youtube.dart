import 'package:flutter/services.dart';

/// One song, as far as this app is concerned.
class Song {
  final String id;
  final String title;
  final String artist;
  final Duration duration;

  const Song({
    required this.id,
    required this.title,
    required this.artist,
    required this.duration,
  });

  factory Song.fromMap(Map<Object?, Object?> map) {
    final seconds = (map["duration"] as num?)?.toInt() ?? 0;
    return Song(
      id: (map["id"] as String?) ?? "",
      title: (map["title"] as String?) ?? "Untitled",
      artist: (map["artist"] as String?) ?? "",
      duration: Duration(seconds: seconds < 0 ? 0 : seconds),
    );
  }

  /// `m:ss`, or `h:mm:ss` for anything over an hour.
  String get length {
    if (duration == Duration.zero) return "";
    final h = duration.inHours;
    final m = duration.inMinutes.remainder(60);
    final s = duration.inSeconds.remainder(60).toString().padLeft(2, "0");
    return h > 0 ? "$h:${m.toString().padLeft(2, "0")}:$s" : "$m:$s";
  }
}

/// Raised when the Android side could not answer. Carries what it said.
class SourceException implements Exception {
  final String message;
  const SourceException(this.message);

  @override
  String toString() => message;
}

/// The only way this app reaches music.
///
/// Both calls cross into Kotlin, where NewPipe's extractor does the work. The
/// Dart side deliberately knows nothing about how YouTube is reached — that
/// detail changes every few months, and it changes in one file.
abstract final class YouTube {
  static const _channel = MethodChannel("jubal/youtube");

  static Future<List<Song>> search(String query) async {
    try {
      final raw = await _channel.invokeMethod<List<Object?>>(
        "search",
        {"query": query},
      );
      return (raw ?? const [])
          .whereType<Map<Object?, Object?>>()
          .map(Song.fromMap)
          .where((song) => song.id.isNotEmpty)
          .toList();
    } on PlatformException catch (error) {
      throw SourceException(error.message ?? "Search failed");
    }
  }

  /// The playable address for [id]. These expire, so it is fetched per play
  /// rather than stored.
  static Future<String> streamUrl(String id) async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>(
        "stream",
        {"id": id},
      );
      final url = raw?["url"] as String?;
      if (url == null || url.isEmpty) {
        throw const SourceException("No audio address was returned");
      }
      return url;
    } on PlatformException catch (error) {
      throw SourceException(error.message ?? "Could not reach the audio");
    }
  }
}
