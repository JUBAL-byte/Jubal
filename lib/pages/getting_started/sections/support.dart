import 'package:auto_route/auto_route.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:spotube/collections/routes.gr.dart';
import 'package:spotube/collections/spotube_icons.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/services/kv_store/kv_store.dart';

/// The last step of the first run.
///
/// This screen used to end on someone else's fundraising: a "contribute on
/// GitHub" button and a donation link. In a build with no way out to the open
/// web neither of them could go anywhere, so both are gone and the step now
/// does the one thing that actually gets the app working — sending you to
/// install a metadata provider.
class GettingStartedScreenSupportSection extends HookConsumerWidget {
  const GettingStartedScreenSupportSection({super.key});

  @override
  Widget build(BuildContext context, ref) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 250),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Button.primary(
              leading: const Icon(SpotubeIcons.extensions),
              onPressed: () async {
                await KVStoreService.setDoneGettingStarted(true);
                if (context.mounted) {
                  context.pushRoute(const SettingsMetadataProviderRoute());
                }
              },
              child: Text(context.l10n.install_a_metadata_provider),
            ),
          ],
        ),
      ),
    );
  }
}
