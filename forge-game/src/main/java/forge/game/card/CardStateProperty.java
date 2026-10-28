package forge.game.card;

import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.CardTraitBase;
import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityColorlessDamageSource;
import forge.game.trigger.Trigger;
import forge.util.Expressions;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

public class CardStateProperty {
    public static boolean hasProperty(CardState cardState, Player sourceController, Card source, String property, CardTraitBase spellAbility) {
        boolean withSource = property.endsWith("Source");
        final ColorSet colors;
        if (withSource && StaticAbilityColorlessDamageSource.colorlessDamageSource(cardState)) {
            colors = ColorSet.C;
        } else {
            colors = cardState.getCard().getColor(cardState);
        }

        final CardTypeView type = cardState.getTypeWithChanges();
        if (property.contains("White") || property.contains("Blue") || property.contains("Black")
                || property.contains("Red") || property.contains("Green")) {
            boolean mustHave = !property.startsWith("non");
            final String colorName = property.substring(mustHave ? 0 : 3, property.length() - (withSource ? 6 : 0));

            int desiredColor = MagicColor.fromName(colorName);
            boolean hasColor = colors.hasAnyColor(desiredColor);
            return mustHave == hasColor;
        } else if (property.contains("Colorless")) {
            boolean non = property.startsWith("non");
            return non != colors.isColorless();
        } else if (property.startsWith("MultiColor")) {
            // ... Card is multicolored
            return colors.isMulticolor();
        } else if (property.startsWith("EnemyColor")) {
            if (colors.countColors() != 2) {
                return false;
            }
            // i want only enemy colors
            for (final byte pair : Arrays.copyOfRange(MagicColor.COLORPAIR, 5, 10)) {
                if (colors.hasExactlyColor(pair)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("AllColors")) {
            return colors.isAllColors();
        } else if (property.startsWith("MonoColor")) {
            return colors.isMonoColor();
        } else if (property.startsWith("ChosenColor")) {
            return source.hasChosenColor() && colors.hasAnyColor(MagicColor.fromName(source.getChosenColor()));
        } else if (property.startsWith("AnyChosenColor")) {
            return source.hasChosenColor()
                    && colors.hasAnyColor(ColorSet.fromNames(source.getChosenColors()).getColor());
        } else if (property.equals("AssociatedWithChosenColor")) {
            final String color = source.getChosenColor();
            switch (color) {
                case "white":
                    return type.hasSubtype("Plains");
                case "blue":
                    return type.hasSubtype("Island");
                case "black":
                    return type.hasSubtype("Swamp");
                case "red":
                    return type.hasSubtype("Mountain");
                case "green":
                    return type.hasSubtype("Forest");
                default:
                    return false;
            }
        } else if (property.equals("prepared")) {
            return cardState.getStateName() == CardStateName.PreparedSpell;
        } else if (property.equals("Worthy")) {
            return cardState.isWorthy();
        } else if (property.equals("Outlaw")) {
            return type.isOutlaw();
        } else if (property.equals("Party")) {
            return type.isParty();
        } else if (property.startsWith("non")) {
            // ... Other Card types
            return !type.hasStringType(property.substring(3));
        } else if (property.equals("CostsPhyrexianMana")) {
            return cardState.getManaCost().hasPhyrexian();
        } else if (property.equals("CostsHybridMana")) {
            return cardState.getManaCost().hasHybrid();
        } else if (property.startsWith("HasSVar")) {
            final String svar = property.substring(8);
            return cardState.hasSVar(svar);
        } else if (property.equals("ChosenType")) {
            return matchesChosenType(type, source.getChosenType(), cardState.getCard());
        } else if (property.equals("IsNotChosenType")) {
            return !matchesChosenType(type, source.getChosenType(), cardState.getCard());
        } else if (property.equals("ChosenType2")) {
            return matchesChosenType(type, source.getChosenType2(), cardState.getCard());
        } else if (property.equals("NotedType")) {
            Card card = cardState.getCard();
            for (String s : source.getNotedTypes()) {
                if (matchesChosenType(type, s, card)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("hasAbility")) {
            String valid = property.substring(11);
            for (final SpellAbility sa : cardState.getSpellAbilities()) {
                if (sa.isValid(valid, sourceController, source, spellAbility)) {
                    return true;
                }
            }
            return false;
        } else if (property.equals("hasManaAbility")) {
            if (!cardState.getManaAbilities().isEmpty()) {
                return true;
            }
            for (final Trigger trig : cardState.getTriggers()) {
                SpellAbility sa = trig.getOverridingAbility();
                if (sa != null) {
                    if (!sa.isTrigger()) {
                        sa.setTrigger(trig);
                    }
                    if (sa.isManaAbility()) {
                        return true;
                    }
                }
            }
            return false;
        } else if (property.equals("hasNonManaActivatedAbility")) {
            for (final SpellAbility sa : cardState.getNonManaAbilities()) {
                if (sa.isActivatedAbility()) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("cmc")) {
            String rhs = property.substring(5);
            int y = cardState.getManaCost().getCMC();
            int x = AbilityUtils.calculateAmount(source, rhs, spellAbility);

            return Expressions.compare(y, property, x);
        }

        return type.hasStringType(property);
    }

    /**
     * Chosen creature type, including changeling / all-creature-types.
     * Deck filters may skip changelings when listing types; in play they match every type.
     */
    static boolean matchesChosenType(CardTypeView type, String chosenType, Card card) {
        if (chosenType == null || chosenType.isEmpty()) {
            return false;
        }
        if (chosenType.startsWith("Non")) {
            return !matchesPositiveType(type, StringUtils.capitalize(chosenType.substring(3)), card);
        }
        return matchesPositiveType(type, chosenType, card);
    }

    private static boolean matchesPositiveType(CardTypeView type, String chosenType, Card card) {
        if (type != null && type.hasStringType(chosenType)) {
            return true;
        }
        CardTypeView live = card == null ? null : card.getType();
        if (live != null && live != type && live.hasStringType(chosenType)) {
            return true;
        }
        if (!CardType.isACreatureType(chosenType)) {
            return false;
        }
        CardTypeView creatureType = live != null ? live : type;
        boolean creature = creatureType != null && (creatureType.isCreature() || creatureType.isKindred());
        if (!creature && card != null) {
            creature = card.isCreature() || card.isKindred();
        }
        if (!creature) {
            return false;
        }
        if (creatureType != null && creatureType.hasAllCreatureTypes()) {
            return true;
        }
        return card != null && card.hasKeyword("Changeling");
    }
}