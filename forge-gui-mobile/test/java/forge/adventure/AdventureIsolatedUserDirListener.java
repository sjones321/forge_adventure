package forge.adventure;

import org.testng.ISuite;
import org.testng.ISuiteListener;

/**
 * Suite listener: redirect Forge {@code USER_DIR} / {@code USER_ADVENTURE_DIR}
 * to a temp folder for the whole surefire suite, then restore and assert the
 * real adventure user tree was untouched.
 *
 * <p>Matches the MV2 (#30) shared-suite-listener approach (not yet merged).
 * Register via surefire {@code listener} property, or call
 * {@link IsolatedAdventureUserDir#install()} from a {@code @BeforeClass}.
 */
public final class AdventureIsolatedUserDirListener implements ISuiteListener {
    @Override
    public void onStart(final ISuite suite) {
        try {
            IsolatedAdventureUserDir.install();
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to isolate Forge USER_ADVENTURE_DIR for tests", e);
        }
    }

    @Override
    public void onFinish(final ISuite suite) {
        try {
            IsolatedAdventureUserDir.restoreAndAssertUntouched();
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Real USER_ADVENTURE_DIR was mutated by tests, or restore failed", e);
        }
    }
}
