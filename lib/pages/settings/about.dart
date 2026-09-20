import 'package:flutter/services.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:spotube/collections/env.dart';
import 'package:spotube/components/button/back_button.dart';
import 'package:spotube/components/titlebar/titlebar.dart';
import 'package:spotube/extensions/context.dart';
import 'package:spotube/hooks/controllers/use_package_info.dart';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:auto_route/auto_route.dart';

final _licenseProvider = FutureProvider<String>((ref) async {
  return await rootBundle.loadString("LICENSE");
});

/// About Jubal.
///
/// This page used to be a wall of someone else's branding: a logo, a founder's
/// profile, a website, a repository, a chat invite. None of it belongs in a
/// build whose whole point is that there is no way out to the open web, and a
/// link that cannot be opened is worse than no link at all.
///
/// What remains is what this page is actually for — which build you are
/// running, and the credit and licence text that the upstream project is owed.
/// The attribution is deliberate: Jubal is a fork, and says so in words.
@RoutePage()
class AboutSpotubePage extends HookConsumerWidget {
  static const name = "about";

  const AboutSpotubePage({super.key});

  @override
  Widget build(BuildContext context, ref) {
    final packageInfo = usePackageInfo();
    final license = ref.watch(_licenseProvider);
    final theme = Theme.of(context);

    const colon = TableCell(child: Text(":"));

    return SafeArea(
      bottom: false,
      child: Scaffold(
        headers: [
          TitleBar(
            leading: const [BackButton()],
            title: const Text("About Jubal"),
          )
        ],
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Column(
              children: [
                const SizedBox(height: 24),
                Center(
                  child: Column(
                    children: [
                      const Text(
                        "Text only music.",
                        textAlign: TextAlign.center,
                      ).semiBold().large(),
                      const SizedBox(height: 20),
                      Table(
                        columnWidths: const {
                          0: FixedTableSize(95),
                          1: FixedTableSize(10),
                          2: IntrinsicTableSize(),
                        },
                        defaultRowHeight: const FixedTableSize(40),
                        rows: [
                          TableRow(
                            cells: [
                              TableCell(child: Text(context.l10n.version)),
                              colon,
                              TableCell(child: Text("v${packageInfo.version}"))
                            ],
                          ),
                          TableRow(
                            cells: [
                              TableCell(child: Text(context.l10n.channel)),
                              colon,
                              TableCell(child: Text(Env.releaseChannel.name))
                            ],
                          ),
                          TableRow(
                            cells: [
                              TableCell(child: Text(context.l10n.build_number)),
                              colon,
                              TableCell(
                                child: Text(packageInfo.buildNumber
                                    .replaceAll(".", " ")),
                              )
                            ],
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  "Jubal is built on Spotube by Kingkor Roy Tirtho, "
                  "used under the BSD-4-Clause licence.",
                  textAlign: TextAlign.center,
                  style: theme.typography.small,
                ),
                Text(
                  context.l10n.copyright(DateTime.now().year),
                  textAlign: TextAlign.center,
                  style: theme.typography.small,
                ),
                const SizedBox(height: 20),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 750),
                  child: SafeArea(
                    child: license.when(
                      data: (data) {
                        return Text(
                          data,
                          style: theme.typography.small,
                        );
                      },
                      loading: () {
                        return const Center(
                          child: CircularProgressIndicator(),
                        );
                      },
                      error: (e, s) {
                        return Text(
                          e.toString(),
                          style: theme.typography.small,
                        );
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
