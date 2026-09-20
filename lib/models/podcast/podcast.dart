import 'package:spotube/models/metadata/metadata.dart';

/// A podcast show, as returned by the public podcast directory.
///
/// Discovery and playback are deliberately independent of whichever metadata
/// plugin is active: a show is found in an open directory, and its audio comes
/// from the publisher's own public RSS feed. Nothing here touches a streaming
/// service's protected content.
class JubalShow {
  final String id;
  final String name;
  final String publisher;

  /// The publisher's public RSS feed. Without it a show cannot be played, and
  /// the UI says so rather than guessing at an audio URL.
  final String? feedUrl;

  final String? genre;
  final int? episodeCount;

  const JubalShow({
    required this.id,
    required this.name,
    required this.publisher,
    this.feedUrl,
    this.genre,
    this.episodeCount,
  });

  bool get isPlayable => feedUrl != null && feedUrl!.isNotEmpty;

  factory JubalShow.fromITunes(Map<String, dynamic> json) {
    return JubalShow(
      id: (json["collectionId"] ?? json["trackId"] ?? "").toString(),
      name: (json["collectionName"] ?? json["trackName"] ?? "").toString(),
      publisher: (json["artistName"] ?? "").toString(),
      feedUrl: json["feedUrl"] as String?,
      genre: json["primaryGenreName"] as String?,
      episodeCount: json["trackCount"] as int?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is JubalShow && other.id == id && other.feedUrl == feedUrl;

  @override
  int get hashCode => Object.hash(id, feedUrl);
}

/// A single episode parsed out of a show's public RSS feed.
class JubalEpisode {
  final String id;
  final String showName;
  final String title;
  final String? description;
  final DateTime? publishedAt;
  final String? publishedRaw;
  final Duration? duration;
  final int? episodeNumber;

  /// The `<enclosure>` URL from the feed — the publisher's own audio file.
  final String audioUrl;

  const JubalEpisode({
    required this.id,
    required this.showName,
    required this.title,
    required this.audioUrl,
    this.description,
    this.publishedAt,
    this.publishedRaw,
    this.duration,
    this.episodeNumber,
  });

  /// Renders the episode as a track the existing player already knows how to
  /// handle.
  ///
  /// A "local" track is one whose `path` is played directly instead of being
  /// resolved through the audio-source plugin. Pointing that path at the RSS
  /// enclosure URL means an episode plays through the untouched playback
  /// pipeline — queue, notification, seeking and all — with no special case
  /// anywhere in the player.
  SpotubeTrackObject toTrack() {
    final artist = SpotubeSimpleArtistObject(
      id: showName,
      name: showName,
      externalUri: audioUrl,
    );

    return SpotubeTrackObject.local(
      id: audioUrl,
      name: title,
      externalUri: audioUrl,
      artists: [artist],
      album: SpotubeSimpleAlbumObject(
        id: showName,
        name: showName,
        externalUri: audioUrl,
        artists: [artist],
        albumType: SpotubeAlbumType.album,
        releaseDate: publishedAt?.toIso8601String(),
        images: const [],
      ),
      durationMs: duration?.inMilliseconds ?? 0,
      path: audioUrl,
    );
  }
}
