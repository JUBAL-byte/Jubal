import 'package:hooks_riverpod/hooks_riverpod.dart';

import 'package:spotube/models/podcast/podcast.dart';
import 'package:spotube/services/podcast/podcast_service.dart';

/// Shows matching a search term, from the public podcast directory.
final podcastSearchProvider =
    FutureProvider.autoDispose.family<List<JubalShow>, String>(
  (ref, query) async {
    if (query.trim().isEmpty) return const [];
    return PodcastService.searchShows(query);
  },
);

/// Episodes of a show, read from its public RSS feed.
///
/// Keyed by feed URL rather than by the show object, so the same feed reached
/// from two places shares one fetch.
final podcastEpisodesProvider =
    FutureProvider.autoDispose.family<List<JubalEpisode>, String>(
  (ref, feedUrl) async {
    if (feedUrl.isEmpty) return const [];
    return PodcastService.fetchEpisodes(feedUrl);
  },
);
