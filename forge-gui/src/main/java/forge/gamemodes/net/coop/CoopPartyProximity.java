package forge.gamemodes.net.coop;

/**
 * CO3 dependency on party + proximity, behind a small interface so this package
 * builds on feature/set-start alone (CO2's {@code CoopPartyState} is not present
 * yet). After PR #18 merges, wire this to:
 * <ul>
 *   <li>{@code CoopPartyState.inParty()}</li>
 *   <li>{@code CoopPartyState.withinRadius(ax, ay, bx, by, tileSize, radiusTiles)}</li>
 * </ul>
 * The safe default is {@link #NEVER} (not in a party → normal solo fight, never prompt).
 */
public interface CoopPartyProximity {
    /**
     * @return true when the local peer is in a party with the connected partner
     *         and that partner is within the configured radius
     */
    boolean partnerInPartyAndNearby();

    /** Always false — not in a party, never prompt. */
    CoopPartyProximity NEVER = () -> false;
}
