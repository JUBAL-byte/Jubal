import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:shadcn_flutter/shadcn_flutter_extension.dart';
import 'package:spotube/collections/spotube_icons.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/extensions/string.dart';

/// Text-only card used in grid views (search results, home sections, library).
///
/// The original rendered a 150x150 cover with the play button floating over it.
/// There is no artwork in this build, so the card is now a title + description
/// block with the same actions moved to the trailing edge. [imageUrl] and
/// [image] are kept — and ignored — so the many call sites still compile.
class PlaybuttonCard extends StatelessWidget {
  final void Function()? onTap;
  final void Function()? onPlaybuttonPressed;
  final void Function()? onAddToQueuePressed;
  final String? description;

  final String? imageUrl;
  final Widget? image;
  final bool isPlaying;
  final bool isLoading;
  final String title;
  final bool isOwner;

  const PlaybuttonCard({
    required this.isPlaying,
    required this.isLoading,
    required this.title,
    this.description,
    this.onPlaybuttonPressed,
    this.onAddToQueuePressed,
    this.onTap,
    this.isOwner = false,
    this.imageUrl,
    this.image,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final cleanDescription = description?.unescapeHtml().cleanHtml() ?? "";
    final scale = context.theme.scaling;

    return SizedBox(
      width: 220 * scale,
      child: Button(
        style: ButtonVariance.ghost,
        enabled: !isLoading,
        onPressed: onTap,
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              tooltip:
                  TooltipContainer(child: Text(context.l10n.add_to_queue)).call,
              child: IconButton.ghost(
                icon: const Icon(SpotubeIcons.queueAdd),
                onPressed: onAddToQueuePressed,
                enabled: !isLoading,
                size: ButtonSize.small,
              ),
            ),
            const Gap(4),
            Tooltip(
              tooltip: TooltipContainer(child: Text(context.l10n.play)).call,
              child: IconButton.secondary(
                icon: switch ((isLoading, isPlaying)) {
                  (true, _) => const CircularProgressIndicator(size: 15),
                  (false, false) => const Icon(SpotubeIcons.play),
                  (false, true) => const Icon(SpotubeIcons.pause),
                },
                onPressed: onPlaybuttonPressed,
                enabled: !isLoading,
                size: ButtonSize.small,
              ),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            if (cleanDescription.isNotEmpty)
              Text(
                cleanDescription,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ).xSmall().muted(),
          ],
        ),
      ),
    );
  }
}
