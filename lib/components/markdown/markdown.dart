import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:spotube/components/dialogs/link_open_permission_dialog.dart';
import 'package:url_launcher/url_launcher_string.dart';

class AppMarkdown extends StatelessWidget {
  final String data;
  const AppMarkdown({
    super.key,
    required this.data,
  });

  @override
  Widget build(BuildContext context) {
    return MarkdownBody(
      data: data,
      // Text-only: markdown images are never fetched. Show the alt text
      // instead so the information is not lost.
      imageBuilder: (uri, title, alt) {
        final label = (alt?.isNotEmpty == true ? alt : title) ?? "";
        if (label.isEmpty) return const SizedBox.shrink();
        return Text(label).small().muted();
      },
      onTapLink: (text, href, title) async {
        final allowOpeningLink = await showDialog<bool>(
          context: context,
          builder: (context) {
            return LinkOpenPermissionDialog(href: href);
          },
        );

        if (href != null && allowOpeningLink == true) {
          launchUrlString(
            href,
            mode: LaunchMode.externalApplication,
          );
        }
      },
    );
  }
}
