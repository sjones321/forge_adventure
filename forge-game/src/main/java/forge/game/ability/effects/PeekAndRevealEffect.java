package forge.game.ability.effects;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Maps;

import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.Localizer;

public class PeekAndRevealEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final Player peeker = sa.getActivatingPlayer();
        final int numPeek = sa.hasParam("PeekAmount") ?
                AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("PeekAmount"), sa) : 1;
        final String verb = sa.hasParam("NoReveal") || sa.hasParam("RevealOptional") ? " looks at " :
                " reveals ";
        final String defined = sa.getParamOrDefault("Defined", "");
        final List<Player> libraryPlayers = getDefinedPlayersOrTargeted(sa);
        final String defString = Lang.joinHomogenous(libraryPlayers);
        String who = defined.equals("Player") && verb.equals(" reveals ") ? "Each player" :
                sa.hasParam("NoPeek") && verb.equals(" reveals ") ? defString : "";
        String whose = defined.equals("Player") && verb.equals(" looks at ") ? "each player's"
                : libraryPlayers.size() == 1 && libraryPlayers.get(0) == peeker ? "their" :
                defString + "'s";

        final StringBuilder sb = new StringBuilder();

        sb.append(who.isEmpty() ? peeker : who);
        sb.append(verb).append("the top ");
        sb.append(numPeek > 1 ? Lang.getNumeral(numPeek) + " cards " : "card ").append("of ").append(whose);
        sb.append(" library.");

        return sb.toString();
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#resolve(forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final Card source = sa.getHostCard();
        final boolean rememberRevealed = sa.hasParam("RememberRevealed");
        final boolean imprintRevealed = sa.hasParam("ImprintRevealed");
        final boolean noPeek = sa.hasParam("NoPeek");
        String revealValid = sa.getParamOrDefault("RevealValid", "Card");
        String peekAmount = sa.getParamOrDefault("PeekAmount", "1");
        int numPeek = AbilityUtils.calculateAmount(source, peekAmount, sa);
        final ZoneType srcZone = sa.hasParam("SourceZone") ? ZoneType.smartValueOf(sa.getParam("SourceZone")) : ZoneType.Library;

        List<Player> srcZonePlayers = getDefinedPlayersOrTargeted(sa);
        final Player peekingPlayer = sa.getActivatingPlayer();

        for (Player zoneToPeek : srcZonePlayers) {
            final PlayerZone playerZone = zoneToPeek.getZone(srcZone);
            numPeek = Math.min(numPeek, playerZone.size());

            CardCollection peekCards = new CardCollection();
            for (int i = 0; i < numPeek; i++) {
                peekCards.add(playerZone.get(i));
            }

            Map<String, Object> params = new HashMap<>();
            params.put("Revealed", peekCards);

            CardCollectionView revealableCards = CardLists.getValidCards(peekCards, revealValid, peekingPlayer, source, sa);
            boolean doReveal = !sa.hasParam("NoReveal") && !revealableCards.isEmpty();
            final boolean optional = sa.hasParam("RevealOptional");

            if (optional && !noPeek) {
                if (doReveal) {
                    Card show = revealableCards.get(0);
                    doReveal = peekingPlayer.getController().confirmAction(sa, null,
                            optionalRevealPrompt(sa), show, params);
                } else if (!peekCards.isEmpty()) {
                    peekingPlayer.getController().reveal(peekCards, srcZone, zoneToPeek,
                            missLookPrompt(sa, source));
                }
            } else {
                if (!noPeek) {
                    peekingPlayer.getController().reveal(peekCards, srcZone, zoneToPeek,
                            source.getTranslatedName() + " - " +
                                    Localizer.getInstance().getMessage("lblLookingCardFrom"));
                }
                if (doReveal && optional) {
                    doReveal = peekingPlayer.getController().confirmAction(sa, null,
                            Localizer.getInstance().getMessage("lblRevealCardToOtherPlayers"), params);
                }
            }

            if (doReveal) {
                peekingPlayer.getGame().getAction().reveal(revealableCards, srcZone, zoneToPeek, !noPeek,
                        source.getTranslatedName() + " - " +
                                Localizer.getInstance().getMessage("lblRevealingCardFrom"));

                if (rememberRevealed) {
                    Map<Integer, Card> cachedMap = Maps.newHashMap();
                    for (Card c : revealableCards) {
                        source.addRemembered(CardCopyService.getLKICopy(c, cachedMap));
                    }
                }
                if (imprintRevealed) {
                    Map<Integer, Card> cachedMap = Maps.newHashMap();
                    for (Card c : revealableCards) {
                        source.addImprintedCard(CardCopyService.getLKICopy(c, cachedMap));
                    }
                }
            } else if (sa.hasParam("RememberPeeked")) {
                Map<Integer, Card> cachedMap = Maps.newHashMap();
                for (Card c : revealableCards) {
                    source.addRemembered(CardCopyService.getLKICopy(c, cachedMap));
                }
            }
        }
    }

    /** Herald's Horn and the same "you may reveal and put into your hand" peek. */
    static String optionalRevealPrompt(SpellAbility sa) {
        if (putsRevealedInHand(sa)) {
            return "Reveal and put into your hand?";
        }
        return Localizer.getInstance().getMessage("lblRevealCardToOtherPlayers");
    }

    /** Looked-at miss. Named type when RevealValid uses ChosenType. */
    static String missLookPrompt(SpellAbility sa, Card source) {
        String valid = sa == null ? "Card" : sa.getParamOrDefault("RevealValid", "Card");
        if (valid.contains("ChosenType") && source != null && source.hasChosenType()) {
            return "Not a " + source.getChosenType() + " — OK";
        }
        if (valid.contains("sharesCreatureTypeWith")) {
            return "Does not share a creature type — OK";
        }
        String readable = readableRevealValid(valid);
        if (readable != null) {
            return "Not a " + readable + " — OK";
        }
        return "Not a match — OK";
    }

    private static boolean putsRevealedInHand(SpellAbility sa) {
        if (sa == null) {
            return false;
        }
        for (SpellAbility sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if ("Hand".equalsIgnoreCase(sub.getParam("Destination")) && sub.hasParam("ConditionDefined")) {
                return true;
            }
        }
        return false;
    }

    private static String readableRevealValid(String valid) {
        if (valid == null || valid.isBlank() || "Card".equals(valid)) {
            return null;
        }
        String[] parts = valid.split(",");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String token = part.trim();
            int dot = token.indexOf('.');
            if (dot >= 0) {
                token = token.substring(0, dot);
            }
            if (token.isEmpty() || token.equals("Card")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" or ");
            }
            out.append(token.toLowerCase());
        }
        return out.length() == 0 ? null : out.toString();
    }

}
