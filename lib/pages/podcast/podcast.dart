import 'package:auto_route/auto_route.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';

import 'package:spotube/components/fallbacks/error_box.dart';
import 'package:spotube/components/titlebar/titlebar.dart';
import 'package:spotube/extensions/duration.dart';
import 'package:spotube/models/podcast/podcast.dart';
import 'package:spotube/provider/audio_player/audio_player.dart';
import 'package:spotube/provider/podcast/podcast.dart';

/// A podcast show: its episodes, listed as text.
///
/// Playing an episode hands the player the episode's public RSS audio URL,
/// through the same queue the rest of the app uses.
@RoutePage()
class PodcastPage extends HookConsumerWidget {
  static const name = "podcast";

  final JubalShow show;

  const PodcastPage({super.key, required this.show});

  @override
  Widget build(BuildContext context, ref) {
    final playlistNotifier = ref.watch(audioPlayerProvider.notifier);
    final playlist = ref.watch(audioPlayerProvider);

    final feedUrl = show.feedUrl ?? "";
    final snapshot = ref.watch(podcastEpisodesProvider(feedUrl));

    return SafeArea(
      bottom: false,
      child: Scaffold(
        headers: const [TitleBar()],
        child: Builder(
          builder: (context) {
            if (!show.isPlayable) {
              return const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text("Public audio source not available."),
                ),
              );
            }

            if (snapshot.hasError) {
              return ErrorBox(
                error: snapshot.error!,
                onRetry: () =>
                    ref.invalidate(podcastEpisodesProvider(feedUrl)),
              );
            }

            if (snapshot.isLoading) {
              return const Center(child: CircularProgressIndicator());
            }

            final episodes = snapshot.asData?.value ?? const <JubalEpisode>[];

            if (episodes.isEmpty) {
              return const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text("Public audio source not available."),
                ),
              );
            }

            return ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: episodes.length + 1,
              separatorBuilder: (context, index) => const Divider(),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          show.name,
                          style: Theme.of(context).typography.h3,
                        ),
                        if (show.publisher.isNotEmpty) ...[
                          const Gap(4),
                          Text(show.publisher).muted(),
                        ],
                        const Gap(4),
                        Text("${episodes.length} episodes").xSmall().muted(),
                      ],
                    ),
                  );
                }

                final episode = episodes[index - 1];
                final track = episode.toTrack();
                final isPlaying = playlist.activeTrack?.id == track.id;

                final meta = [
                  if (episode.episodeNumber != null)
                    "Episode ${episode.episodeNumber}",
                  if (episode.publishedAt != null)
                    "${episode.publishedAt!.day}/${episode.publishedAt!.month}/${episode.publishedAt!.year}"
                  else if (episode.publishedRaw != null)
                    episode.publishedRaw!,
                  if (episode.duration != null)
                    episode.duration!.toHumanReadableString(),
                ].join(" · ");

                return Button(
                  style: ButtonVariance.ghost,
                  onPressed: () async {
                    await playlistNotifier.load([track], autoPlay: true);
                  },
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        episode.title,
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (meta.isNotEmpty) ...[
                        const Gap(2),
                        Text(meta).xSmall().muted(),
                      ],
                      if (episode.description != null &&
                          episode.description!.isNotEmpty) ...[
                        const Gap(4),
                        Text(
                          episode.description!,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ).xSmall().muted(),
                      ],
                      if (isPlaying) ...[
                        const Gap(4),
                        const Text("Now playing").xSmall(),
                      ],
                    ],
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
