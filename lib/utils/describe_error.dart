import 'package:dio/dio.dart';

/// Query parameters whose values are secrets rather than diagnostics.
const _redactedParams = {
  "access_token",
  "refresh_token",
  "token",
  "key",
  "api_key",
  "apikey",
  "client_secret",
  "password",
  "auth",
  "signature",
  "sig",
};

Uri _redact(Uri uri) {
  if (uri.queryParameters.isEmpty) return uri;

  final safe = <String, String>{};
  for (final entry in uri.queryParameters.entries) {
    safe[entry.key] = _redactedParams.contains(entry.key.toLowerCase())
        ? "<redacted>"
        : entry.value;
  }
  return uri.replace(queryParameters: safe);
}

/// A description of [error] that names what actually failed.
///
/// `DioException.toString()` is four lines about what status codes mean and a
/// link to MDN, and never says which request failed. Reading one tells you a
/// 401 happened but not who returned it — which is the only thing worth
/// knowing, since a 401 from a metadata provider and a 401 from an audio
/// source are entirely different problems.
///
/// This puts the method, the address, the status and the server's own reply
/// first. Secrets in the query string are replaced before the text is shown,
/// because these descriptions are meant to be copied and shared.
String describeError(Object? error) {
  if (error is! DioException) return error.toString();

  final request = error.requestOptions;
  final response = error.response;

  final buffer = StringBuffer()
    ..writeln("${request.method} ${_redact(request.uri)}");

  if (response != null) {
    final status = "${response.statusCode} ${response.statusMessage ?? ""}";
    buffer.writeln("-> ${status.trim()}");

    final body = response.data?.toString();
    if (body != null && body.trim().isNotEmpty) {
      buffer.writeln(
        body.length > 600 ? "${body.substring(0, 600)}..." : body,
      );
    }
  } else {
    buffer.writeln("-> ${error.type.name}");
  }

  final message = error.message;
  if (message != null && message.trim().isNotEmpty) {
    buffer.writeln(message.trim());
  }

  return buffer.toString().trim();
}
