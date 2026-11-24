import 'dart:convert';
import 'package:crypto/crypto.dart';

/// 去重用的摘要工具。
class Hashing {
  Hashing._();

  static String urlHash(String url) => sha256.convert(utf8.encode(normalizeUrl(url))).toString();

  /// 去首尾空白、去锚点、去结尾斜杠。
  static String normalizeUrl(String? url) {
    if (url == null) return '';
    var value = url.trim();
    final hashIndex = value.indexOf('#');
    if (hashIndex >= 0) value = value.substring(0, hashIndex);
    while (value.endsWith('/')) {
      value = value.substring(0, value.length - 1);
    }
    return value;
  }
}
