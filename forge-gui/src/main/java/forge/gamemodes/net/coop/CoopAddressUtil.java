package forge.gamemodes.net.coop;

/**
 * Helpers for co-op join addresses (LAN vs Tailscale).
 */
public final class CoopAddressUtil {
    private CoopAddressUtil() {
    }

    /**
     * Tailscale CGNAT addresses are in {@code 100.64.0.0/10}. Forge treats any
     * {@code 100.x} IPv4 join target as Tailscale for co-op purposes so UPnP
     * can be skipped (Tailscale does not need it).
     */
    public static boolean isTailscaleAddress(final String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        final String h = host.trim();
        if (!h.startsWith("100.")) {
            return false;
        }
        // Reject hostnames that merely start with "100." (unlikely) by requiring digits.
        final String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (final String part : parts) {
                final int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            }
            return true;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /**
     * Whether UPnP should be attempted when hosting for a guest that will use
     * this address. Tailscale joins never need UPnP; LAN/WAN may.
     */
    public static boolean shouldSkipUPnPForAddress(final String host) {
        return isTailscaleAddress(host);
    }
}
