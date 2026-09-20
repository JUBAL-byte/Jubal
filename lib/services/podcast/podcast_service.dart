import 'package:collection/collection.dart';
import 'package:dio/dio.dart';
import 'package:xml/xml.dart';

import 'package:spotube/extensions/string.dart';
import 'package:spotube/models/podcast/podcast.dart';
import 'package:spotube/services/dio/dio.dart';

/// Podcast discovery and episode resolution.
///
/// Two steps, both against public sources:
///
///  1. [searchShows] queries an open podcast directory, which returns the
///     show's identity *and its public RSS feed URL*. Asking the directory
///     removes the guesswork of trying to derive a feed from a streaming
///     service's page.
///  2. [fetchEpisodes] reads that feed and takes each episode's `<enclosure>`
///     URL — the audio file the publisher themselves put on the open web.
///
/// Nothing here works around authentication, DRM, a paywall or a private feed.
/// An episode with no public enclosure simply has no audio source, and the UI
/// says so instead of inventing a URL.
class PodcastService {
  static const _directoryEndpoint = "https://itunes.apple.com/search";

  /// Searches the public podcast directory for shows matching [query].
  static Future<List<JubalShow>> searchShows(
    String query, {
    int limit = 25,
  }) async {
    if (query.trim().isEmpty) return const [];

    final response = await globalDio.get(
      _directoryEndpoint,
      queryParameters: {
        "media": "podcast",
        "entity": "podcast",
        "limit": limit,
        "term": query,
      },
    );

    final data = response.data;
    final results = (data is Map ? data["results"] : null) as List?;
    if (results == null) return const [];

    return results
        .whereType<Map>()
        .map((e) => JubalShow.fromITunes(e.cast<String, dynamic>()))
        .where((show) => show.name.isNotEmpty)
        .toList();
  }

  /// Reads a show's public RSS feed and returns its episodes, newest first.
  ///
  /// Episodes without a playable `<enclosure>` are dropped rather than shown
  /// as if they could be played.
  static Future<List<JubalEpisode>> fetchEpisodes(String feedUrl) async {
    // Feeds are served under many XML content types; ask for the body as a
    // plain string rather than letting Dio guess at a decoder.
    final response = await globalDio.get<String>(
      feedUrl,
      options: Options(responseType: ResponseType.plain),
    );

    final body = response.data;
    if (body == null || body.isEmpty) return const [];

    final document = XmlDocument.parse(body);

    final channel = document.findAllElements("channel").firstOrNull;
    if (channel == null) return const [];

    final showName = _childText(channel, "title") ?? "";

    final episodes = <JubalEpisode>[];
    for (final item in channel.findElements("item")) {
      final audioUrl = _enclosureUrl(item);
      if (audioUrl == null) continue;

      final title = _childText(item, "title") ?? "";
      if (title.isEmpty) continue;

      final publishedRaw = _childText(item, "pubDate");
      final rawDescription =
          _childText(item, "description") ?? _childText(item, "summary");

      episodes.add(
        JubalEpisode(
          id: _childText(item, "guid") ?? audioUrl,
          showName: showName,
          title: title.unescapeHtml().cleanHtml(),
          description: rawDescription?.unescapeHtml().cleanHtml().trim(),
          publishedRaw: publishedRaw,
          publishedAt: parseRfc822(publishedRaw),
          duration: parseDuration(_childText(item, "duration")),
          episodeNumber: int.tryParse(_childText(item, "episode") ?? ""),
          audioUrl: audioUrl,
        ),
      );
    }

    return episodes;
  }

  /// Finds a direct child by *local* name, so both `duration` and
  /// `itunes:duration` match without hard-coding namespace prefixes.
  static String? _childText(XmlElement parent, String localName) {
    for (final child in parent.childElements) {
      if (child.name.local == localName) {
        final text = child.innerText.trim();
        if (text.isNotEmpty) return text;
      }
    }
    return null;
  }

  static String? _enclosureUrl(XmlElement item) {
    for (final child in item.childElements) {
      if (child.name.local != "enclosure") continue;
      final url = child.getAttribute("url");
      if (url == null || url.isEmpty) continue;
      final type = child.getAttribute("type") ?? "";
      if (type.isNotEmpty && !type.toLowerCase().startsWith("audio")) continue;
      return url;
    }
    return null;
  }

  /// `itunes:duration` appears as seconds, `MM:SS` or `HH:MM:SS`.
  static Duration? parseDuration(String? raw) {
    if (raw == null || raw.isEmpty) return null;

    if (!raw.contains(":")) {
      final seconds = int.tryParse(raw.split(".").first);
      return seconds == null ? null : Duration(seconds: seconds);
    }

    final parts = raw.split(":").map((p) => int.tryParse(p.trim()) ?? 0).toList();
    if (parts.length == 2) {
      return Duration(minutes: parts[0], seconds: parts[1]);
    }
    if (parts.length == 3) {
      return Duration(hours: parts[0], minutes: parts[1], seconds: parts[2]);
    }
    return null;
  }

  static const _months = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
  };

  /// RSS dates are RFC 822 ("Tue, 09 Sep 2026 06:00:00 +0000"), which
  /// [DateTime.parse] does not accept.
  static DateTime? parseRfc822(String? raw) {
    if (raw == null || raw.isEmpty) return null;

    final cleaned = raw.replaceAll(",", " ").trim();
    final parts = cleaned.split(RegExp(r"\s+"));

    for (var i = 0; i + 2 < parts.length; i++) {
      final day = int.tryParse(parts[i]);
      final month = _months[parts[i + 1].toLowerCase()];
      final year = int.tryParse(parts[i + 2]);
      if (day == null || month == null || year == null) continue;

      var hour = 0, minute = 0, second = 0;
      if (i + 3 < parts.length && parts[i + 3].contains(":")) {
        final time =
            parts[i + 3].split(":").map((p) => int.tryParse(p) ?? 0).toList();
        hour = time.isNotEmpty ? time[0] : 0;
        minute = time.length > 1 ? time[1] : 0;
        second = time.length > 2 ? time[2] : 0;
      }
      return DateTime.utc(year, month, day, hour, minute, second);
    }
    return null;
  }
}
