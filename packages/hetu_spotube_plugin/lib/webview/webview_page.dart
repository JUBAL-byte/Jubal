import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:path/path.dart';
import 'package:path_provider/path_provider.dart';
import 'package:fk_user_agent/fk_user_agent.dart';

import 'allowed_hosts.dart';

Future<String?> getUserAgent() async {
  if (Platform.isIOS || Platform.isAndroid) {
    await FkUserAgent.init();
    return FkUserAgent.userAgent;
  }
  return null;
}

final webViewEnvironment = Platform.isWindows
    ? getApplicationSupportDirectory().then((directory) async {
        return await WebViewEnvironment.create(
          settings: WebViewEnvironmentSettings(
            userDataFolder: join(directory.path, 'inappwebview_data'),
          ),
        );
      })
    : Future.value(null);

/// The sign-in webview.
///
/// Jubal is a browsing-free build: this webview is the one place where web
/// content is rendered, and it exists only so a metadata provider can be
/// signed in to. Navigation is therefore closed by default and opened only for
/// the hosts in [kAllowedSignInDomains] — the provider, the identity providers
/// it hands off to, and the assets and verification hosts those pages need.
///
/// Anything else is cancelled before it loads, and a short notice names the
/// host that was refused so a blocked tap does not look like a frozen page.
class WebviewPage extends StatefulWidget {
  final String uri;
  final void Function(String url)? onLoad;
  const WebviewPage({super.key, required this.uri, this.onLoad});

  @override
  State<WebviewPage> createState() => _WebviewPageState();
}

class _WebviewPageState extends State<WebviewPage> {
  String? _blockedHost;
  Timer? _blockedTimer;

  @override
  void dispose() {
    _blockedTimer?.cancel();
    super.dispose();
  }

  void _noteBlocked(String host) {
    if (!mounted) return;
    setState(() => _blockedHost = host);
    _blockedTimer?.cancel();
    _blockedTimer = Timer(const Duration(seconds: 4), () {
      if (mounted) setState(() => _blockedHost = null);
    });
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: Future.wait([webViewEnvironment, getUserAgent()]),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }

        return Stack(
          children: [
            InAppWebView(
              initialUrlRequest: URLRequest(url: WebUri(widget.uri)),
              webViewEnvironment: snapshot.data?[0] as WebViewEnvironment?,
              initialSettings: InAppWebViewSettings(
                userAgent: snapshot.data?[1] as String?,
                // Required for shouldOverrideUrlLoading to be consulted.
                useShouldOverrideUrlLoading: true,
                // Keep every navigation inside this one webview, so the
                // allowlist below sees it. Pop-ups would bypass the check.
                supportMultipleWindows: false,
                javaScriptCanOpenWindowsAutomatically: false,
              ),
              shouldOverrideUrlLoading: (controller, navigationAction) async {
                final url = navigationAction.request.url;
                if (url == null) {
                  return NavigationActionPolicy.CANCEL;
                }
                if (isSignInUrlAllowed(url)) {
                  return NavigationActionPolicy.ALLOW;
                }
                debugPrint("[Webview] blocked navigation to ${url.host}");
                _noteBlocked(url.host);
                return NavigationActionPolicy.CANCEL;
              },
              onLoadStop: (controller, url) {
                try {
                  if (widget.onLoad != null && url != null) {
                    widget.onLoad!(url.toString());
                  }
                } catch (e, stack) {
                  debugPrint("[Webview][onLoad] Error: $e");
                  debugPrintStack(stackTrace: stack);
                  rethrow;
                }
              },
            ),
            if (_blockedHost != null)
              Positioned(
                left: 12,
                right: 12,
                bottom: 12,
                child: Material(
                  color: Colors.black87,
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 10,
                    ),
                    child: Text(
                      "Blocked: $_blockedHost\n"
                      "This window is for signing in only.",
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
