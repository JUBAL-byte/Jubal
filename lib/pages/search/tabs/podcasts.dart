import 'package:auto_route/auto_route.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';

import 'package:spotube/collections/routes.gr.dart';
import 'package:spotube/components/fallbacks/error_box.dart';
import 'package:spotube/modules/search/loading.dart';
import 'package:spotube/pages/search/search.dart';
import 'package:spotube/provider/podcast/podcast.dart';

/// Podcast results, text only: show name, publisher, and how many episodes.
class SearchPagePodcastsTab extends HookConsumerWidget {
  const SearchPagePodcastsTab({super.key});

  @override
  Widget build(BuildContext context, ref) {
    final searchTerm = ref.watch(searchTermStateProvider);
    final snapshot = ref.watch(podcastSearchProvider(searchTerm));

    if (snapshot.hasError) {
      return ErrorBox(
        error: snapshot.error!,
        onRetry: () => ref.invalidate(podcastSearchProvider(searchTerm)),
      );
    }

    final shows = snapshot.asData?.value ?? const [];

    return SearchPlaceholder(
      snapshot: snapshot,
      child: shows.isEmpty
          ? const Center(
              child: Text("No podcasts found."),
            )
          : ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: shows.length,
              separatorBuilder: (context, index) => const Divider(),
              itemBuilder: (context, index) {
                final show = shows[index];

                final meta = [
                  if (show.publisher.isNotEmpty) show.publisher,
                  if (show.episodeCount != null)
                    "${show.episodeCount} episodes",
                  if (!show.isPlayable) "No public feed",
                ].join(" · ");

                return Button(
                  style: ButtonVariance.ghost,
                  enabled: show.isPlayable,
                  onPressed: () {
                    context.navigateTo(PodcastRoute(show: show));
                  },
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        show.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (meta.isNotEmpty)
                        Text(
                          meta,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ).xSmall().muted(),
                    ],
                  ),
                );
              },
            ),
    );
  }
}
