package com.codeauto.tools;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

final class WebToolUrlGuard {
  private WebToolUrlGuard() {
  }

  static String validatePublicHttpUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (Exception error) {
      return "Invalid URL: " + error.getMessage();
    }

    String scheme = uri.getScheme();
    if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      return "Only http/https URLs are allowed, got: " + scheme;
    }
    if (uri.getUserInfo() != null) {
      return "URLs with embedded credentials are not allowed";
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return "URL has no host: " + url;
    }

    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (Exception error) {
      return "Host could not be resolved safely: " + host;
    }
    if (addresses.length == 0) {
      return "Host could not be resolved safely: " + host;
    }
    for (InetAddress address : addresses) {
      if (isBlocked(address)) {
        return "Access to private/internal addresses is not allowed: " + host;
      }
    }
    return null;
  }

  private static boolean isBlocked(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    if (address instanceof Inet6Address inet6) {
      byte[] bytes = inet6.getAddress();
      int first = bytes[0] & 0xFF;
      if ((first & 0xFE) == 0xFC) {
        return true; // fc00::/7 unique local addresses
      }
    }
    return false;
  }
}
