package forge.gamemodes.net;

import forge.util.IHasForgeLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

/**
 * Standard Java {@link ObjectInputStream} that consults {@link WireClassFilter}
 * on every class resolve. Use for payloads that were written with a plain
 * {@link java.io.ObjectOutputStream} (not the thin-descriptor Netty codec),
 * such as Ascendant co-op world blobs.
 */
public final class FilteredJavaObjectInputStream extends ObjectInputStream implements IHasForgeLog {

    public FilteredJavaObjectInputStream(final InputStream in) throws IOException {
        super(in);
        WireStreamLimits.applyTo(this);
    }

    @Override
    protected Class<?> resolveClass(final ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        WireClassFilter.checkAllowed(desc.getName());
        return super.resolveClass(desc);
    }

    @Override
    protected Class<?> resolveProxyClass(final String[] interfaces) throws IOException, ClassNotFoundException {
        netLog.error("Rejected dynamic proxy on filtered ObjectInputStream implementing {}",
                String.join(", ", interfaces));
        if (WireClassFilter.isEnforcing()) {
            throw new InvalidClassException("dynamic proxy",
                    "proxy classes are not permitted by the multiplayer class filter");
        }
        return super.resolveProxyClass(interfaces);
    }
}
