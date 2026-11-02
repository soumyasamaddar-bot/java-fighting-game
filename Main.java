import java.util.*;

enum Card {
    STRIKE,
    BLAST,
    QUIRK,
    SPMOVE,
    ULT
}

enum NeutralAttack {
    TACKLE,
    QUICK_ATTACK
}

enum AttackRange {
    MELEE,
    LONG_RANGE,
    ANY
}

enum CharacterColor {
    RED,
    YELLOW,
    PURPLE,
    GREEN,
    BLUE
}

/* =========================
   MAIN DRIVER
   ========================= */
public class Main { 
    static int timer = 0;
    static int lastDrawTime = 0;

    // ---- Draw Speed ----
    // Each team has a draw meter that fills a little every tick; a card arrives
    // whenever the meter fills. Draw Speed picks how many ticks one card takes,
    // so it can never stop card generation. Slowest is -6 (40 ticks per card);
    // fastest is +5 (1 card every tick, the most the game can deliver).
    //
    // *** EDIT THE NUMBERS IN DRAW_TICKS_PER_CARD TO TUNE THIS ***
    //   speed:   -6  -5  -4  -3  -2  -1   0  +1  +2   +3    +4  +5
    //   ticks:   40  33  27  22  18  15  12  10   5  3.3  1.83   1
    static final int DRAW_PROGRESS_PER_CARD = 100000;   // leave this alone
    static final int MIN_DRAW_SPEED = -6;               // slowest speed in the table

    // Ticks needed to draw ONE card. Order: speed -6, -5, -4, -3, -2, -1, 0, +1, +2, +3, +4, +5.
    // Decimals are fine. Bigger = slower. Speeds outside the table use the first/last value,
    // so anything above +5 is still 1 card per tick.
    static final double[] DRAW_TICKS_PER_CARD = { 40, 33, 27, 22, 18, 15, 12, 10, 5, 10.0 / 3, 1.83, 1 };

    static int drawProgressPerTick(int speed) {
        int index = speed - MIN_DRAW_SPEED;
        index = Math.max(0, Math.min(index, DRAW_TICKS_PER_CARD.length - 1));
        // Round up so a card arrives on its listed tick, never one tick late.
        return (int) Math.ceil(DRAW_PROGRESS_PER_CARD / DRAW_TICKS_PER_CARD[index] - 1e-9);
    }
    static final int MAX_TIMER = 240;
    static final String SEASON_NAME = "Warriors Beyond Limits!!";   // change this each season
    static final double NEUTRAL_ATTACK_CHANCE = 0.08;
    static final double NEUTRAL_TACKLE_CHANCE = 0.20;
    static final double NEUTRAL_GUARD_CHANCE = 0.35;
    static boolean stun=false;
    static boolean isAlive(Fighter f) {
        return f != null && f.hp > 0;
    }

    static boolean colorHasAdvantage(CharacterColor attackerColor,
                                     CharacterColor defenderColor) {
        if (attackerColor == null || defenderColor == null
            || attackerColor == defenderColor) {
            return false;
        }

        int nextColor = (attackerColor.ordinal() + 1)
            % CharacterColor.values().length;
        return nextColor == defenderColor.ordinal();
    }

    static double colorDamageMultiplier(Fighter attacker, Fighter defender) {
        if (attacker == null || defender == null
            || attacker.color == null || defender.color == null
            || attacker.color == defender.color) {
            return 1.0;
        }

        if (colorHasAdvantage(attacker.color, defender.color)) {
            defender.updateTimedBuffs();
            if (defender.colorImmune) {
                return 1.0;
            }
            return attacker.superiorColor ? 1.75 : 1.50;
        }

        if (colorHasAdvantage(defender.color, attacker.color)) {
            attacker.updateTimedBuffs();
            if (attacker.colorImmune) {
                return 1.0;
            }
            return 0.75;
        }

        return 1.0;
    }

    static String colorLabel(Fighter fighter) {
        if (fighter == null || fighter.color == null) {
            return "UNKNOWN";
        }
        return fighter.color.name() + (fighter.superiorColor ? "+" : "");
    }

    static double applyChipDamage(Fighter target, Fighter attacker, double amount) {
        if (!isAlive(target) || !Double.isFinite(amount) || amount <= 0) {
            return 0;
        }

        amount *= colorDamageMultiplier(attacker, target);
        double hpBefore = target.hp;
        target.hp = Math.max(1, target.hp - amount);
        double damageDealt = hpBefore - target.hp;

        if (attacker != null && attacker.myTeam != null) {
            attacker.myTeam.addDamage(damageDealt);
        }

        return damageDealt;
    }

    static boolean hasPlayableCard(Teams team, Fighter fighter) {
        if (team == null || fighter == null) {
            return false;
        }

        for (Card card : team.hand) {
            if (card != null
                && card != Card.QUIRK
                && card != Card.ULT
                && fighter.ki >= fighter.kiRequired(card)) {
                return true;
            }
        }
        return false;
    }

    static int chooseSwitchCandidate(Teams team, Fighter opponent) {
        if (team == null || opponent == null || !team.canSwitchNow()) {
            return -1;
        }

        Fighter active = team.getActive();
        if (!isAlive(active)) {
            return -1;
        }

        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 1; i < team.fighters.length; i++) {
            Fighter ally = team.fighters[i];
            if (!isAlive(ally) || ally.switchCD > 0) {
                continue;
            }

            double score = (ally.hp / Math.max(1.0, ally.maxhp)) * 100.0;

            if (hasPlayableCard(team, ally)) {
                score += 20;
            }

            // Do not throw away a healthy active fighter for a weaker ally.
            if (active.hp / Math.max(1.0, active.maxhp)
                    > ally.hp / Math.max(1.0, ally.maxhp)) {
                score -= 15;
            }

            // A low-ki opponent is less able to punish a tactical switch.
            if (opponent.ki < 25) {
                score += 8;
            }

            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    static boolean shouldSwitchForCondition(Teams team, Teams opponentTeam) {
        if (team == null || opponentTeam == null || !team.canSwitchNow()) {
            return false;
        }

        Fighter active = team.getActive();
        Fighter opponent = opponentTeam.getActive();
        if (!isAlive(active) || !isAlive(opponent)) {
            return false;
        }

        double healthRatio = active.hp / Math.max(1.0, active.maxhp);
        boolean disabled = active.isParalyzed || active.isFaint;
        boolean noPlayableCard = !hasPlayableCard(team, active);
        boolean opponentPressuring = opponent.ki >= opponent.kiRequired(Card.SPMOVE)
            || opponent.ultunlocked;

        // Switching is deliberate: it happens for danger or a disabled
        // active fighter—not on every action.
        if (healthRatio <= 0.35 || disabled) {
            return true;
        }
        return noPlayableCard && opponentPressuring && healthRatio <= 0.50;
    }

    static void considerTacticalSwitch(Teams team, Teams opponentTeam) {
        if (!shouldSwitchForCondition(team, opponentTeam)) {
            return;
        }

        int candidate = chooseSwitchCandidate(team, opponentTeam.getActive());
        if (candidate >= 0) {
            System.out.println("🧠 " + team.teamName
                + " evaluates the battlefield and chooses a tactical switch.");
            team.switchFighter(candidate, opponentTeam.getActive());
        }
    }

    // Adds one tick of draw progress for the team's active fighter, using that
    // fighter's current effective Draw Speed (base or temporary buff, minus
    // any stacked penalty).
    static void accrueDrawProgress(Teams team) {
        if (team == null) {
            return;
        }

        Fighter active = team.getActive();
        if (active != null) {
            team.addDrawProgress(drawProgressPerTick(active.getEffectiveDrawSpeed()));
        }
    }

    // Catches the draw meters up to the current timer, one tick at a time.
    static void advanceDrawTimer(Teams first, Teams second) {
        while (timer - lastDrawTime >= 1) {
            lastDrawTime++;
            accrueDrawProgress(first);
            accrueDrawProgress(second);
        }
    }
    static void advanceComboWindow(Teams firstTeam, Teams secondTeam, int counts) {
        if (counts <= 0) {
            return;
        }

        for (int i = 0; i < counts && timer < MAX_TIMER; i++) {
            timer++;

            if (firstTeam != null) {
                firstTeam.reduceSwitchCooldown(1);
                Fighter active = firstTeam.getActive();
                if (isAlive(active)) {
                    active.recoverKi(1);
                    active.recoverVanish(1);
                    active.updateTimedStatuses();
                }
            }
            if (secondTeam != null) {
                secondTeam.reduceSwitchCooldown(1);
                Fighter active = secondTeam.getActive();
                if (isAlive(active)) {
                    active.recoverKi(1);
                    active.recoverVanish(1);
                    active.updateTimedStatuses();
                }
            }

            advanceDrawTimer(firstTeam, secondTeam);
        }
    }

    static void forceSwap(Teams t, Fighter attacker) {

    if (t == null) {
        return;
    }

    ArrayList<Integer> options = new ArrayList<>();

    for (int i = 1; i < 3; i++) {
        if (t.fighters[i] != null && t.fighters[i].hp > 0) {
            options.add(i);
        }
    }

    if (options.isEmpty()) {

        System.out.println(">>> " + t.teamName + " has no more fighters!");

        t.fighters[0] = null;
        return;
    }

    int chosen = options.get(
        (int)(Math.random() * options.size())
    );

    Fighter dead = t.fighters[0];
    if (dead != null) {
        dead.switchOut();
    }

    t.fighters[0] = t.fighters[chosen];
    t.fighters[chosen] = dead;

    System.out.println(">>> " + t.teamName +
        " sends in " + t.fighters[0].name + "!");

    if (attacker != null) {
        t.fighters[0].switchIn(attacker);
    }

    for (int i = 0; i < 4; i++) {
        if (t.hand[i] == Card.ULT) {
            t.hand[i] = null;
            t.refillSlot(i);
        }
    }
}
    
    static boolean checkDeathAndHandle(Teams defendingTeam, Fighter attacker) {

    if (defendingTeam == null) {
        return true;
    }

    Fighter defender = defendingTeam.getActive();

    if (defender == null) {
        forceSwap(defendingTeam, attacker);
        defender = defendingTeam.getActive();
        if (!isAlive(defender)) {
            System.out.println(">>> Team " + defendingTeam.teamName + " is fully defeated!");
            return true;
        }
        System.out.println(">>> Momentum reset!");
        return true;
    }

    if (!isAlive(defender)) {
        System.out.println("💀 [" + defendingTeam.teamName + "] " + defender.name + " is defeated!");
        Fighter victim = defender;

        forceSwap(defendingTeam, attacker);
        defender = defendingTeam.getActive();

        // Notify the killer AFTER the swap, so the replacement's switchIn can't overwrite the bonus.
        if (attacker != null && victim != null && attacker.myTeam != defendingTeam) {
            attacker.onKill(victim);
        }

        if (!isAlive(defender)) {
            System.out.println(">>> Team " + defendingTeam.teamName + " is fully defeated!");
            return true; // match over or no units left
        }

        System.out.println(">>> Momentum reset!");
        return true; // stop combo
    }

    return false; // continue normally
}

    // =========================
    // LIMIT BREAK SUMMON SYSTEM
    // =========================
    // ----- COINS & PITY (all numbers are yours to tune) -----
    static final int START_COINS = 6000;      // a team that has never played starts with this
    static final int SUMMON_COST = 100;       // price of one summon
    static final int BUNDLE_SIZE = 10;        // every full bundle of this many summons...
    static final int BUNDLE_DISCOUNT = 100;   // ...is this many coins cheaper (10 summons = 900)
    static final int PITY_LIMIT = 30;         // hard pity: headliner guaranteed on this many summons without one
    static final int WIN_COINS = 1000;         // coins earned after a match
    static final int LOSS_COINS = 500;
    static final int DRAW_COINS = 200;
    static final int EXTRA_COPY_COINS = 20;   // each copy beyond the limit break cap turns into this many coins
    static final int LB_MAX_COPIES = 7;       // limit break cap for now

    // Per-character choices, keyed by the fighter name used in the pool in main().
    // Anything not listed here uses the automatic rule below.
    //   LB_PERK3 (table below): 3-copy +30% goes to "DAMAGE" or "DEFENSE"
    //   LB_HP4_OVERRIDE:   4-copy HP perk, 0.10 .. 0.15
    // Example:  LB_HP4_OVERRIDE.put("Cell", 0.15);
    // FIXED 3-copy choice for every character: "DAMAGE" or "DEFENSE". Edit any line you like.
    // (Keys are the fighter names used in the pool in main().)
    static final Map<String, String> LB_PERK3 = new HashMap<>();
    static {
        LB_PERK3.put("ZenkaiGoku", "DAMAGE");
        LB_PERK3.put("ZenkaiVegeta", "DAMAGE");
        LB_PERK3.put("UIGoku", "DAMAGE");
        LB_PERK3.put("ZenkaiSSBEVegeta", "DAMAGE");
        LB_PERK3.put("Gohan", "DAMAGE");
        LB_PERK3.put("Vegito", "DAMAGE");
        LB_PERK3.put("Superbaby2", "DEFENSE");
        LB_PERK3.put("RoseGb", "DAMAGE");
        LB_PERK3.put("Angel Goku", "DAMAGE");
        LB_PERK3.put("Majin Vegeta", "DAMAGE");
        LB_PERK3.put("SOHTrunks", "DEFENSE");
        LB_PERK3.put("BaseVegito", "DAMAGE");
        LB_PERK3.put("FusionZamasu", "DEFENSE");
        LB_PERK3.put("SSJ4Gogeta", "DAMAGE");
        LB_PERK3.put("Cooler", "DAMAGE");
        LB_PERK3.put("Frieza", "DEFENSE");
        LB_PERK3.put("Janemba", "DAMAGE");
        LB_PERK3.put("SSJB Vegeta", "DAMAGE");
        LB_PERK3.put("Majin Vegeta2", "DAMAGE");
        LB_PERK3.put("GokuBlackRoseTyrant", "DAMAGE");
        LB_PERK3.put("MUIGOKU", "DAMAGE");
        LB_PERK3.put("Ssj3Goku", "DAMAGE");
        LB_PERK3.put("SsjbGoku", "DAMAGE");
        LB_PERK3.put("Cell", "DAMAGE");
        LB_PERK3.put("Beerus", "DAMAGE");
        LB_PERK3.put("UltimateGohan", "DAMAGE");
        LB_PERK3.put("Buuhan", "DAMAGE");
        LB_PERK3.put("MetalCooler", "DAMAGE");
    }
    static final Map<String, Double> LB_HP4_OVERRIDE = new HashMap<>();

    // The 3-copy +30% goes exactly where the table above says. A character that is not in
    // the table (a new one you add later) gets DAMAGE until you add a line for it.
    static boolean lbPerk3GoesToDamage(Fighter f) {
        String choice = LB_PERK3.get(f.name);
        if (choice == null) return true;
        return choice.equalsIgnoreCase("DAMAGE");
    }

    // Automatic rule: 15% for 6.0M HP, sliding down to 10% for 6.8M HP and above.
    static double lbHp4Pct(Fighter f) {
        Double o = LB_HP4_OVERRIDE.get(f.name);
        double pct = (o != null) ? o : 0.15 - (f.maxhp - 6000000.0) / 800000.0 * 0.05;
        pct = Math.max(0.10, Math.min(0.15, pct));
        return Math.round(pct * 100.0) / 100.0;
    }

    // Tiers compound (each one multiplies the result of the one before), applied once per fighter.
    static void applyLimitBreak(Fighter f, int owned) {
        int c = Math.min(owned, LB_MAX_COPIES);
        double dmg = 1.0, def = 1.0, hp = 1.0;
        if (c >= 1) { dmg *= 1.10; def *= 1.10; }
        if (c >= 2) { dmg *= 1.30; def *= 1.30; }
        if (c >= 3) { if (lbPerk3GoesToDamage(f)) dmg *= 1.30; else def *= 1.30; }
        if (c >= 4) { dmg *= 1.10; def *= 1.10;hp *= 1.0 + lbHp4Pct(f); }
        if (c >= 5) { dmg *= 1.20; def *= 1.20; hp *= 1.05; }
        if (c >= 6) { if (lbPerk3GoesToDamage(f)) dmg *= 1.30; else def *= 1.30; }
        if (c >= 7) { dmg *= 1.50; def *= 1.50; hp *= 1.20; }
        double hp0 = f.maxhp;
        f.lbDamage = dmg;
        f.lbDefense = def;
        f.lbHp = hp;
        f.lbCopies = c;
        f.maxhp = hp0 * hp;
        f.hp = f.maxhp;
    }

    // =========================
    // BANNERS
    // =========================
    static final int ROLE_NORMAL = 0, ROLE_FEATURED = 1, ROLE_HEADLINER = 2;

    static class Banner {
        final String name, description;
        final boolean allPool;   // true: every pool fighter, each with its own rate (the standard banner)
        final Map<String, Double> weights = new LinkedHashMap<>();  // fighter name -> weight (percent)
        final Map<String, Integer> roles = new HashMap<>();         // fighter name -> ROLE_*

        Banner(String name, String description, boolean allPool) {
            this.name = name; this.description = description; this.allPool = allPool;
        }
        Banner add(String fighterName, double weight, int role) {
            weights.put(fighterName, weight);
            roles.put(fighterName, role);
            return this;
        }
        double weightOf(Fighter f) {
            if (f == null) return 0;
            if (allPool) return (Double.isFinite(f.rate) && f.rate > 0) ? f.rate : 0;
            Double w = weights.get(f.name);
            return (w == null || !Double.isFinite(w) || w <= 0) ? 0 : w;
        }
        int roleOf(Fighter f) {
            Integer r = roles.get(f.name);
            return r == null ? ROLE_NORMAL : r;
        }
    }

    // Add a banner = copy a block below. The weights are relative, so they don't have to add up
    // to exactly 100 (they are scaled to percentages automatically).
    static final List<Banner> BANNERS = new ArrayList<>();
    static {
        BANNERS.add(new Banner("Standard Summon",
            "Every character, using the normal pool rates.", true));

        BANNERS.add(new Banner("God of Destruction",
            "Beerus has arrived! SSJB Vegeta, SSJB Goku and MUI Goku are boosted.", false)
            .add("Beerus",      5,  ROLE_HEADLINER)
            .add("SSJB Vegeta", 15, ROLE_FEATURED)
            .add("SsjbGoku",    15, ROLE_FEATURED)
            .add("MUIGOKU",     15, ROLE_FEATURED)
            .add("UIGoku",      25, ROLE_NORMAL)
            .add("ZenkaiSSBEVegeta",  25, ROLE_NORMAL));

        BANNERS.add(new Banner("Shattering the Heavens",
            "BaseVegito has arrived! SOHTrunks, GokuBlackRoseTyrant and Cell are boosted.", false)
            .add("BaseVegito",          5,  ROLE_HEADLINER)
            .add("SOHTrunks",           15, ROLE_FEATURED)
            .add("GokuBlackRoseTyrant", 15, ROLE_FEATURED)
            .add("Cell",                15, ROLE_FEATURED)
            .add("RoseGb",              25, ROLE_NORMAL)
            .add("FusionZamasu",        25, ROLE_NORMAL));

        BANNERS.add(new Banner("Fusion Warrior",
            "SSJ4Gogeta has arrived! Vegito, FusionZamasu and Angel Goku are boosted.", false)
            .add("SSJ4Gogeta",   5,  ROLE_HEADLINER)
            .add("Vegito",       15, ROLE_FEATURED)
            .add("FusionZamasu", 15, ROLE_FEATURED)
            .add("Angel Goku",   15, ROLE_FEATURED)
            .add("Gohan",        25, ROLE_NORMAL)
            .add("Janemba",      25, ROLE_NORMAL));

        
        BANNERS.add(new Banner("Saiyan Pride",
        "Majin Vegeta2 has arrived! Majin Vegeta, SSJB Vegeta and ZenkaiVegeta are boosted.", false)
        .add("Majin Vegeta2", 5, ROLE_HEADLINER)
        .add("Majin Vegeta", 15, ROLE_FEATURED)
        .add("SSJB Vegeta", 15, ROLE_FEATURED)
        .add("ZenkaiVegeta", 15, ROLE_FEATURED)
        .add("Gohan", 25, ROLE_NORMAL)
        .add("Angel Goku", 25, ROLE_NORMAL));

        
        BANNERS.add(new Banner("Don't Die Vegeta",
        "SSJ3 Goku has arrived! Vegeta says, \"Trust me, I won't go down!\" Cell, Zenkai Goku, SSJB Goku and Zenkai Vegeta are boosted.", false)
        .add("Ssj3Goku", 5, ROLE_HEADLINER)
        .add("Cell", 15, ROLE_FEATURED)
        .add("ZenkaiGoku", 15, ROLE_FEATURED)
        .add("SsjbGoku", 15, ROLE_FEATURED)
        .add("ZenkaiVegeta", 15, ROLE_FEATURED)
        .add("Angel Goku", 25, ROLE_NORMAL)
        .add("UIGoku", 25, ROLE_NORMAL));

        BANNERS.add(new Banner("Powerful Opponents",
        "Frieza has arrived! Fusion Zamasu, Goku Black Tyrant and Cooler are boosted.", false)
        .add("Frieza", 5, ROLE_HEADLINER)
        .add("FusionZamasu", 15, ROLE_FEATURED)
        .add("GokuBlackRoseTyrant", 15, ROLE_FEATURED)
        .add("Cooler", 15, ROLE_FEATURED)
        .add("RoseGb", 25, ROLE_NORMAL)
        .add("Superbaby2", 25, ROLE_NORMAL));

        BANNERS.add(new Banner("Rising Shining Warrior",
            "UltimateGohan has arrived! Ssj3Goku, Cooler and Majin Vegeta2 are boosted.", false)
            .add("UltimateGohan", 5,  ROLE_HEADLINER)
            .add("Ssj3Goku",      15, ROLE_FEATURED)
            .add("Cooler",        15, ROLE_FEATURED)
            .add("Majin Vegeta2", 15, ROLE_FEATURED)
            .add("Gohan",         25, ROLE_NORMAL)
            .add("ZenkaiGoku",    25, ROLE_NORMAL));


        BANNERS.add(new Banner("The Ultimate Majin Awakening",
            "Buuhan has arrived! Cell, Cooler and Majin Vegeta2 are boosted.", false)
            .add("Buuhan", 5,  ROLE_HEADLINER)
            .add("Cell",      15, ROLE_FEATURED)
            .add("Cooler",      15, ROLE_FEATURED)
            .add("Majin Vegeta2", 15, ROLE_FEATURED)
            .add("Gohan",         25, ROLE_NORMAL)
            .add("Janemba",         25, ROLE_NORMAL)
            .add("FusionZamasu", 25, ROLE_NORMAL)
            .add("Superbaby2",25,ROLE_NORMAL)
            .add("ZenkaiVegeta",    25, ROLE_NORMAL));

        BANNERS.add(new Banner("The Infinite Army Awakens",
            "MetalCooler has arrived! Cell, Cooler and FusionZamasu are boosted.", false)
            .add("MetalCooler", 5,  ROLE_HEADLINER)
            .add("Cooler",      15, ROLE_FEATURED)
            .add("FusionZamasu", 25, ROLE_FEATURED)
            .add("Janemba",      25, ROLE_NORMAL)
            .add("Superbaby2",25,ROLE_NORMAL)
            .add("RoseGb",    25, ROLE_NORMAL));
    }

    // =========================
    // SAVE DATA: coins and pity counters, kept between matches
    // =========================
    static class SummonSave {
        static final String FILE = "summon_save.properties";
        static final Properties props = new Properties();
        static boolean loaded = false;

        static void load() {
            if (loaded) return;
            loaded = true;
            try (java.io.InputStream in = new java.io.FileInputStream(FILE)) {
                props.load(in);
            } catch (java.io.FileNotFoundException e) {
                // first run: nothing saved yet
            } catch (java.io.IOException | RuntimeException e) {
                System.out.println("(Could not read " + FILE + ", starting fresh.)");
            }
        }
        static void save() {
            try (java.io.OutputStream out = new java.io.FileOutputStream(FILE)) {
                props.store(out, "Summon coins and pity counters");
            } catch (java.io.IOException e) {
                System.out.println("(Could not write " + FILE + ": progress will not be kept.)");
            }
        }
        static int getInt(String key, int def) {
            load();
            try {
                return Math.max(0, Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim()));
            } catch (NumberFormatException e) {
                return def;
            }
        }
        static void setInt(String key, int value) {
            load();
            props.setProperty(key, String.valueOf(Math.max(0, value)));
        }
        // Wipes every team's coins and pity counters; next time they start from the defaults again.
        static void resetAll() {
            load();
            props.clear();
            save();
        }
        static int coins(String team) { return getInt(team + ".coins", START_COINS); }
        static void setCoins(String team, int v) { setInt(team + ".coins", v); }
        static int pity(String team, Banner b) { return getInt(team + ".pity." + b.name, 0); }
        static int copies(String team, String fighter) { return getInt(team + ".inv." + fighter, 0); }
        static void setCopies(String team, String fighter, int v) {
            load();
            String key = team + ".inv." + fighter;
            if (v <= 0) props.remove(key); else props.setProperty(key, String.valueOf(v));
        }
        static void setPity(String team, Banner b, int v) { setInt(team + ".pity." + b.name, v); }
    }

    // =========================
    // TIER BOOSTS
    // =========================
    // Extra damage and defense for weaker characters, so they can compete with stronger ones.
    // It is separate from limit break and multiplies with it.  0.50 means +50% (x1.50).
    static final double TIER_W_DAMAGE = 5.00, TIER_W_DEFENSE = 4.50;   // W tier
    static final double TIER_X_DAMAGE = 4.00, TIER_X_DEFENSE = 3.50;   // X tier
    static final double TIER_Z_DAMAGE = 3.00, TIER_Z_DEFENSE = 2.50;   // Z tier
    static final double TIER_S_DAMAGE = 2.40, TIER_S_DEFENSE = 2.00;   // S tier
    static final double TIER_A_DAMAGE = 1.50, TIER_A_DEFENSE = 1.20;   // A tier
    static final double TIER_B_DAMAGE = 0.80, TIER_B_DEFENSE = 0.50;   // B tier
    static final double TIER_C_DAMAGE = 0.00, TIER_C_DEFENSE = 0.00;   // C tier: no boost

    // FEATURED BOOST: the headliner of any banner (ROLE_HEADLINER in BANNERS above) gets this on TOP of its
    // strength tier (they multiply). It follows the banners automatically: change the headliner of a banner
    // and the boost moves with it - no need to touch the tier table.   0.30 means +30% (x1.30).
    static final double FEATURED_DAMAGE = 0.30, FEATURED_DEFENSE = 0.30;

    // Strength tier of each character: "Z", "S", "A", "B" or "C" (no boost). Pick it from how strong the
    // character really is - NOT from whether it is a headliner (that is the Featured Boost, above).
    // A character that is not listed gets no tier boost.  Names are the ones used in the pool.
    // Banner headliners (Beerus, BaseVegito, SSJ4Gogeta, ...) are listed here with their STRENGTH tier too; the
    // Featured Boost is added on top of it automatically, because they headline a banner.
    static final Map<String, String> CHARACTER_TIER = new HashMap<>();
    static {
        CHARACTER_TIER.put("ZenkaiSSBEVegeta", "A");
        CHARACTER_TIER.put("RoseGb", "W");
        CHARACTER_TIER.put("Gohan", "Z");
        CHARACTER_TIER.put("ZenkaiGoku", "Z");
        CHARACTER_TIER.put("Janemba", "X");
        CHARACTER_TIER.put("Superbaby2", "Z");
        CHARACTER_TIER.put("Angel Goku", "X");
        CHARACTER_TIER.put("ZenkaiVegeta", "Z");
        CHARACTER_TIER.put("UIGoku", "Z");
        CHARACTER_TIER.put("GokuBlackRoseTyrant", "S");
        CHARACTER_TIER.put("SSJ4Gogeta", "A");
        CHARACTER_TIER.put("Majin Vegeta", "X");
        CHARACTER_TIER.put("Cooler", "W");
        CHARACTER_TIER.put("Frieza", "S");
        CHARACTER_TIER.put("SSJB Vegeta", "S");
        CHARACTER_TIER.put("Vegito", "S");
        CHARACTER_TIER.put("FusionZamasu", "A");
        CHARACTER_TIER.put("Majin Vegeta2", "A");
        CHARACTER_TIER.put("SsjbGoku", "A");
        CHARACTER_TIER.put("BaseVegito", "B");
        CHARACTER_TIER.put("Beerus", "B");
        CHARACTER_TIER.put("SOHTrunks", "A");
        CHARACTER_TIER.put("MUIGOKU", "B");
        CHARACTER_TIER.put("Cell", "B");
        CHARACTER_TIER.put("Ssj3Goku", "A");
        CHARACTER_TIER.put("UltimateGohan", "B");
        CHARACTER_TIER.put("Buuhan", "B");
        CHARACTER_TIER.put("MetalCooler", "B");
    }

    static String tierName(String t) {
        switch (t) {
            case "W": return "W tier";
            case "X": return "X tier";
            case "Z": return "Z tier";
            case "S": return "S tier";
            case "A": return "A tier";
            case "B": return "B tier";
            case "C": return "C tier";
            default:  return "";
        }
    }

    // True if this character headlines at least one banner (so it gets the Featured Boost).
    static boolean isHeadliner(String fighterName) {
        for (Banner b : BANNERS) {
            Integer r = b.roles.get(fighterName);
            if (r != null && r == ROLE_HEADLINER && b.weights.containsKey(fighterName)) return true;
        }
        return false;
    }

    // Call once per fighter, AFTER applyLimitBreak (which sets lbDamage / lbDefense).
    // Final multiplier = limit break x strength tier x Featured Boost (headliners only).
    static void applyTierBoost(Fighter f) {
        if (f.tierApplied) return;
        String t = CHARACTER_TIER.get(f.name);
        if ("F".equals(t)) t = "C";                 // old tables: "F" is gone, the featured boost comes from the banners now
        double d = 0.0, df = 0.0;
        if (t != null) {
            switch (t) {
                case "W": d = TIER_W_DAMAGE; df = TIER_W_DEFENSE; break;
                case "X": d = TIER_X_DAMAGE; df = TIER_X_DEFENSE; break;
                case "Z": d = TIER_Z_DAMAGE; df = TIER_Z_DEFENSE; break;
                case "S": d = TIER_S_DAMAGE; df = TIER_S_DEFENSE; break;
                case "A": d = TIER_A_DAMAGE; df = TIER_A_DEFENSE; break;
                case "B": d = TIER_B_DAMAGE; df = TIER_B_DEFENSE; break;
                case "C": d = TIER_C_DAMAGE; df = TIER_C_DEFENSE; break;
                default:  t = null; break;          // unknown tier name: treated as "no tier"
            }
        }
        boolean feat = isHeadliner(f.name);
        if (t == null && !feat) return;             // no strength tier and not a headliner: nothing to apply
        f.tier = (t == null) ? "" : t;
        f.tierDamage = 1.0 + d;
        f.tierDefense = 1.0 + df;
        f.featured = feat;
        f.featuredDamage = feat ? 1.0 + FEATURED_DAMAGE : 1.0;
        f.featuredDefense = feat ? 1.0 + FEATURED_DEFENSE : 1.0;
        f.lbDamage *= f.tierDamage * f.featuredDamage;
        f.lbDefense *= f.tierDefense * f.featuredDefense;
        f.tierApplied = true;
    }

    // The inventory is kept between matches, like the coins.
    static void saveInventory(String team, Fighter[] pool, int[] copies) {
        for (int j = 0; j < pool.length; j++) SummonSave.setCopies(team, pool[j].name, copies[j]);
        SummonSave.save();
    }

    static void printSavedInventory(String team, Fighter[] pool, int[] copies) {
        System.out.println("\n" + team + " saved inventory:");
        for (int j = 0; j < pool.length; j++) {
            if (copies[j] > 0) {
                System.out.println("  " + pool[j].name + " [" + colorLabel(pool[j]) + "] x" + copies[j]);
            }
        }
    }

    static int summonCost(int n) {
        return n * SUMMON_COST - (n / BUNDLE_SIZE) * BUNDLE_DISCOUNT;
    }

    static int maxAffordable(int coins) {
        int n = 0;
        while (summonCost(n + 1) <= coins) n++;
        return n;
    }

    // Index in the pool of the banner's headliner, or -1 if the banner has none.
    static int headlinerIndex(Banner b, Fighter[] pool) {
        if (b.allPool) return -1;
        for (int j = 0; j < pool.length; j++) {
            if (b.weightOf(pool[j]) > 0 && b.roleOf(pool[j]) == ROLE_HEADLINER) return j;
        }
        return -1;
    }

    static String readLine(Scanner sc) {
        if (!sc.hasNextLine()) {
            throw new IllegalArgumentException("Summon input ended before the team was complete.");
        }
        return sc.nextLine();
    }

    // Called once at the end of a battle.  winner: 1 = Team 1, 2 = Team 2, 0 = draw.
    static void awardMatchCoins(String team1, String team2, int winner) {
        int c1 = winner == 1 ? WIN_COINS : winner == 2 ? LOSS_COINS : DRAW_COINS;
        int c2 = winner == 2 ? WIN_COINS : winner == 1 ? LOSS_COINS : DRAW_COINS;
        int t1 = SummonSave.coins(team1) + c1;
        int t2 = SummonSave.coins(team2) + c2;
        SummonSave.setCoins(team1, t1);
        SummonSave.setCoins(team2, t2);
        SummonSave.save();
        System.out.println("\n" + team1 + " earns " + c1 + " coins (total " + t1 + ")");
        System.out.println(team2 + " earns " + c2 + " coins (total " + t2 + ")");
    }

    static String roleText(int role) {
        return role == ROLE_HEADLINER ? "HEADLINER" : role == ROLE_FEATURED ? "featured" : "";
    }

    static boolean poolHas(Fighter[] pool, String fighterName) {
        for (Fighter f : pool) if (f != null && f.name.equals(fighterName)) return true;
        return false;
    }

    static Banner chooseBanner(String team, Fighter[] pool, Scanner sc) {
        // Check every banner once so a typo in a name shows up immediately, not mid-summon.
        for (Banner bn : BANNERS) {
            if (!bn.allPool) {
                for (String n : bn.weights.keySet()) {
                    if (!poolHas(pool, n)) {
                        throw new IllegalStateException("Banner \"" + bn.name + "\" lists \"" + n
                            + "\", which is not in the summon pool.");
                    }
                }
            }
            int usable = 0;
            for (Fighter f : pool) if (bn.weightOf(f) > 0) usable++;
            if (usable < 3) {
                throw new IllegalStateException("Banner \"" + bn.name + "\" needs at least 3 different characters.");
            }
        }

        System.out.println("\n=== " + team + ": choose a banner ===");
        for (int i = 0; i < BANNERS.size(); i++) {
            Banner bn = BANNERS.get(i);
            System.out.println("[" + (i + 1) + "] " + bn.name + " - " + bn.description);
            if (headlinerIndex(bn, pool) >= 0) {
                System.out.println("      Pity: " + SummonSave.pity(team, bn) + "/" + PITY_LIMIT
                    + " (headliner guaranteed at " + PITY_LIMIT + " summons without one)");
            }
            double total = 0;
            for (Fighter f : pool) total += bn.weightOf(f);
            if (!bn.allPool) {
                List<Fighter> shown = new ArrayList<>();
                for (Fighter f : pool) if (bn.weightOf(f) > 0) shown.add(f);
                final Banner bnf = bn;
                shown.sort((x, y) -> {
                    int byRole = bnf.roleOf(y) - bnf.roleOf(x);   // headliner, then featured, then the rest
                    return byRole != 0 ? byRole : Double.compare(bnf.weightOf(y), bnf.weightOf(x));
                });
                for (Fighter f : shown) {
                    String rt = roleText(bn.roleOf(f));
                    System.out.printf("      %-20s %5.1f%%  %s%n", f.name, bn.weightOf(f) / total * 100.0, rt);
                }
            }
        }
        while (true) {
            System.out.print("Banner number: ");
            if (!sc.hasNextLine()) {
                throw new IllegalArgumentException("Summon input ended before a banner was chosen.");
            }
            String line = sc.nextLine().trim();
            try {
                int idx = Integer.parseInt(line) - 1;
                if (idx >= 0 && idx < BANNERS.size()) return BANNERS.get(idx);
            } catch (NumberFormatException e) { /* fall through */ }
            System.out.println("Enter a number from the list.");
        }
    }

    static int rollSummon(Fighter[] pool, Banner banner, Random r) {
        double total = 0;
        for (Fighter p : pool) total += banner.weightOf(p);
        if (!(total > 0)) {
            throw new IllegalArgumentException("Banner \"" + banner.name + "\" has no usable characters.");
        }
        double roll = r.nextDouble() * total, cum = 0;
        int last = -1;
        for (int j = 0; j < pool.length; j++) {
            double w = banner.weightOf(pool[j]);
            if (w <= 0) continue;
            last = j;
            cum += w;
            if (roll < cum) return j;
        }
        return last;
    }

    static int distinctOwned(int[] copies) {
        int n = 0;
        for (int c : copies) if (c > 0) n++;
        return n;
    }

    static Teams summonTeam(String name, Fighter[] pool, Scanner sc) {
        if (pool == null || pool.length < 3) {
            throw new IllegalArgumentException("A team requires at least three fighters in the summon pool.");
        }
        if (sc == null) {
            throw new IllegalArgumentException("A scanner is required for the summon phase.");
        }

        Random r = new Random();
        int[] copies = new int[pool.length];
        int coins = SummonSave.coins(name);
        boolean firstRound = true;
        for (int j = 0; j < pool.length; j++) copies[j] = SummonSave.copies(name, pool[j].name);

        // ---- 1) SUMMON (optional when you already own at least 3 different characters) ----
        System.out.println("\n=== " + name + " Summon Phase ===");
        boolean doSummon = true;
        if (distinctOwned(copies) > 0) {
            printSavedInventory(name, pool, copies);
            System.out.println("\n" + name + " coins: " + coins);
        }
        if (distinctOwned(copies) >= 3) {
            System.out.print("Summon this match? (y/n): ");
            if (!readLine(sc).trim().toLowerCase().startsWith("y")) doSummon = false;
        } else if (distinctOwned(copies) > 0) {
            System.out.println("You own fewer than 3 different characters, so you need to summon.");
        }
        while (doSummon) {
            System.out.println("\n" + name + " coins: " + coins + "   (1 summon = " + SUMMON_COST + ", "
                + BUNDLE_SIZE + " summons = " + summonCost(BUNDLE_SIZE) + ")");
            if (coins < SUMMON_COST) {
                System.out.println("Not enough coins for another summon.");
                break;
            }
            if (!firstRound && distinctOwned(copies) >= 3) {
                System.out.print("Summon more? (y/n): ");
                if (!readLine(sc).trim().toLowerCase().startsWith("y")) break;
            }

            Banner banner = chooseBanner(name, pool, sc);
            int maxN = maxAffordable(coins);
            int n = -1;
            while (n < 0) {
                System.out.print("How many summons? (1-" + maxN + ", 0 to stop): ");
                try {
                    int v = Integer.parseInt(readLine(sc).trim());
                    if (v >= 0 && v <= maxN) n = v;
                } catch (NumberFormatException e) { /* ask again */ }
                if (n < 0) System.out.println("Enter a number from 0 to " + maxN + ".");
            }
            if (n == 0) {
                if (distinctOwned(copies) >= 3) break;
                System.out.println("You need at least 3 different characters to form a team - summon more.");
                continue;
            }

            coins -= summonCost(n);
            firstRound = false;
            int pity = SummonSave.pity(name, banner);
            int hl = headlinerIndex(banner, pool);
            boolean autoRest = false;
            System.out.println("\n--- " + banner.name + ": " + n + " summons (coins left: " + coins + ") ---");
            for (int i = 1; i <= n; i++) {
                if (!autoRest) {
                    System.out.println("Press ENTER to summon (" + i + "/" + n + "), or type A to summon the rest...");
                    if (readLine(sc).trim().equalsIgnoreCase("a")) autoRest = true;
                }
                boolean byPity = false;
                int j;
                if (hl >= 0 && pity >= PITY_LIMIT - 1) {
                    j = hl;
                    byPity = true;
                } else {
                    j = rollSummon(pool, banner, r);
                }
                if (j < 0) throw new IllegalArgumentException("Could not select a fighter from the summon pool.");
                copies[j]++;
                if (hl >= 0) pity = (j == hl) ? 0 : pity + 1;
                String rt = roleText(banner.roleOf(pool[j]));
                System.out.println("\u2728 Summoned: " + pool[j].name + " [" + colorLabel(pool[j]) + "]"
                    + (rt.isEmpty() ? "" : "  *" + rt + "*")
                    + (byPity ? "  (PITY!)" : "")
                    + "  (copies: " + copies[j] + ")");
            }
            if (hl >= 0) SummonSave.setPity(name, banner, pity);
            SummonSave.setCoins(name, coins);
            saveInventory(name, pool, copies);
        }

        // A team that could not pay for 3 different characters gets free standard summons so it can still play.
        while (distinctOwned(copies) < 3) {
            System.out.println("(Free bonus summon: you need 3 different characters to form a team)");
            System.out.println("Press ENTER to summon...");
            readLine(sc);
            Banner std = BANNERS.get(0);
            int j = rollSummon(pool, std, r);
            if (j < 0) throw new IllegalArgumentException("Could not select a fighter from the summon pool.");
            copies[j]++;
            System.out.println("\u2728 Summoned: " + pool[j].name + " [" + colorLabel(pool[j]) + "]  (copies: " + copies[j] + ")");
        }

        // Copies beyond the limit break cap are not wasted: they turn into coins once, and the
        // saved inventory keeps the cap, so they are never counted twice.
        int extra = 0;
        for (int j = 0; j < pool.length; j++) {
            if (copies[j] > LB_MAX_COPIES) {
                extra += copies[j] - LB_MAX_COPIES;
                copies[j] = LB_MAX_COPIES;
            }
        }
        if (extra > 0) {
            coins += extra * EXTRA_COPY_COINS;
            System.out.println("\n" + extra + " extra copies beyond " + LB_MAX_COPIES + " turned into "
                + (extra * EXTRA_COPY_COINS) + " coins (total " + coins + ").");
        }
        SummonSave.setCoins(name, coins);
        saveInventory(name, pool, copies);

        // ---- 2) INVENTORY ----
        List<Integer> owned = new ArrayList<>();
        for (int j = 0; j < pool.length; j++) if (copies[j] > 0) owned.add(j);
        System.out.println("\n" + name + " inventory:");
        for (int i = 0; i < owned.size(); i++) {
            int j = owned.get(i);
            int c = copies[j];
            System.out.println("  [" + (i + 1) + "] " + pool[j].name + " [" + colorLabel(pool[j]) + "] x" + c
                + (c > LB_MAX_COPIES ? " (limit break capped at " + LB_MAX_COPIES + ")" : ""));
        }

        // ---- 3) PICK 3 DIFFERENT CHARACTERS ----
        int[] picks = new int[3];
        for (int i = 0; i < 3; ) {
            System.out.print("Pick character " + (i + 1) + "/3 (number): ");
            if (!sc.hasNextLine()) {
                throw new IllegalArgumentException("Summon input ended before the team was complete.");
            }
            String line = sc.nextLine().trim();
            int idx;
            try {
                idx = Integer.parseInt(line) - 1;
            } catch (NumberFormatException e) {
                System.out.println("Enter a number from the list.");
                continue;
            }
            if (idx < 0 || idx >= owned.size()) {
                System.out.println("That number is not in the inventory.");
                continue;
            }
            int j = owned.get(idx);
            boolean already = false;
            for (int k = 0; k < i; k++) if (picks[k] == j) already = true;
            if (already) {
                System.out.println("Already in this team, pick a different character.");
                continue;
            }
            picks[i++] = j;
        }

        // ---- 4) BUILD: fresh clone, then limit break perks ----
        Fighter[] team = new Fighter[3];
        for (int i = 0; i < 3; i++) {
            Fighter source = pool[picks[i]];
            team[i] = source.clone();
            if (team[i] == null) {
                throw new IllegalStateException("Fighter cloning failed during summoning.");
            }
            if (team[i].getClass() != source.getClass()) {
                throw new IllegalStateException(
                    "Fighter clone changed character type: "
                    + source.getClass().getSimpleName() + " -> "
                    + team[i].getClass().getSimpleName()
                );
            }
            applyLimitBreak(team[i], copies[picks[i]]);
            applyTierBoost(team[i]);
        }

        Teams t = new Teams(name, team[0], team[1], team[2]);
        for (Fighter f : team) f.assignTeam(t);

        System.out.println("\n" + name + " team:");
        for (Fighter f : team) {
            double boostD = f.tierDamage * f.featuredDamage, boostF = f.tierDefense * f.featuredDefense;
            String perk = f.lbCopies <= 0 ? "no limit break"
                : "LB " + f.lbCopies + ": damage x" + String.format("%.3f", f.lbDamage / boostD)
                + ", defense x" + String.format("%.3f", f.lbDefense / boostF)
                + ", HP x" + String.format("%.3f", f.lbHp);
            String tierText = "";
            if (!f.tier.isEmpty()) {
                tierText += (f.tierDamage == 1.0 && f.tierDefense == 1.0)
                    ? "  | " + tierName(f.tier) + ": no boost"
                    : "  | " + tierName(f.tier) + ": damage x" + String.format("%.2f", f.tierDamage)
                      + ", defense x" + String.format("%.2f", f.tierDefense);
            }
            if (f.featured) {
                tierText += "  | Featured Boost: damage x" + String.format("%.2f", f.featuredDamage)
                    + ", defense x" + String.format("%.2f", f.featuredDefense);
            }
            System.out.println("  " + f.name + "  (" + perk + ")" + tierText + "  HP " + Math.round(f.maxhp));
        }
        System.out.println(name + " team ready!");
        return t;
    }

    // =========================
    // 🔥 REFACTORED COMBO ENGINE
    // =========================
// Index (1 or 2) of a living benched fighter who can cover this card type right now, else -1.
    static int findCover(Teams team, Card incoming) {
        if (team == null || !team.canSwitchNow()) {
            return -1;
        }
        for (int i = 1; i < team.fighters.length; i++) {
            Fighter f = team.fighters[i];
            if (f != null && f.coverCard == incoming && isAlive(f) && f.switchCD <= 0) {
                return i;
            }
        }
        return -1;
    }

    // After a flagged fighter covers, his cover change is comboable into his coverCombo card:
    // if that card is in his hand and he has the ki, he fires it at the attacker.
    // Returns true if he did (that ends the attacker's combo).
    static boolean coverFollowUp(Teams coverTeam, Teams enemyTeam) {
        Fighter cover = coverTeam.getActive();
        Fighter enemy = enemyTeam.getActive();
        if (cover == null || cover.coverCombo == null || !isAlive(cover) || !isAlive(enemy)) {
            return false;
        }
        Card follow = cover.coverCombo;

        int slot = -1;
        for (int j = 0; j < coverTeam.hand.length; j++) {
            if (coverTeam.hand[j] == follow) {
                slot = j;
                break;
            }
        }
        if (slot < 0 || cover.ki < cover.kiRequired(follow)) {
            return false;
        }

        System.out.println("\n--- " + cover.name + " follows the cover change with a " + follow + "! ---");
        coverTeam.consumeSlot(slot);

        int actionTime = cover.getActionTime(follow);
        cover.recoverKi(actionTime);
        cover.recoverVanish(actionTime);
        enemy.recoverKi(actionTime);
        coverTeam.reduceSwitchCooldown(actionTime);
        enemyTeam.reduceSwitchCooldown(actionTime);

        cover.performAction(enemy, follow);

        timer += actionTime;
        if (timer >= MAX_TIMER) {
            return true;
        }
        System.out.println("⏱ Timer +" + actionTime + " → " + timer + "/" + MAX_TIMER);

        checkDeathAndHandle(enemyTeam, cover);
        return true;
    }

static void executeCombo(Teams attackerTeam, Teams defenderTeam, boolean isCounter) {

    if (attackerTeam == null || defenderTeam == null)
        return;

    Fighter attacker = attackerTeam.getActive();
    Fighter defender = defenderTeam.getActive();

    if (!isAlive(attacker) || !isAlive(defender))
        return;

    boolean comboStarted = false;

    for (int i = 0; i < 4; i++) {

        attacker = attackerTeam.getActive();
        defender = defenderTeam.getActive();

        if (!isAlive(attacker) || !isAlive(defender))
            return;

        // ---------- Prefer Ultimate ----------
        for (int j = 0; j < 4; j++) {
    if (attackerTeam.hand[j] == Card.ULT &&
        attacker.ultunlocked &&
        attacker.ki >= attacker.kiRequired(Card.ULT)) {

        Card temp = attackerTeam.hand[i];
        attackerTeam.hand[i] = attackerTeam.hand[j];
        attackerTeam.hand[j] = temp;
        break;
    }
}

        Card card = attackerTeam.hand[i];

        if (card == null)
            continue;

        if (card == Card.ULT && !attacker.ultunlocked)
            continue;

        if (attacker.ki < attacker.kiRequired(card))
            continue;

        boolean hasNext = false;
        for (int j = i + 1; j < 4; j++) {
            if (attackerTeam.hand[j] != null) {
                hasNext = true;
                break;
            }
        }

        // ---------- Flagged Cover Change (universal) ----------
        // A benched fighter flagged with coverCard == this card takes the hit for his teammate,
        // and his cover change is comboable into his coverCombo card (see coverFollowUp).
        boolean flagCovered = false;
        if (!isCounter && defender.coverCard != card) {
            int coverIdx = findCover(defenderTeam, card);
            if (coverIdx >= 0) {
                System.out.println("\n🛡️ COVER CHANGE! "
                        + defenderTeam.fighters[coverIdx].name
                        + " takes the " + card + " for "
                        + defender.name + "!");

                if (defenderTeam.switchFighter(coverIdx, attacker)) {
                    defender = defenderTeam.getActive();
                    flagCovered = true;
                }
            }
        }

        // ---------- Cover Change ----------
        if (!flagCovered && !isCounter && defender.hp < 1800000 && hasNext) {
            int coverIndex = chooseSwitchCandidate(defenderTeam, attacker);
            if (coverIndex >= 0) {
                System.out.println("\n🛡️ COVER CHANGE! "
                        + defenderTeam.fighters[coverIndex].name
                        + " saves "
                        + defender.name + "!");

                if (defenderTeam.switchFighter(coverIndex, attacker)) {
                    defender = defenderTeam.getActive();
                }
            }
        }

        // ---------- Danger Sense ----------
    
        // ---------- Danger Sense ----------
        // Only allow Danger Sense before the combo actually begins.
        // Do not let it interrupt an existing combo or tag extension.
        if (!isCounter
                && !comboStarted
                && defender.vanish >= 100
                && !attacker.quirkcannotbevanished) {

            if (defender.useDangerSense()) {

                if (Math.random() < 0.15) {
                    System.out.println("⚡ PERFECT DANGER SENSE!");

                    // The resulting counter-combo cannot trigger
                    // Danger Sense again.
                    executeCombo(defenderTeam, attackerTeam, true);

                } else {
                    System.out.println(
                        "⚡ " + defender.name
                        + "'s DANGER SENSE activates; they vanish!"
                    );
                }

                return;
            }
        }


        // ---------- Consume Card ----------
        attackerTeam.consumeSlot(i);

        int actionTime = attacker.getActionTime(card);

        attacker.recoverKi(actionTime);
        attacker.recoverVanish(actionTime);
        defender.recoverKi(actionTime);

        attackerTeam.reduceSwitchCooldown(actionTime);
        defenderTeam.reduceSwitchCooldown(actionTime);

        if (!comboStarted) {
            comboStarted = true;
            System.out.println("\n--- " + attacker.name + " starts a combo! ---");
        }

        boolean continueCombo = attacker.performAction(defender, card);

        timer += actionTime;

        if (timer >= MAX_TIMER)
            return;

        System.out.println("⏱ Timer +" + actionTime + " → " + timer + "/" + MAX_TIMER);

        if (checkDeathAndHandle(defenderTeam, attacker))
            return;

        // ---------- Flagged cover change combos into its follow-up card ----------
        if (flagCovered && coverFollowUp(defenderTeam, attackerTeam))
            return;

        // ---------- Tag Extension ----------
        if (continueCombo &&
            (attacker.ki < attacker.kiRequired(card) ||
             (!hasNext && attacker.ki < 40))) {

            int tagIndex = chooseSwitchCandidate(attackerTeam, defender);
            if (tagIndex >= 0
                && attackerTeam.switchFighter(tagIndex, defender)) {
                System.out.println(">>> TAG! "
                        + attackerTeam.getActive().name
                        + " extends the combo!");
                i = -1;
            }
        }

        if (!continueCombo)
            return;
    }
}

    // Helper class to hold the AI's decision
    static class CardChoice {
        int slot;
        Card card;
        CardChoice(int s, Card c) { slot = s; card = c; }
    }

    // AI decides what to throw out in neutral
    static CardChoice pickNeutralCard(Teams t) {
        if (t == null) {
            return null;
        }
        Fighter f = t.getActive();
        if (f == null || f.isParalyzed || f.isFaint) return null;
        
        // Priority 1: Throw a Strike or Blast to start a fight
        for (int i = 0; i < 4; i++) {
            Card c = t.hand[i];
            if (c == null) continue; // Don't raw Ult in neutral
            if ((c == Card.STRIKE || c == Card.BLAST)
                && f.ki >= f.kiRequired(c)) {
                return new CardChoice(i, c);
            }
        }
        
        // Priority 2: Throw a Blue Card.
        for (int i = 0; i < 4; i++) {
            Card c = t.hand[i];
            if (c == null) continue;
            if (c == Card.SPMOVE
                && f.ki >= f.kiRequired(c)) {
                return new CardChoice(i, c);
            }
        }
        
        //priority 3: Throw a ULT
        if(!f.sacrifice){
        for (int i = 0; i < 4; i++) {
            Card c = t.hand[i];
            if (c == null) continue;
            if (c == Card.ULT && f.ultunlocked
                && f.ki >= f.kiRequired(c)) {
                return new CardChoice(i, c);
            }
        }
    }
        
        // Priority 3: Use a Quirk (Green Card)
        for (int i = 0; i < 4; i++) {
            Card c = t.hand[i];
            if (c == null) continue;
            if (c == Card.ULT) continue;   // never fall back to ULT in neutral
            if (f.ki >= f.kiRequired(c)) {
                return new CardChoice(i, c);
            }
        }
        
        return null; // No playable cards
    }

    static int findTackleCounterSlot(Teams team, Fighter fighter) {
        if (team == null || fighter == null
                || fighter.isParalyzed || fighter.isFaint) {
            return -1;
        }

        // Stronger eligible counters are preferred. Blast is deliberately
        // excluded; only melee Special Moves can counter a Tackle.
        Card[] counterPriority = {Card.ULT, Card.SPMOVE, Card.STRIKE};
        for (Card counter : counterPriority) {
            if (counter == Card.ULT && !fighter.ultunlocked) {
                continue;
            }
            if (counter == Card.SPMOVE
                    && fighter.spMoveRange != AttackRange.MELEE) {
                continue;
            }
            if (fighter.ki < fighter.kiRequired(counter)) {
                continue;
            }

            for (int slot = 0; slot < team.hand.length; slot++) {
                if (team.hand[slot] == counter) {
                    return slot;
                }
            }
        }
        return -1;
    }

    static void resolveTackleCounter(Teams counterTeam, Teams tackleTeam,
                                     int counterSlot, Card counterCard,
                                     int recoveryTime) {
        if (counterTeam == null || tackleTeam == null
                || counterSlot < 0 || counterSlot >= counterTeam.hand.length
                || counterCard == null) {
            return;
        }

        Fighter counterFighter = counterTeam.getActive();
        Fighter tackleFighter = tackleTeam.getActive();
        if (!isAlive(counterFighter) || !isAlive(tackleFighter)) {
            return;
        }

        counterTeam.hand[counterSlot] = null;
        System.out.println("⚡ " + counterFighter.name + " counters "
            + tackleFighter.name + "'s TACKLE with " + counterCard + "!");
        boolean comboStarted = counterFighter.performAction(
            tackleFighter, counterCard);

        advanceComboWindow(counterTeam, tackleTeam, recoveryTime);
        System.out.println("⏱ Tackle counter recovery → " + timer
            + "/" + MAX_TIMER);

        if (timer < MAX_TIMER
                && !checkDeathAndHandle(tackleTeam, counterFighter)
                && comboStarted
                && isAlive(counterTeam.getActive())
                && isAlive(tackleTeam.getActive())) {
            executeCombo(counterTeam, tackleTeam, false);
        }
    }

    static void performRareNeutralAttack(Teams attackerTeam, Teams defenderTeam) {
        if (attackerTeam == null || defenderTeam == null) {
            return;
        }

        Fighter attacker = attackerTeam.getActive();
        Fighter defender = defenderTeam.getActive();
        if (!isAlive(attacker) || !isAlive(defender)) {
            return;
        }

        // These are uncommon neutral actions, not cards drawn into either hand.
        if (Math.random() >= NEUTRAL_ATTACK_CHANCE) {
            return;
        }

        NeutralAttack action = Math.random() < NEUTRAL_TACKLE_CHANCE
            ? NeutralAttack.TACKLE
            : NeutralAttack.QUICK_ATTACK;
        int actionTime = attacker.getNeutralActionTime(action);
        if (attacker.isParalyzed || attacker.isFaint
                || attacker.ki < attacker.kiRequired(action)) {
            return;
        }

        if (action == NeutralAttack.TACKLE) {
            int counterSlot = findTackleCounterSlot(defenderTeam, defender);
            if (counterSlot >= 0) {
                Card counterCard = defenderTeam.hand[counterSlot];
                int recoveryTime = Math.max(
                    actionTime, defender.getActionTime(counterCard));
                resolveTackleCounter(
                    defenderTeam, attackerTeam, counterSlot,
                    counterCard, recoveryTime);
                return;
            }

            // Danger Sense is the fallback when no eligible card can stop
            // the Tackle. It spends the full vanish gauge.
            if (defender.useDangerSense()) {
                System.out.println("⚡ " + defender.name
                    + "'s DANGER SENSE evades the TACKLE!");
                advanceComboWindow(attackerTeam, defenderTeam, actionTime);
                System.out.println("⏱ " + action + " recovery → "
                    + timer + "/" + MAX_TIMER);
                return;
            }
        }

        boolean comboStarted = false;
        if (action == NeutralAttack.QUICK_ATTACK
                && defender.guardNeutralAttack()) {
            // A blocked Quick Attack still costs its ki and recovery window.
            attacker.ki -= attacker.kiRequired(action);
            System.out.println("🛡️ " + defender.name
                + " uses GUARD and blocks " + attacker.name
                + "'s QUICK ATTACK!");
        } else {
            comboStarted = attacker.performNeutralAttack(defender, action);
        }

        advanceComboWindow(attackerTeam, defenderTeam, actionTime);
        System.out.println("⏱ " + action + " recovery → " + timer + "/" + MAX_TIMER);

        if (comboStarted && timer < MAX_TIMER
                && !checkDeathAndHandle(defenderTeam, attacker)
                && isAlive(attackerTeam.getActive())
                && isAlive(defenderTeam.getActive())) {
            System.out.println("🔥 The successful Tackle opens a combo!");
            executeCombo(attackerTeam, defenderTeam, false);
        }
    }
    
    static boolean isSpecialOrQuirk(Card card) {
        return card == Card.SPMOVE || card == Card.QUIRK;
    }

    static boolean hasPriority(Fighter attacker, Fighter defender, Card atk, Card def) {

        if (attacker == null || defender == null || atk == null || def == null) {
            return false;
        }

        // Range no longer affects priority: Ultimates lead, followed by the
        // existing card-type priority rules.
        if (atk == Card.ULT) {
            return true;
        }
        if (def == Card.ULT) {
            return false;
        }

        /*
         * Special moves and quirks share one priority tier.  Two cards from
         * this tier do not beat each other and therefore become a clash.
         */
        if (isSpecialOrQuirk(atk) && isSpecialOrQuirk(def)) {
            return false;
        }

        // Special moves and quirks beat Strike.
        if (isSpecialOrQuirk(atk) && def == Card.STRIKE) {
            return true;
        }
        if (atk == Card.STRIKE && isSpecialOrQuirk(def)) {
            return false;
        }

        /*
         * Special Moves get action-specific Blast Armor. Character-level
         * Blast Armor remains available for other actions such as Quirk.
         */
        if (atk == Card.BLAST && isSpecialOrQuirk(def)) {
            return !hasBlastArmorForAction(defender, def);
        }
        if (isSpecialOrQuirk(atk) && def == Card.BLAST) {
            return hasBlastArmorForAction(attacker, atk);
        }

        // Blast beats Strike.
        if (atk == Card.BLAST && def == Card.STRIKE) {
            return true;
        }
        if (atk == Card.STRIKE && def == Card.BLAST) {
            return false;
        }

        return false; // no clear priority -> forced clash
    }

    static boolean hasBlastArmorForAction(Fighter fighter, Card action) {
        return fighter != null
            && (fighter.hasBlastArmor || action == Card.SPMOVE);
    }

    
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        timer = 0;
        lastDrawTime = 0;
        stun = false;
                Fighter[] pool = {
            new ZenkaiGoku("ZenkaiGoku",3600000,70,100,350000,320000,1200000,1,200),
            new ZenkaiVegeta("ZenkaiVegeta",3700000,50,100,381000,326000,1400000,1,100),
            new UIGoku("UIGoku",3200000,50,100,302000,280000,1200000,1,1000),
            new ZenkaiSSBEVegeta("ZenkaiSSBEVegeta",3500000,50,100,300000,276000,1400000,1,100),
            new Gohan("Gohan",3000000,70,100,300000,300000,1200000,1,100),
            new Vegito("Vegito",3000000,70,100,300000,300000,1800000,1,100),
            new Superbaby2("Superbaby2",3200000,70,100,260000,265000,800000,1,100),
            new RoseGb("RoseGb",3000000,70,100,310000,270000,1000000,1,100),
            new TagGokuVegeta("Angel Goku",3000000,70,100,280000,280000,800000,1,100),
            new MajinVegeta("Majin Vegeta",3000000,70,100,300000,300000,1000000,1,100),
            new SOHTrunks("SOHTrunks",3000000,70,100,320000,310000,1500000,1,100),
            new BaseVegito("BaseVegito",3000000,70,100,400000,390000,2000000,1,300),
            new FusionZamasu("FusionZamasu",3000000,70,100,270000,280000,1500000,1,300),
            new SSJ4Gogeta("SSJ4Gogeta",3000000,70,100,320000,320000,1500000,1,100),
            new Cooler("Cooler",3200000,70,100,380000,250000,1200000,1,400),
            new Frieza("Frieza",3500000,70,100,280000,250000,1000000,1,100),
            new Janemba("Janemba",3500000,70,100,310000,290000,1200000,1,700),
            new SsjbVegeta("SSJB Vegeta",3300000,70,100,400000,380000,2500000,1,600),
            new MajinVegeta2("Majin Vegeta2",3300000,70,100,410000,380000,2000000,1,100),
            new GokuBlackRoseTyrant("GokuBlackRoseTyrant",3800000,70,100,340000,320000,2500000,1
            ,100),
             new MUIGoku("MUIGOKU",3500000,70,100,340000,320000,2000000,1,999999999),
             new Ssj3Goku("Ssj3Goku",3500000,70,100,320000,310000,2000000,1,100),
             new SsjbGoku("SsjbGoku",3500000,70,100,320000,340000,1500000,1,100),
             new Cell("Cell",3500000,70,100,300000,440000,1800000,1,100),
             new Beerus("Beerus",3500000,70,100,340000,340000,1800000,1,99999),
            new UltimateGohan("UltimateGohan",3500000,70,100,300000,440000,2600000,1,100),
            new Buuhan("Buuhan",3800000,70,100,400000,440000,2800000,1,100),
            new MetalCooler("MetalCooler",3800000,70,100,420000,400000,2700000,1,100)

        };


        System.out.println("\n==========================================");
        System.out.println("A NEW SEASON HAS BEGUN: " + SEASON_NAME);
        System.out.println("==========================================");

        Teams team1 = summonTeam("Team 1", pool, sc);
        Teams team2 = summonTeam("Team 2", pool, sc);

        timer = 0;
        lastDrawTime = 0;

        if (isAlive(team1.getActive()) && isAlive(team2.getActive())) {
    team1.getActive().switchIn(team2.getActive());
    team2.getActive().switchIn(team1.getActive());
}

        while (timer < MAX_TIMER && !team1.isDefeated() && !team2.isDefeated()) {

           advanceDrawTimer(team1, team2);

            timer++;

            for (Fighter f : team1.fighters) if (isAlive(f)) {
                f.applyBleed();
                f.updateParalysis();
                f.applyFaint();
                f.updateSeal();
                f.updateTimedStatuses();
            }

            for (Fighter f : team2.fighters) if (isAlive(f)) {
                f.applyBleed();
                f.updateParalysis();
                f.applyFaint();
                f.updateSeal();
                f.updateTimedStatuses();
            }

            // Status damage can defeat the active fighter before a combat action.
            // Resolve that death immediately so the match cannot stall on a dead active slot.
            if (!isAlive(team1.getActive())) {
                checkDeathAndHandle(team1, team2.getActive());
            }
            if (!isAlive(team2.getActive())) {
                checkDeathAndHandle(team2, team1.getActive());
            }

            // Spirit Bomb preparation uses an absolute timer and continues while
            // SSJ3 Goku is benched. Resolve it against the opponent's active fighter.
            for (Fighter f : team1.fighters) {
                if (f instanceof Ssj3Goku && isAlive(f)) {
                    ((Ssj3Goku) f).updateSpiritBomb(team2.getActive());
                }
            }
            for (Fighter f : team2.fighters) {
                if (f instanceof Ssj3Goku && isAlive(f)) {
                    ((Ssj3Goku) f).updateSpiritBomb(team1.getActive());
                }
            }

            team1.reduceSwitchCooldown(1);
            team2.reduceSwitchCooldown(1);

            if (isAlive(team1.getActive())) team1.getActive().recoverKi(1);
            if (isAlive(team2.getActive())) team2.getActive().recoverKi(1);

 if (isAlive(team1.getActive()) && isAlive(team2.getActive())) {
            team1.getActive().mainAbility(team2.getActive());
            team2.getActive().mainAbility(team1.getActive());
            team1.getActive().checkautotimer();
            team2.getActive().checkautotimer();
            team1.getActive().checkpreperation();
            team2.getActive().checkpreperation();
 }

            if (isAlive(team1.getActive()) && isAlive(team2.getActive())) {
                considerTacticalSwitch(team1, team2);
                considerTacticalSwitch(team2, team1);
            }

            System.out.println("⏱ Timer +1 (neutral) → " + timer + "/" + MAX_TIMER);


// ==========================================
// CLASH & NEUTRAL EXCHANGE SYSTEM (UPDATED)
// ==========================================
if (isAlive(team1.getActive()) && isAlive(team2.getActive())) {

    // 1. Roll for Initiative (60% chance for Team 1)
    boolean team1WinsNeutral = Math.random() < 0.60;
    Teams attackerTeam = team1WinsNeutral ? team1 : team2;
    Teams defenderTeam = team1WinsNeutral ? team2 : team1;

    Fighter atkF = attackerTeam.getActive();
    Fighter defF = defenderTeam.getActive();

    // 2. Both fighters pick an action simultaneously
    CardChoice atkChoice = pickNeutralCard(attackerTeam);
    CardChoice defChoice = pickNeutralCard(defenderTeam);

    if (atkChoice == null) {
        performRareNeutralAttack(attackerTeam, defenderTeam);
        continue;
    }

    // =========================
    // 🟢 QUIRK HANDLING
    // =========================
    if (atkChoice.card == Card.QUIRK) {

        attackerTeam.consumeSlot(atkChoice.slot);
        boolean quirkSucceeded = atkF.performAction(defF, Card.QUIRK);
        boolean quirkStartsCombo = quirkSucceeded && atkF.quirkComboable;

        int quirkTime = atkF.getActionTime(Card.QUIRK);

        if (defChoice != null && defChoice.card == Card.QUIRK) {
            defenderTeam.consumeSlot(defChoice.slot);
            defF.performAction(atkF, Card.QUIRK);
            quirkTime = Math.max(quirkTime, defF.getActionTime(Card.QUIRK));
            System.out.println(">>> Both fighters hold their ground!");
        }

        attackerTeam.reduceSwitchCooldown(quirkTime);
        defenderTeam.reduceSwitchCooldown(quirkTime);
        timer += quirkTime;
        System.out.println("⏱ Timer +" + quirkTime + " → " + timer + "/" + MAX_TIMER);

        if (quirkStartsCombo && timer < MAX_TIMER
                && !checkDeathAndHandle(defenderTeam, atkF)
                && isAlive(attackerTeam.getActive())
                && isAlive(defenderTeam.getActive())) {
            System.out.println("🔥 " + atkF.name
                + "'s comboable QUIRK starts a combo!");
            executeCombo(attackerTeam, defenderTeam, false);
        }
        continue;
    }

    // =========================
    // ⚔️ CHECK IF DEFENDER IS ATTACKING
    // =========================
    boolean defenderIsAttacking = (defChoice != null && defChoice.card != Card.QUIRK);

    if (defenderIsAttacking) {

        boolean atkPriority = hasPriority(atkF, defF, atkChoice.card, defChoice.card);
        boolean defPriority = hasPriority(defF, atkF, defChoice.card, atkChoice.card);

        // =========================
        // ⚡ SAME CARD → CLASH / BLAST CANCEL
        // =========================
        if (atkChoice.card == defChoice.card) {

            attackerTeam.consumeSlot(atkChoice.slot);
            defenderTeam.consumeSlot(defChoice.slot);

            if (atkChoice.card == Card.BLAST) {

                System.out.println("💥 " + atkF.name + "'s blast collides with " +
                                   defF.name + "'s blast and cancels out!");
                
            attackerTeam.hand[0] = Card.QUIRK;
            defenderTeam.hand[0] = Card.QUIRK;
                timer += 1;
                System.out.println("⏱ Timer +1 → " + timer + "/" + MAX_TIMER);

            } else {

                System.out.println("⚡ CLASH! " + atkF.name + "'s " + atkChoice.card +
                                   " clashes with " + defF.name + "'s " + defChoice.card + "!");

                double atkPower = atkF.clash + Math.random() * 10000;
                double defPower = defF.clash + Math.random() * 10000;

                Teams winner = (atkPower >= defPower) ? attackerTeam : defenderTeam;
                Teams loser  = (winner == attackerTeam) ? defenderTeam : attackerTeam;

                System.out.println("🔥 " + winner.getActive().name + " wins the clash!");

                int time = Math.max(atkF.getActionTime(atkChoice.card),
                                    defF.getActionTime(defChoice.card));

                timer += time;
                 double chipDamage = applyChipDamage(loser.getActive(), winner.getActive(), 100000);
                 System.out.println(loser.getActive().name+" of "+loser.teamName+
                                    " loses "+Math.round(chipDamage)+" hp");
                 if (checkDeathAndHandle(loser, winner.getActive()))
                     continue;
                executeCombo(winner, loser, true);

                System.out.println("⏱ Timer +" + time + " → " + timer + "/" + MAX_TIMER);
            }
        }

        // =========================
        // 🟡 PRIORITY SYSTEM
        // =========================
        else if (atkPriority && !defPriority) {

            System.out.println("⚡ PRIORITY! " + atkF.name + " beats " + defChoice.card);

            attackerTeam.consumeSlot(atkChoice.slot);
            defenderTeam.consumeSlot(defChoice.slot);
            double chipDamage = applyChipDamage(defF, atkF, 100000);
            attackerTeam.hand[0]=Card.STRIKE;
            stun=true;
            System.out.println(defF.name+" of "+defenderTeam.teamName+
                               " loses "+Math.round(chipDamage)+" hp");
            if (checkDeathAndHandle(defenderTeam, atkF)) {
                stun=false;
                continue;
            }
            executeCombo(attackerTeam, defenderTeam, true);
            stun=false;
            continue;
        }

        else if (defPriority && !atkPriority) {

            System.out.println("⚡ COUNTER! " + defF.name + " beats " + atkChoice.card);

            attackerTeam.consumeSlot(atkChoice.slot);
            defenderTeam.consumeSlot(defChoice.slot);
            double chipDamage = applyChipDamage(atkF, defF, 100000);
            defenderTeam.hand[0]=Card.STRIKE;
            stun=true;
            System.out.println(atkF.name+" of "+attackerTeam.teamName+
                               " loses "+Math.round(chipDamage)+" hp");
            if (checkDeathAndHandle(attackerTeam, defF)) {
                stun=false;
                continue;
            }
            executeCombo(defenderTeam, attackerTeam, true);
            stun=false;
            continue;
        }

        // =========================
        // 🔴 NO PRIORITY → FORCED CLASH
        // =========================
        else {

            System.out.println("⚡ FORCE CLASH!");

            attackerTeam.consumeSlot(atkChoice.slot);
            defenderTeam.consumeSlot(defChoice.slot);

            double atkPower = atkF.clash + Math.random() * 10000;
            double defPower = defF.clash + Math.random() * 10000;

            Teams winner = (atkPower >= defPower) ? attackerTeam : defenderTeam;
            Teams loser  = (winner == attackerTeam) ? defenderTeam : attackerTeam;

            System.out.println("🔥 " + winner.getActive().name + " wins the clash!");

            int time = Math.max(atkF.getActionTime(atkChoice.card),
                                defF.getActionTime(defChoice.card));

            timer += time;
             double chipDamage = applyChipDamage(loser.getActive(), winner.getActive(), 100000);
            System.out.println(loser.getActive().name+" of "+loser.teamName+
                               " loses "+Math.round(chipDamage)+" hp");
             if (checkDeathAndHandle(loser, winner.getActive()))
                 continue;
            executeCombo(winner, loser, true);
            System.out.println("⏱ Timer +" + time + " → " + timer + "/" + MAX_TIMER);
        }

    }

    // =========================
    // 🟢 ONLY ATTACKER ACTS
    // =========================
    else {

        System.out.println(">>> " + attackerTeam.teamName + " wins the neutral exchange!");

        if (defChoice != null && defChoice.card == Card.QUIRK) {
            defenderTeam.consumeSlot(defChoice.slot);
            defF.performAction(atkF, Card.QUIRK);
        }

        // swap to slot 0
        Card temp = attackerTeam.hand[0];
        attackerTeam.hand[0] = attackerTeam.hand[atkChoice.slot];
        attackerTeam.hand[atkChoice.slot] = temp;

        executeCombo(attackerTeam, defenderTeam, false);
    }
}
        }

        System.out.println("\n========== BATTLE OVER ==========");

        if (team1.isDefeated() && team2.isDefeated()) {
            System.out.println("It's a Draw!");
        } else if (team1.isDefeated()) {
            System.out.println("Team 2 Wins!");
        } else if (team2.isDefeated()) {
            System.out.println("Team 1 Wins!");
        } else {
            System.out.println("Time's Up!");
            if (team1.totalDamageDealt > team2.totalDamageDealt) {
                System.out.println("Team 1 Wins!");
            } else if (team2.totalDamageDealt > team1.totalDamageDealt) {
                System.out.println("Team 2 Wins!");
            } else {
                System.out.println("It's a Draw!");
            }
        }

        System.out.println("Damage Dealt:");
        System.out.println("Team 1: " + team1.totalDamageDealt);
        System.out.println("Team 2: " + team2.totalDamageDealt);

        int winner;
        if (team1.isDefeated() && team2.isDefeated()) winner = 0;
        else if (team1.isDefeated()) winner = 2;
        else if (team2.isDefeated()) winner = 1;
        else if (team1.totalDamageDealt > team2.totalDamageDealt) winner = 1;
        else if (team2.totalDamageDealt > team1.totalDamageDealt) winner = 2;
        else winner = 0;
        awardMatchCoins(team1.teamName, team2.teamName, winner);

        System.out.print("\nReset coins, pity and inventory for both teams? (y/n): ");
        if (sc.hasNextLine() && sc.nextLine().trim().toLowerCase().startsWith("y")) {
            SummonSave.resetAll();
            System.out.println("Reset done: every team is back to " + START_COINS + " coins, 0 pity and an empty inventory.");
        } else {
            System.out.println("Progress kept.");
        }
    }
}
         
/* =========================
       TEAMS SYSTEM
========================= */
class Teams{   
    String teamName;
    Fighter fighters[]=new Fighter[3];
    int MAX_COOLDOWN=15;
    int nextSwitchAllowedAt=0;
    long totalDamageDealt = 0; 
    boolean switching =false;
    /* -------- Cards -------- */

Card[] hand = new Card[4];
    int drawProgress = 0;   // draw meter; see Main.DRAW_PROGRESS_PER_CARD
    int lastConsumedSlot = -1;   // hand slot of the card that was played most recently

    // Takes the card out of a slot because it was played, remembering which slot it was.
    void consumeSlot(int slot) {
        if (slot < 0 || slot >= hand.length) {
            return;
        }
        hand[slot] = null;
        lastConsumedSlot = slot;
    }
    
    Teams(String teamName, Fighter f1, Fighter f2, Fighter f3){
        this.teamName=teamName;
        this.fighters[0]=f1;
        this.fighters[1]=f2;
        this.fighters[2]=f3;

        // A team owns its fighters as soon as it is constructed.  This also
        // makes Teams usable outside summonTeam(), which previously had to
        // remember to assign the team separately.
        for (Fighter fighter : fighters) {
            if (fighter != null) {
                fighter.assignTeam(this);
            }
        }
        
        for (int i = 0; i < 4; i++) 
        {
           refillSlot(i);
           
        }
    }
    
void refillSlot(int slot) {
    if (slot < 0 || slot >= hand.length) {
        return;
    }

    double random = Math.random();

    if (random <= 0.3)
        hand[slot] = Card.STRIKE;
    else if (random <= 0.6)
        hand[slot] = Card.BLAST;
    else if (random <= 0.8)
        hand[slot] = Card.QUIRK;
    else
        hand[slot] = Card.SPMOVE;

    System.out.println("[" + teamName + "] drew " + hand[slot]);
}
    int handSize() {
        int n = 0;
        for (Card c : hand) {
            if (c != null) n++;
        }
        return n;
    }

    // Draws up to 'count' cards into empty hand slots. Returns how many were drawn.
    int drawCards(int count) {
        int drawn = 0;
        for (int i = 0; i < hand.length && drawn < count; i++) {
            if (hand[i] == null) {
                refillSlot(i);
                drawn++;
            }
        }
        return drawn;
    }

    // Refills every empty hand slot.
    void fillHand() {
        drawCards(hand.length);
    }

    // Adds draw progress and draws every card that has come due. A card that
    // comes due while the hand is full is lost, as it always was.
    void addDrawProgress(int amount) {
        drawProgress += amount;
        int due = drawProgress / Main.DRAW_PROGRESS_PER_CARD;
        if (due > 0) {
            drawProgress -= due * Main.DRAW_PROGRESS_PER_CARD;
            drawCards(due);
        }
    }

    void drawToHand(int drawspeed) {
        if (drawspeed <= 0) {
            return;
        }
        drawCards(drawspeed);
    }
    
    void forceCard(Card card)
    {
        if (card == null) {
            return;
        }

        String activeName = getActive() == null ? teamName : getActive().name;

        if(card.equals(Card.SPMOVE))
        {
            for(int i=0;i<4;i++){
            if(hand[i]==null){
            hand[i]=card;
            System.out.println(activeName + " Drawn a blue card");
            return;
        }
            }
    }
    else if(card.equals(Card.ULT))
    {
        for(int i=0;i<4;i++){
            if(hand[i]==null){
            hand[i]=card;
            System.out.println(activeName + " Drawn a ulimate card");
            return;
        }
            }
    }
    int rndmslots=(int)(Math.random()*4);
    Card discarded=hand[rndmslots];
    if(discarded == Card.ULT){
        return;
    }
    if(discarded == null){
        hand[rndmslots]=card;
    System.out.println(">>> " + activeName + " draws " + card + "!");
    }
    else{
    hand[rndmslots]=card;
    System.out.println(">>> " + activeName + " Discarded " + discarded + " for " + card + "!");
    }
    }
    
   Fighter getActive() {
    return fighters[0];
}

    boolean canSwitchNow() {
        return Main.timer >= nextSwitchAllowedAt;
    }
    
    boolean switchFighter(int next, Fighter opponent) {

        // The restriction belongs to the fighter who would be switched OUT:
        // a paralyzed / fainted / no-switch fighter pins his OWN team in place.
        Fighter self = fighters[0];
        if(self != null && (self.isParalyzed || self.noswitching || self.isFaint)){
            if(self.isFaint)
            System.out.println(">>> " + self.name + " is fainted no switching allowed");
            else if(self.isParalyzed)
            System.out.println(">>> " + self.name + " is paralyzed no switching allowed");
            else if(self.noswitching)
            System.out.println(">>> " + self.name + " is unable to switch ");
            return false;
        }

    if (next <= 0 || next > 2)
        return false;

    if (fighters[0] == null || opponent == null)
        return false;

    if (!canSwitchNow())
        return false;

    if (fighters[next] == null || fighters[next].hp <= 0)
        return false;

    if (fighters[next].switchCD > 0)
        return false;

    fighters[0].switchCD = MAX_COOLDOWN;

    fighters[0].switchOut();

    Fighter temp = fighters[0];
    fighters[0] = fighters[next];
    fighters[next] = temp;
    nextSwitchAllowedAt = Main.timer + Math.max(1, MAX_COOLDOWN);

    System.out.println("\n>>> [" + teamName + "] switches to " + fighters[0].name + "!");

    // remove ult cards
    for (int i = 0; i < 4; i++) {
        if (hand[i] == Card.ULT)
            hand[i] = null;
    }

    fighters[0].switchIn(opponent);
    opponent.onEnemySwitchIn(fighters[0]);

    return true;
}
    
    void reduceSwitchCooldown(int time) {

        if (time <= 0) {
            return;
        }
    
        for(Fighter f:fighters){
            if (f != null && f.switchCD > 0) {
            f.switchCD -= time;
            if (f.switchCD < 0) f.switchCD = 0;
        }
        }
    }
    
    boolean isDefeated() {

    for (Fighter f : fighters) {

        if (f != null && f.hp > 0)
            return false;
    }

    return true;
}
    
    void addDamage(double amount) {
        if (Double.isFinite(amount) && amount > 0) {
            this.totalDamageDealt += (long)amount;
        }
    }
    
    void healTeam(double percent) {
    if (!Double.isFinite(percent) || percent <= 0) {
        return;
    }

    for (Fighter f : fighters) {
        if (f != null && f.hp > 0) {
            int heal = (int)(f.maxhp * percent);
            f.hp = Math.min(f.hp + heal, f.maxhp);
        }
    }
}

void DamageTeam(double damage) {
    DamageTeam(null, damage);
}

void DamageTeam(Fighter attacker, double damage) {
    if (!Double.isFinite(damage) || damage <= 0) {
        return;
    }

    for (Fighter f : fighters) {
        if (f != null && f.hp > 0) {
            double adjustedDamage = damage * Main.colorDamageMultiplier(attacker, f);
            f.hp = Math.max(f.hp - adjustedDamage*1.5, 1);
            if(f.hp<0)
            f.hp=1;
        }
    }
}


}

/* =========================
   BASE FIGHTER CLASS
   ========================= */
class Fighter implements Cloneable {
    
    @Override
    public Fighter clone() {
        /*
         * These objects are character templates used by summonTeam().  A
         * shallow super.clone() is unsafe here: it copies every subclass
         * counter (transformation stages, gauges, used-card counts, etc.)
         * along with any future mutable fields.  Resetting only the fields
         * known by Fighter can therefore leave a clone with a hybrid state.
         *
         * Construct the same runtime class instead.  Every playable
         * character in this file exposes the same constructor shape, so this
         * preserves dynamic dispatch and constructor-defined character
         * behavior while giving the clone fresh battle state.
         */
        try {
            java.lang.reflect.Constructor<? extends Fighter> constructor =
                getClass().asSubclass(Fighter.class).getDeclaredConstructor(
                    String.class,
                    double.class,
                    int.class,
                    int.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class
                );

            constructor.setAccessible(true);

            return constructor.newInstance(
                name,
                maxhp,
                ki,
                vanish,
                strikedmg,
                blastdmg,
                spmovedmg,
                rate,
                clash
            );
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException(
                "Unable to create a fresh clone of " + getClass().getName(),
                e
            );
        }
    }



    /* -------- Core Stats -------- */
    String name;
    double hp;
    double maxhp;
    int ki;
    int vanish;
    double rate;
    double clash;
double damageMul = 1.0;   
double defenseMul = 1.0;  
// LIMIT BREAK (summon copies). Kept in separate factors so no buff cancel, nullify or
// transformation (which only touch damageMul/defenseMul with += or =) can ever remove them.
double lbDamage = 1.0;
double lbDefense = 1.0;
double lbHp = 1.0;
int lbCopies = 0;
// TIER BOOST (balance boost for weaker characters). Separate from limit break; also cannot be cancelled.
String tier = "";
double tierDamage = 1.0;
double tierDefense = 1.0;
boolean tierApplied = false;
// FEATURED BOOST (banner headliners). Separate from the strength tier and from limit break; also cannot be cancelled.
boolean featured = false;
double featuredDamage = 1.0;
double featuredDefense = 1.0;
int switchCD=0;
// TEMP DEFENSE DOWN (TEST SYSTEM)
double tempDefDown = 0.0;
int tempDefEndTime = 0;
double timedDamageBuff = 0.0;   // +0.5 = +50% damage
// Each active buff is {value, expiresAt}. timedDamageBuff is the cached sum of the live ones.
java.util.ArrayList<double[]> damageBuffs = new java.util.ArrayList<>();
int debuffExpiresAt=0;
double tempDefUp=0.0;
double timedDamageDown=0.0;   // cached combined reduction of the live entries - read it via currentDamageDown()
// Each active reduction is {value, expiresAt}, same idea as damageBuffs.
java.util.ArrayList<double[]> damageDowns = new java.util.ArrayList<>();
// false = the strongest live reduction applies (the old '=' / Math.max behaviour)
// true  = live reductions multiply together: total = 1 - (1-a)(1-b)...
static final boolean DAMAGE_DOWN_STACKS = false;
int tempDefUpEndTime=0;
double bleedDamage = 0;
int bleedEndTime = 0;
boolean isParalyzed = false;
int paralysisEndTime = 0;
boolean isFaint=false;
int faintEndTime=0;
boolean sealmain=false;
int sealmainTime=0;
boolean endurancehas=false;
boolean endnulify=false;
int endnulifyBuffExpiresAt=0;
int drawspeedtimer=0;
int drawSpeedPenalty=0;
int temporaryDrawSpeed=0;
int temporaryDrawSpeedExpiresAt=0;
boolean justDodged=false;
boolean isMeleeSpMove = false;
boolean isRangedSpMove = false;
boolean ignoresBlast = false; 
boolean hasBlastArmor = false;
boolean releaseguagetimer=false;
int releaseguagetimerExpiresAt=0;
boolean handImmune=false;
int endTimeimmune=0;
boolean justattacked=false;
boolean revive=false;
boolean kiimmune=false;
int kiImmuneExpiresAt=0;
int handImmuneExpiresAt=0;
boolean buffCancelImmune = false;
int buffCancelImmuneExpiresAt = 0;
boolean immuneindestructible = false;
boolean cannotultnull = false;
boolean immuneguage=false;
int immuneguageExpiresAt=0;
int immuneIndestructibleExpiresAt = 0;
int cannotUltNullExpiresAt = 0;
boolean isImmune=false;
int isImmuneExpiresAt=0;
boolean colorImmune=false;
int colorImmuneExpiresAt=0;
boolean noswitching=false;
int noswitchingExpiresAt=0;
boolean quirkcannotbevanished=false;
int     autotimer=0;
boolean autocounter=false;
boolean sacrifice=false;
boolean colourimmunitypermanent=false;
boolean colourNullGrantsPermanent=false;
boolean permanentendurancenull=false;
boolean endNullGrantsPermanent=false;
int     preparation=0;
boolean preparationmodeon=false;
boolean permanentapplyimmuneguage=false;
boolean permanentimmunity=false;
boolean permanentbuffcancelimmunity=false;



void applypreparationmode(int duration) {
    if (duration <= 0) {
        clearpreparationmode();
        return;
    }

    preparation = Math.max(preparation, Main.timer + duration);
}

void checkpreperation() {
    if(preparation<=Main.timer){
        preparationmodeon=true;
    }
    else{
        preparationmodeon=false;
    }
}

void clearpreparationmode() {
    preparation = 0;
    preparationmodeon= false;
}

void applyautotimer(int duration) {
    if (duration <= 0) {
        clearautotimer();
        return;
    }

    autotimer = Math.max(autotimer, Main.timer + duration);
}

void checkautotimer() {
    if(autotimer<=Main.timer){
        autocounter=true;
    }
    else{
        autocounter=false;
    }
}

void clearautotimer() {
    autotimer = 0;
    autocounter = false;
}


void applyNoswitch(int duration) {
    if (duration <= 0) {
        clearNoswitch();
        return;
    }

    noswitching = true;
    noswitchingExpiresAt =
        Math.max(noswitchingExpiresAt, Main.timer + duration);
}

void clearNoswitch() {
    noswitching = false;
    noswitchingExpiresAt = 0;
}

void updateNoswitch() {
    if (noswitchingExpiresAt > 0
            && Main.timer >= noswitchingExpiresAt) {
        clearNoswitch();
    }
}

void applyColorImmunity(int duration) {
    if (duration <= 0) {
        clearColorImmunity();
        return;
    }

    colorImmune = true;
    colorImmuneExpiresAt =
        Math.max(colorImmuneExpiresAt, Main.timer + duration);
    if(colourNullGrantsPermanent)
        colourimmunitypermanent=true;
}

void clearColorImmunity() {
    if(colourimmunitypermanent && colourNullGrantsPermanent){
        return;
    }
    colorImmune = false;
    colorImmuneExpiresAt = 0;
}

void updateColorImmunity() {
    if (colorImmuneExpiresAt > 0
            && Main.timer >= colorImmuneExpiresAt) {
        colourimmunitypermanent=false;
        clearColorImmunity();
    }
}

void applyImmune(int duration) {
    if (duration <= 0) {
        clearImmunity();
        return;
    }

    isImmune = true;
    isImmuneExpiresAt =
        Math.max(isImmuneExpiresAt, Main.timer + duration);
}

void clearImmunity() {
    if(!permanentimmunity){
    isImmune = false;
    isImmuneExpiresAt = 0;
    }
}

void updateTimedImmunity() {
    if (isImmuneExpiresAt > 0
            && Main.timer >= isImmuneExpiresAt) {
        clearImmunity();
    }
}

void applyImmuneGuage(int duration) {
    if (duration <= 0) {
        clearImmuneGuageImmunity();
        return;
    }

    immuneguage = true;
    immuneguageExpiresAt =
        Math.max(immuneguageExpiresAt, Main.timer + duration);
}

void clearImmuneGuageImmunity() {
    if(!permanentapplyimmuneguage){
    immuneguage = false;
    immuneguageExpiresAt = 0;
    }
}

void updateTimedImmuneGuage() {
    if (immuneguageExpiresAt > 0
            && Main.timer >= immuneguageExpiresAt) {
        clearImmuneGuageImmunity();
    }
}

void applyBuffCancelImmunity(int duration) {
    if (duration <= 0) {
        clearBuffCancelImmunity();
        return;
    }

    buffCancelImmune = true;
    buffCancelImmuneExpiresAt =
        Math.max(buffCancelImmuneExpiresAt, Main.timer + duration);
}

void clearBuffCancelImmunity() {
    if(!permanentbuffcancelimmunity){
    buffCancelImmune = false;
    buffCancelImmuneExpiresAt = 0;
    }
}

void updateTimedBuffCancelImmunity() {
    if (buffCancelImmuneExpiresAt > 0
            && Main.timer >= buffCancelImmuneExpiresAt) {
        clearBuffCancelImmunity();
    }
}

void applyIndestructibleImmunity(int duration) {
    if (duration <= 0) {
        immuneindestructible = false;
        immuneIndestructibleExpiresAt = 0;
        return;
    }

    immuneindestructible = true;
    immuneIndestructibleExpiresAt =
        Math.max(immuneIndestructibleExpiresAt, Main.timer + duration);
}

void clearIndestructibleImmunity() {
    immuneindestructible = false;
    immuneIndestructibleExpiresAt = 0;
}

void applyCannotUltNullification(int duration) {
    if (duration <= 0) {
        cannotultnull = false;
        cannotUltNullExpiresAt = 0;
        return;
    }

    cannotultnull = true;
    cannotUltNullExpiresAt =
        Math.max(cannotUltNullExpiresAt, Main.timer + duration);
}

void clearUltnullificationImmunity() {
    cannotultnull = false;
    cannotUltNullExpiresAt = 0;
}


void updateTimedControlImmunities() {
    if (immuneIndestructibleExpiresAt > 0
            && Main.timer >= immuneIndestructibleExpiresAt) {
        immuneindestructible = false;
        immuneIndestructibleExpiresAt = 0;
    }

    if (cannotUltNullExpiresAt > 0
            && Main.timer >= cannotUltNullExpiresAt) {
        cannotultnull = false;
        cannotUltNullExpiresAt = 0;
    }
}

void updateTimedhandImmune() {
    if (handImmuneExpiresAt > 0
        && Main.timer >= handImmuneExpiresAt) {
        handImmune=false;
        handImmuneExpiresAt=0;
        endTimeimmune=0;
    }
}

void updateTimedKiImmune() {
    if (kiImmuneExpiresAt > 0 && Main.timer >= kiImmuneExpiresAt) {
        kiimmune=false;
        kiImmuneExpiresAt=0;
    }
}

void updateTimedStatuses() {
    updateTimeddrawspeedBuffs();
    updateTimedBuffs();
    updateTimedBuffsdefence();
    updateTimedEndNullify();
    updateTimedEndIndestructible();
    updateTimedhandImmune();
    updateTimedKiImmune();
    updateTimedBuffCancelImmunity();
    updateTimedControlImmunities();
    updateTimedImmuneGuage();
    updateTimedImmunity();
    updateColorImmunity();
    updateNoswitch();
    currentCardCostIncrease(null);   // prunes expired cost debuffs
}

void updateTimeddrawspeedBuffs() {
    if (drawspeedtimer > 0 && Main.timer >= drawspeedtimer) {
        drawSpeedPenalty = 0;
        drawspeedtimer=0;
    }

    if (temporaryDrawSpeedExpiresAt > 0
            && Main.timer >= temporaryDrawSpeedExpiresAt) {
        temporaryDrawSpeed = 0;
        temporaryDrawSpeedExpiresAt = 0;
    }
}

int getEffectiveDrawSpeed() {
    updateTimeddrawspeedBuffs();
    int speed = temporaryDrawSpeedExpiresAt > 0 ? temporaryDrawSpeed : drawspeed;
    // Penalties lower the speed (slower refills); they never turn drawing off.
    return Math.max(Main.MIN_DRAW_SPEED, speed - drawSpeedPenalty);
}

void setDrawSpeedFor(int speed, int duration) {
    if (duration <= 0) {
        return;
    }

    temporaryDrawSpeed = Math.max(Main.MIN_DRAW_SPEED, speed);
    temporaryDrawSpeedExpiresAt = Main.timer + duration;
}
void applyImmuneHand(int duration)
{
    handImmune=true;
    handImmuneExpiresAt=Math.max(handImmuneExpiresAt, Main.timer+duration);
    endTimeimmune=handImmuneExpiresAt;
}

void applyKiImmune(int duration)
{
    kiimmune=true;
    kiImmuneExpiresAt=Math.max(kiImmuneExpiresAt, Main.timer+duration);
}

void applyFaint(){
    if (faintEndTime <= 0 || Main.timer >= faintEndTime) {
        isFaint = false;
        return;
    }
    isFaint=true;
    System.out.println("[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] "
    + name + " is fainted");
}

void applyBleed() {
    if (hp <= 0 || bleedDamage <= 0) {
        bleedDamage = 0;
        return;
    }
    if (bleedEndTime > 0 && Main.timer <= bleedEndTime) {
        hp -= bleedDamage;
        // Bleed cannot finish a living fighter; it leaves them at 1 HP minimum.
        hp = Math.max(1, hp);
        System.out.println("[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] " + name + " is bleeding!");
    } else {
        bleedDamage = 0;
    }
}

void updateParalysis() {
    if (paralysisEndTime <= 0 || Main.timer >= paralysisEndTime) {
        isParalyzed = false;
        return;
    }
    isParalyzed=true;
     System.out.println("[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] "
    + name + " is paralysed");
}

void applyTempDefenseDown(double value, int duration) {
    tempDefDown += value;
    tempDefEndTime = Math.max(tempDefEndTime, Main.timer + duration);
}

void applyTempDefenseUp(double value, int duration) {
    tempDefUp += value;
    tempDefUpEndTime = Math.max(tempDefUpEndTime, Main.timer + duration);
}

void EndNullify(int duration) {
    if (duration <= 0) {
        clearEndNullify();
        return;
    }

    endnulify = true;
    endnulifyBuffExpiresAt = Math.max(
        endnulifyBuffExpiresAt, Main.timer + duration);
     if (endNullGrantsPermanent) {
        permanentendurancenull = true;
    }
}

void clearHandImmune() {
    handImmune=false;
    handImmuneExpiresAt=0;
    endTimeimmune=0;
}

void clearKiImmune() {
    kiimmune=false;
    kiImmuneExpiresAt=0;
}

void clearEndNullify() {
    if (endNullGrantsPermanent && permanentendurancenull) {
        return;
    }
    endnulify=false;
    endnulifyBuffExpiresAt=0;
}

void drawspeedtime(Fighter attacker,int duration){
    if (attacker == null || duration <= 0) {
        return;
    }
    attacker.updateTimeddrawspeedBuffs();
    attacker.drawSpeedPenalty++;
    attacker.drawspeedtimer=Math.max(attacker.drawspeedtimer,Main.timer+duration);
}

void releaseguageend(int duration){
    releaseguagetimer=true;
    releaseguagetimerExpiresAt=Math.max(releaseguagetimerExpiresAt,Main.timer+duration);
}

// Stackable timed damage buff, same style as applyNoswitch(duration):
// adds `value` to the buff and keeps whichever expiry is longer.
void applyDamageBuff(double value, int duration) {
    if (value <= 0 || duration <= 0) {
        return;
    }
    damageBuffs.add(new double[] { value, Main.timer + duration });
    timedDamageBuff += value;
}

// Total of all buffs that are still active right now.
double currentDamageBuff() {
    updateTimedBuffs();
    return timedDamageBuff;
}

void clampHp() {
    if (hp > maxhp) hp = maxhp;
}

// ---- shared helpers (replace the repeated "hp += X; hp = Math.min(hp, maxhp);" etc.) ----
void heal(double amount) {
    hp = Math.min(maxhp, hp + amount);
}

void gainKi(double amount) {
    ki = (int) Math.min(100, ki + amount);
}

// Draws up to 'count' cards for this fighter's team and announces how many.
void drawCards(int count) {
    int drawn = myTeam.drawCards(count);
    System.out.println(name + " draws " + drawn + " card" + (drawn == 1 ? "!" : "s!"));
}

// Draws 2 cards if the hand is nearly empty (<= 2 cards), otherwise 1.
void drawCardsSmart() {
    drawCards(myTeam.handSize() <= 2 ? 2 : 1);
}

void updateTimedBuffs() {
    double total = 0.0;
    for (int i = damageBuffs.size() - 1; i >= 0; i--) {
        double[] buff = damageBuffs.get(i);
        if (Main.timer >= buff[1]) {
            damageBuffs.remove(i);
        } else {
            total += buff[0];
        }
    }
    timedDamageBuff = total;
    clampHp();   // catches uncapped "hp += X" heals before the next action/hit
}

// Timed damage REDUCTION (fraction of incoming damage ignored). Each call has its own expiry.
void applyDamageDown(double value, int duration) {
    if (value <= 0 || duration <= 0) {
        return;
    }
    damageDowns.add(new double[] { value, Main.timer + duration });
    updateTimedBuffsdefence();
}

// Total reduction from the entries that are still active right now.
double currentDamageDown() {
    updateTimedBuffsdefence();
    return timedDamageDown;
}

void updateTimedBuffsdefence() {
    double best = 0.0;
    double keep = 1.0;
    for (int i = damageDowns.size() - 1; i >= 0; i--) {
        double[] entry = damageDowns.get(i);
        if (Main.timer >= entry[1]) {
            damageDowns.remove(i);
        } else {
            best = Math.max(best, entry[0]);
            keep *= (1.0 - entry[0]);
        }
    }
    timedDamageDown = DAMAGE_DOWN_STACKS ? (1.0 - keep) : best;
}

void updateTimedEndNullify() {
    if (endnulifyBuffExpiresAt >= 0
            && Main.timer >= endnulifyBuffExpiresAt) {
        permanentendurancenull=false;
        endnulifyBuffExpiresAt = 0;
        endnulify=false;
    }
}

boolean isEnduranceNullifiedBy(Fighter attacker) {
    if(attacker == null)
        return false;
    else if(attacker.permanentendurancenull || attacker.sacrifice)
        return true;
    attacker.updateTimedEndNullify();
    return attacker.endnulify;
}

void updateTimedEndIndestructible() {
    
    if (Main.timer >=releaseguagetimerExpiresAt ) {
        releaseguagetimerExpiresAt = 0;
        releaseguagetimer=false;
    }
}

    /* -------- Combat State -------- */
    double mul = 1;
    double permul = 1;
    boolean ultunlocked = false;
    boolean mainused = false;
    int drawspeed=1;
    double strikedmg=0.0;
    double blastdmg=0.0;
    double spmovedmg=0.0;
    double ultdmg=0.0;
    AttackRange spMoveRange = AttackRange.LONG_RANGE;
    // Allows a neutral Quirk to open a combo. Any successful Quirk can still
    // extend a combo that is already underway.
    boolean quirkComboable = false;
    CharacterColor color = CharacterColor.RED;
    boolean superiorColor = false;
int getActionTime(Card card){
    if (card == null) {
        return 0;
    }
    
    switch (card) {
        case STRIKE: return 3;
        case BLAST:  return 4;
        case QUIRK:  return 2;
        case SPMOVE: return 6;
        case ULT:    return 10;
        default: return 1;
    }
}

// ---- CARD COST INCREASE (debuff) ----
// Each entry is {cardOrdinal (-1 = every card), extraKi, expiresAt}.
// "counts" = timer ticks, same as the duration of applyTempDefenseDown(...).
// Call it on whoever should PAY more:  target.strikecardcostincrease(10, 10);
java.util.ArrayList<double[]> cardCostIncreases = new java.util.ArrayList<>();

// ---- CARD COST IMMUNITY ----
// While immune, new card cost increases cannot be put on this fighter.
// "counts" = timer ticks. Granting it again keeps whichever expiry is longer.
int cardCostImmuneExpiresAt = 0;

void cardcostimmune(int counts) {
    if (counts <= 0) {
        return;
    }
    cardCostImmuneExpiresAt = Math.max(cardCostImmuneExpiresAt, Main.timer + counts);
}

boolean isCardCostImmune() {
    return Main.timer < cardCostImmuneExpiresAt;
}

void clearCardCostImmune() {
    cardCostImmuneExpiresAt = 0;
}

void cardcostincrease(Card card, int costs, int counts) {
    if (costs <= 0 || counts <= 0 || isCardCostImmune()) {
        return;
    }
    cardCostIncreases.add(new double[] {
        card == null ? -1 : card.ordinal(), costs, Main.timer + counts });
}

void cardcostincreaseall(int costs, int counts)      { cardcostincrease(null, costs, counts); }
void strikecardcostincrease(int costs, int counts)   { cardcostincrease(Card.STRIKE, costs, counts); }
void blastcardcostincrease(int costs, int counts)    { cardcostincrease(Card.BLAST, costs, counts); }
void spmovecardcostincrease(int costs, int counts)   { cardcostincrease(Card.SPMOVE, costs, counts); }
void quirkcardcostincrease(int costs, int counts)    { cardcostincrease(Card.QUIRK, costs, counts); }
void ultimatecardcostincrease(int costs, int counts) { cardcostincrease(Card.ULT, costs, counts); }

// Extra ki currently added to this card by live (unexpired) entries. Prunes expired ones.
int currentCardCostIncrease(Card card) {
    int total = 0;
    for (int i = cardCostIncreases.size() - 1; i >= 0; i--) {
        double[] e = cardCostIncreases.get(i);
        if (Main.timer >= e[2]) {
            cardCostIncreases.remove(i);
        } else if (e[0] < 0 || (card != null && e[0] == card.ordinal())) {
            total += (int) e[1];
        }
    }
    return total;
}

void clearCardCostIncrease() {
    cardCostIncreases.clear();
}

// Every cost check and ki payment calls this one. Characters change their BASE
// costs by overriding baseKiRequired(Card); the debuff is added on top here.
final int kiRequired(Card card) {
    int base = baseKiRequired(card);
    if (card == null || base <= 0) {
        return base;
    }
    return base + currentCardCostIncrease(card);
}

int baseKiRequired(Card card){
    if (card == null) {
        return 0;
    }
    
    switch (card) {
        case STRIKE: return 19;
        case BLAST:  return 29;
        case QUIRK:  return 19;
        case SPMOVE: return 40;
        case ULT:    return 15;
        default: return 0;
    }
}

int getNeutralActionTime(NeutralAttack attack) {
    if (attack == null) {
        return 0;
    }
    switch (attack) {
        case TACKLE: return 2;
        case QUICK_ATTACK: return 1;
        default: return 0;
    }
}

int kiRequired(NeutralAttack attack) {
    if (attack == null) {
        return 0;
    }
    switch (attack) {
        case TACKLE: return 0;
        case QUICK_ATTACK: return 5;
        default: return 0;
    }
}


    /* =========================
       CONSTRUCTOR
       ========================= */
    Fighter(String name, double hp, int ki, int vanish, double strikedmg, double blastdmg, double spmovedmg,double rate,double clash)
    {
        this.name = name;
        this.hp = hp;
        this.maxhp = hp;
        this.ki = ki;
        this.vanish = vanish;
        this.strikedmg=strikedmg;
        this.blastdmg=blastdmg;
        this.spmovedmg=spmovedmg;
        this.rate=rate;
        this.clash=clash;
        configureColor();
        System.out.println("");
    }

    void configureColor() {
        String characterType = getClass().getSimpleName();

        switch (characterType) {
            case "ZenkaiGoku":
            case "Vegito":
            case "RoseGb":
                color = CharacterColor.RED;
                break;
            case "UIGoku":
            case "SOHTrunks":
            case "Frieza":
                color = CharacterColor.YELLOW;
                break;
            case "ZenkaiSSBEVegeta":
            case "FusionZamasu":
            case "ZenkaiVegeta":
                color = CharacterColor.PURPLE;
                break;
            case "Gohan":
            case "Superbaby2":
            case "SSJ4Gogeta":
                color = CharacterColor.GREEN;
                break;
            case "TagGokuVegeta":
            case "MajinVegeta":
            case "Beerus":
                color = CharacterColor.BLUE;
                break;
            case "Cooler":
                color = CharacterColor.PURPLE;
                superiorColor = true;
                break;
            case "SsjbVegeta":
                color = CharacterColor.YELLOW;
                superiorColor = true;
                break;
            case "Janemba":
                color = CharacterColor.GREEN;
                superiorColor = true;
                break;
            case "MajinVegeta2":
                color = CharacterColor.RED;
                superiorColor = true;
                break;
            case "GokuBlackRoseTyrant":
                color = CharacterColor.BLUE;
                superiorColor = true;
                break;
            case "MUIGoku":
                color = CharacterColor.GREEN;
                superiorColor = true;
                break;
            case "Ssj3Goku":
                color = CharacterColor.YELLOW;
                superiorColor = true;
                break;
            case "SsjbGoku":
                color = CharacterColor.BLUE;
                superiorColor = true;
                break;
            case "Cell":
                color = CharacterColor.PURPLE;
                superiorColor = true;
                break;
            case "BaseVegito":
                color = CharacterColor.RED;
                superiorColor = true;
                break;
            case "UltimateGohan":
                color = CharacterColor.YELLOW;
                superiorColor = true;
                break;
            case "Buuhan":
                color = CharacterColor.GREEN;
                superiorColor = true;
                break;
            case "MetalCooler":
                color = CharacterColor.BLUE;
                superiorColor = true;
                break;
            default:
                color = CharacterColor.RED;
                break;
        }

        if ("Vegito".equals(characterType)) {
            superiorColor = true;
        }
    }

     Teams myTeam;
     void assignTeam(Teams t) {
        this.myTeam = t;
     }

     void setSpecialMoveRange(AttackRange range) {
         if (range != null) {
             this.spMoveRange = range;
             this.isMeleeSpMove = range == AttackRange.MELEE;
             this.isRangedSpMove = range == AttackRange.LONG_RANGE;
         }
     }

     void setQuirkComboable(boolean comboable) {
         this.quirkComboable = comboable;
     }

     // ---- Universal Cover Change ----
     // coverCard  = the incoming card type this fighter covers for a teammate from the bench
     //              (null = cannot cover). A fighter only covers the card he is flagged for.
     // coverCombo = the card he fires back at the attacker right after covering
     //              (null = just covers, no follow-up).
     Card coverCard = null;
     Card coverCombo = null;

     void setCoverChange(Card covers, Card followUp) {
         this.coverCard = covers;
         this.coverCombo = followUp;
     }

    /* =========================
       VANISH SYSTEM
       ========================= */
void recoverVanish(int time) {
        int before = vanish;
        vanish += time*2;
        if (vanish < 0)
            vanish = 0;
        if (vanish > 100)
            vanish = 100;
            
             if (vanish != before) {
        // System.out.println("[VANISH] " + name + ": " + before + " → " + vanish);
    }
    }

    boolean useDangerSense() {
        if (vanish < 100) {
            return false;
        }
        vanish -= 100;
        return true;
    }

    void removeVanish(Fighter target) {
        if (target != null) {
            target.vanish = 0;
        }
    }

    /* =========================
       TURN ACTION
       ========================= */
    boolean performAction(Fighter target, Card card) {
    if (target == null || target.hp <= 0 || this.hp <= 0 || card == null) return false;
    if (isParalyzed || isFaint) return false;
    updateTimedStatuses();
    
    if (Main.timer > tempDefEndTime) tempDefDown = 0.0;
    if (Main.timer > tempDefUpEndTime) tempDefUp = 0.0;
    if (target.justDodged) {
            target.justDodged = false; // reset the flag for next time
            return false; // COMBO BREAKER!
        }
    if (target.justattacked) {
            target.justattacked = false; // reset the flag for next time
            return false; // COMBO BREAKER!
        }
   switch (card) {

    case STRIKE:
        return strike(target, strikedmg);

    case BLAST:
        return blast(target, blastdmg);

    case QUIRK:
        return quirk(target, quirkcannotbevanished);

    case SPMOVE:
        spmove(target, spmovedmg);
        return false;

    case ULT:
        ultimate(target, ultdmg);
        return false;

    default:
    return false;
    
}
}

boolean performNeutralAttack(Fighter target, NeutralAttack attack) {
    if (target == null || target.hp <= 0 || hp <= 0
            || attack == null || isParalyzed || isFaint) {
        return false;
    }

    switch (attack) {
        case TACKLE:
            return tackle(target);
        case QUICK_ATTACK:
            return quickAttack(target);
        default:
            return false;
    }
}

boolean guardNeutralAttack() {
    if (hp <= 0 || isParalyzed || isFaint
            || Math.random() >= Main.NEUTRAL_GUARD_CHANCE) {
        return false;
    }
    return true;
}

void recoverKi(int time) {
    int before=ki;
    ki+=(time+1);
        if (ki < 0)
            ki = 0;
        if (ki > 100)
            ki = 100;
            
            // System.out.println("[RECOVER KI] " + name + ": " + before + " → " + ki);
    }
    /* =========================
       DAMAGE HANDLING
       ========================= */
 double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul, double critical, Card type) {

    updateTimedBuffs();
    double actualDamage = baseDamage;
    double colorMultiplier = Main.colorDamageMultiplier(attacker, this);
    actualDamage *= colorMultiplier;

    if (colorMultiplier != 1.0) {
        System.out.println("🎨 " + Main.colorLabel(attacker) + " "
            + (colorMultiplier > 1.0 ? "advantage" : "disadvantage")
            + " multiplier: " + colorMultiplier + "x");
    }

    // CRITICAL
    if (critical > 0.8) {
        actualDamage *= 1.5;
        System.out.print("CRITICAL HIT!!! ");
    }

    // BASE DEFENSE
    double effectiveDefense = defenseMul * lbDefense;

    // DEFENSE DOWN
    if (Main.timer <= tempDefEndTime) {
        effectiveDefense -= tempDefDown;
    }

    // DEFENSE UP (FIXED)
    if (Main.timer <= tempDefUpEndTime) {
        effectiveDefense += tempDefUp;
    }

    effectiveDefense = Math.max(0, effectiveDefense);

    // DEFENSE FORMULA
    double defenseFactor = 1.0 / (1.0 + effectiveDefense);

    // APPLY DAMAGE
    actualDamage *= attackerDamageMul;
    actualDamage *= defenseFactor;

    // DAMAGE REDUCTION (FIXED)
    actualDamage *= (1 - currentDamageDown());

    // APPLY HP
    hp -= actualDamage;
    if (hp < 0) hp = 0;

    System.out.println(name + " took " + Math.round(actualDamage) +
                       " damage! HP left: " + Math.round(hp));

    return actualDamage;
}
void switchIn(Fighter target)
{
    setDrawSpeedFor(2,10);
    heal(30000);
    gainKi(30);
    myTeam.drawCards(1);
}


void switchOut()
{
}
    /* =========================
       ABILITIES
       ========================= */
boolean tackle(Fighter target) {
    if (target == null || target.hp <= 0 || hp <= 0) {
        return false;
    }

    ki = Math.max(0, ki - kiRequired(NeutralAttack.TACKLE));
    System.out.println("[" + (myTeam == null ? "Unassigned" : myTeam.teamName)
        + "] " + name + " uses TACKLE on " + target.name + "!");

    double finalScore = target.takeDamage(this,
        strikedmg * 0.45,
        (this.damageMul * lbDamage) * (1 + currentDamageBuff()),
        Math.random(), null);

    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    return finalScore > 0;
}

boolean quickAttack(Fighter target) {
    if (target == null || target.hp <= 0 || hp <= 0
        || ki < kiRequired(NeutralAttack.QUICK_ATTACK)) {
        return false;
    }

    ki -= kiRequired(NeutralAttack.QUICK_ATTACK);
    System.out.println("[" + (myTeam == null ? "Unassigned" : myTeam.teamName)
        + "] " + name + " uses QUICK ATTACK on " + target.name + "!");

    double finalScore = target.takeDamage(this,
        strikedmg * 0.30,
        (this.damageMul * lbDamage) * (1 + currentDamageBuff()),
        Math.random(), null);

    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    return false;
}

  boolean strike(Fighter target, double strikedmg) {
    int required = kiRequired(Card.STRIKE);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required)
        return false;

    ki -= required;
 System.out.println("[" + myTeam.teamName + "] "
    + name + " uses strike card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");


    double finalScore = target.takeDamage(this,
        strikedmg * mul,
        (this.damageMul * lbDamage)*(1 + currentDamageBuff()),
        Math.random(),Card.STRIKE
    );

    mul = 1;

    if (myTeam != null)
        myTeam.addDamage(finalScore);

    return true;
}

boolean blast(Fighter target, double blastdmg) {
    int required = kiRequired(Card.BLAST);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required)
        return false;

    ki -= required;
     System.out.println("[" + myTeam.teamName + "] "
    + name + " uses blast card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");

    double finalScore = target.takeDamage(this,
        blastdmg * mul,
        (this.damageMul * lbDamage)*(1 + currentDamageBuff()),
        Math.random(), Card.BLAST
    );

    mul = 1;

    if (myTeam != null)
        myTeam.addDamage(finalScore);

    return true;
}

// Overridable hook: called on the killer after a victim is defeated and swapped out.
void onKill(Fighter victim) { }

// Overridable hook: called on the ACTIVE fighter when an enemy switches in against him
// (tactical, tag and cover switches, and forced switches that a fighter triggers on purpose).
void onEnemySwitchIn(Fighter incoming) { }

boolean spmove(Fighter target, double spmovedmg) {
    int required = kiRequired(Card.SPMOVE);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required)
        return false;

    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
    + name + " uses blue card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");

    double finalScore = target.takeDamage(this,
        spmovedmg * mul,
        (this.damageMul * lbDamage)*(1 + currentDamageBuff()),
        Math.random(), Card.SPMOVE
    );

    mul = 1;

    if (myTeam != null)
        myTeam.addDamage(finalScore);

    return false;
}
boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required)
        return false;

    ki -= required;
    System.out.println("[" + myTeam.teamName + "] " + name + " uses ultimate card on [" 
        + target.myTeam.teamName + "] " + target.name + "!");

    double finalScore = target.takeDamage(this, ultdmg * mul, 
        (this.damageMul * lbDamage) * (1 + currentDamageBuff()), Math.random(), Card.ULT);

    mul = 1;
    if (myTeam != null) myTeam.addDamage(finalScore);
    ultunlocked=false;
    return false;   // non-comboable
}

boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    int required = kiRequired(Card.QUIRK);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required)
        return false;

    myTeam.drawCards(1);
    ki -= required;
    gainKi(40);

    if (Math.random() <= 0.5) {
        heal(250000);
        System.out.println("[" + myTeam.teamName + "] "
    + name + "heals and uses green card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");
    } else {
       applyDamageBuff(0.15, 15);
        System.out.println("[" + myTeam.teamName + "] "
    + name + " uses green card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");
    }
    return true;
}



    /* =========================
       MAIN ABILITY
       ========================= */
    void mainAbility(Fighter target) {
    }
    
    
    
    void cancelBuffs() {
       if(buffCancelImmune){
            System.out.println(name + "'s buffs cannot be CANCELLED!");
            return;
        }
    // Clear timed effects
    damageBuffs.clear();
    tempDefDown = 0;
    tempDefEndTime = 0;
    timedDamageBuff=0;
    mul=1;
    tempDefUp=0.0;
    damageDowns.clear();
    timedDamageDown=0.0;
    tempDefUpEndTime=0;
    clearEndNullify();
    if(!colourimmunitypermanent){
    clearColorImmunity();
    }
       
    System.out.println(name + "'s buffs were CANCELLED!");
        }

    void cancelAtributesUpdrades() {
    // Clear timed effects
    clearHandImmune();
    clearKiImmune();
    clearEndNullify();
    if(!colourimmunitypermanent){
    clearColorImmunity();
    }
       
    System.out.println(name + "'s buffs were CANCELLED ANYWAYS!");
        }
        
       void sealMain(Fighter target, int duration) {
    if (target == null) {
        return;
    }
    target.sealmain = true;
    target.sealmainTime = Main.timer + duration;
    System.out.println(target.name + "'s Main Ability SEALED!");
}

void updateSeal() {
    if (Main.timer >= sealmainTime) {
        sealmain = false;
    }
}

void die(Fighter attacker,Fighter killer, Teams myTeam) {
    hp = 0;
    if (myTeam != null) {
        System.out.println("💀 [" + myTeam.teamName + "] " + name + " is defeated!");
        Main.forceSwap(myTeam,attacker);
    }
}

void inflictBleed(Fighter target) {            // call instead of target.applyBleed()
    if (target == null || target.isImmune) return;
    target.bleedDamage += target.maxhp * 0.015;
    target.bleedEndTime = Main.timer + 10;
}
void inflictFaint(Fighter target) {            // call instead of target.applyFaint()
    if (target == null || target.isImmune) return;
    target.isFaint = true;
    target.faintEndTime = Main.timer + 3;
    System.out.println(target.name + " is FAINTED!");
}


}


/* =========================
   CHARACTERS
   ========================= */
class ZenkaiGoku extends Fighter{

int destroy=0;
    ZenkaiGoku(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul =3.2;   
        defenseMul =2.5;  
        ultdmg=2500000;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    
    
    @Override
    int baseKiRequired(Card card){
    if (card == null) {
        return 0;
    }
    
    switch (card) {
        case STRIKE: return 8;
        case BLAST:  return 15;
        case QUIRK:  return 8;
        case SPMOVE: return 35;
        case ULT:    return 10;
        default: return 0;
    }
    }
    
        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1500000);
        damageMul += 0.3;  
        defenseMul += 0.3;
        System.out.println("[" + myTeam.teamName + "] " + "I AM SON GOKU!");
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
      applyDamageBuff(0.50, 25);
      myTeam.hand[0]=Card.STRIKE;
      myTeam.hand[1] = Card.ULT;
        applyBuffCancelImmunity(10);
        super.mainAbility(target);
        
        }
        
}

@Override
    void switchIn(Fighter target){
        destroy=0;
        setDrawSpeedFor(2,10);
        myTeam.hand[1]=Card.STRIKE;
    super.switchIn(target);
    }
    
@Override
    boolean strike(Fighter target, double strikedmg) {
        if (hp < 1200000 && ki >=8) {
            System.out.println("GOKU IS ANGRY! Damage Boosted!");
            mul = 2;
        }
        target.applyTempDefenseDown(0.15, 5);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        if (hp < 2000000 && ki >= 15) {
            System.out.println("GOKU IS ANGRY! Damage Boosted!");
            mul = 2;
        }
        target.applyTempDefenseDown(0.15, 5);
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(30);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           target.cancelBuffs();
           sealMain(target, 10);
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}

    
    @Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    applyDamageDown(0.10, 5);
    applyTempDefenseUp(0.1,5);
    if(destroy<1){
    if (!attacker.handImmune) {
        for(int i=1;i<attacker.myTeam.hand.length-1;i++)
            attacker.myTeam.hand[i] =null;
    }
    destroy++;
    }
    return dmg;
}

}

class ZenkaiVegeta extends Fighter {

    ZenkaiVegeta(
            String name,
            double hp,
            int ki,
            int vanish,
            double strikedmg,
            double blastdmg,
            double spmovedmg,
            double rate,
            double clash) {
        super(name, hp, ki, vanish, strikedmg, blastdmg, spmovedmg, rate, clash);
        damageMul = 3.3;
        defenseMul = 2.7;
    }

    @Override
    int baseKiRequired(Card card) {
        if (card == null) return 0;

        switch (card) {
            case STRIKE: return 8;
            case BLAST:  return 15;
            case QUIRK:  return 8;
            case SPMOVE: return 35;
            case ULT:    return 10;
            default:     return 0;
        }
    }

    @Override
    void switchIn(Fighter target) {
        setDrawSpeedFor(2,10);
        applyImmuneHand(60);
        applyKiImmune(60);
        super.switchIn(target);
    }

    @Override
    void mainAbility(Fighter target) {
        if (sealmain || mainused || Main.timer <= 50
                || target == null || target.hp <= 0 || hp <= 0) {
            return;
        }

        mainused = true;
        ki = 100;
        vanish = 100;
        target.vanish = 0;
        heal(1000000);

        damageMul += 0.5;
        defenseMul += 0.4;
        applyDamageBuff(0.45, 25);
        setDrawSpeedFor(2,10);
        applyBuffCancelImmunity(25);
        applyImmuneHand(25);
        applyKiImmune(25);

        if (myTeam != null) {
            myTeam.forceCard(Card.SPMOVE);
        }

        System.out.println("I AM THE SAIYAN PRINCE!");
        System.out.println("Removed vanish from " + target.name);
        super.mainAbility(target);
    }

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        if (target == null || target.hp <= 0 || hp <= 0
                || ki < kiRequired(Card.QUIRK)) {
            return false;
        }

        if (myTeam != null) {
            myTeam.refillSlot(3);
        }
        setDrawSpeedFor(2,10);
        return super.quirk(target, quirkcannotbevanished);
    }

    @Override
    boolean strike(Fighter target, double strikedmg) {
        if (target != null && target.hp > 0 && hp > 0
                && ki >= kiRequired(Card.STRIKE)) {
            if (mainused) mul = 1.5;
            target.applyTempDefenseDown(0.15, 5);
        }
        return super.strike(target, strikedmg);
    }

    @Override
    boolean blast(Fighter target, double blastdmg) {
        if (target != null && target.hp > 0 && hp > 0
                && ki >= kiRequired(Card.BLAST)) {
            if (mainused) mul = 1.5;
            target.applyTempDefenseDown(0.15, 5);
        }
        return super.blast(target, blastdmg);
    }

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        if (target == null || target.hp <= 0 || hp <= 0
                || ki < kiRequired(Card.SPMOVE)) {
            return false;
        }

        if (mainused) mul = 1.5;
        applyDamageBuff(0.20, 5);
        target.applyTempDefenseDown(0.15, 5);

        // Base spmove returns false after a successful move because it
        // cannot extend a combo; validate first, then apply the Ki refund.
        super.spmove(target, spmovedmg);
        gainKi(25);
        return false;
    }

    @Override
    double takeDamage(Fighter attacker, double baseDamage,
                      double attackerDamageMul, double critical, Card type) {
        baseDamage*=0.5;
        double damage = super.takeDamage(
                attacker, baseDamage, attackerDamageMul, critical, type);

        if (hp <= 0 && !endurancehas
                && !isEnduranceNullifiedBy(attacker)) {
            endurancehas = true;
            hp = Math.min(maxhp, 1200000);
            System.out.println(name + " refuses to fall and keeps fighting!");
            return damage;
        }
        else{
            if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }

        if (hp > 0) {
            heal(100000);
            applyDamageDown(0.30, 5);
            applyTempDefenseUp(0.10, 5);
        }

        return damage;
    }
}

class UIGoku extends Fighter {
    
    int uniqueGauge = 100;      // full gauge
        int maxUniqueGauge = 100;
        int dodgeCount=4;          // max dodges per full gauge
        int decayPerTick = 1; 

    UIGoku(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 2.5;   
        defenseMul = 2.5;  
        ultdmg=1800000;
        justDodged=false;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(true);

    }
        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1280000);
        recoverUniqueGauge(100);
        damageMul += 0.5;  
        defenseMul += 0.3;
        System.out.println("[" + myTeam.teamName + "] "
    +"This fight's far from over,Here goes... Ultra Instinct!");
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.20, 15);
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
        
}

    @Override
    void switchIn(Fighter target) {
        uniqueGauge = maxUniqueGauge;
        dodgeCount = 4;
        System.out.println("[" + myTeam.teamName + "] "
    + name + " resets Unique Gauge! Ready to dodge again.");
        super.switchIn(target);
    }
    
    void decayUniqueGauge() {
        if (uniqueGauge > 0) {
            uniqueGauge -= decayPerTick;
            if (uniqueGauge < 0) uniqueGauge = 0;
        }
    }
    
    boolean tryUniqueDodge() {
        if (uniqueGauge >=33 && dodgeCount > 0) {
            dodgeCount--;
            uniqueGauge -= 33; // each dodge also reduces gauge
            uniqueGauge=Math.max(uniqueGauge,0);
            System.out.println("[" + myTeam.teamName + "] "
    + name + " SAYS TRY HARDER! (" + dodgeCount + " dodges left, gauge: " + uniqueGauge + ")");
            return true; // attack avoided
        }
        return false; // no dodge left, take damage normally
    }
    
    
@Override
    boolean strike(Fighter target, double strikedmg) {
        recoverUniqueGauge(10); 
        applyDamageBuff(0.15, 5);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        recoverUniqueGauge(10); 
        applyDamageBuff(0.15, 5);
        return super.blast(target,blastdmg);
    }
    
    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        recoverUniqueGauge(30); 
        applyDamageBuff(0.15, 5);
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    recoverUniqueGauge(40); 
    myTeam.drawCards(1);
    
    return super.quirk(target, quirkcannotbevanished); 
}

    
    void recoverUniqueGauge(int amount) {
    uniqueGauge = Math.min(uniqueGauge + amount, maxUniqueGauge);
    System.out.println(name + " recovers " + amount + " Unique Gauge! Current: " + uniqueGauge);
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if ((type == Card.STRIKE || type == Card.BLAST)  && !attacker.immuneguage) {
        if(tryUniqueDodge()){
        attacker.updateTimedStatuses();
        justDodged=true;
        return 0; // dodged!
        }
    }
    return super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
}


}

class ZenkaiSSBEVegeta extends Fighter {

boolean kiLowTriggered = false;
boolean fullguage=false;
int hit=5;

    ZenkaiSSBEVegeta(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.8;   
        defenseMul = 4.5;  
        ultdmg=3000000;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    
    @Override
void switchIn(Fighter target) {
    kiLowTriggered = false; // reset the one-time trigger
    super.switchIn(target); // keep all existing switchIn logic
}
    
    @Override
boolean performAction(Fighter target, Card card) {

    // Check Ki-trigger before action
    if (this.ki < 19 && !kiLowTriggered) {
        kiLowTriggered = true; // mark as triggered
        gainKi(50);
        System.out.println(name + " triggered LOW KI BOOST! +50 KI");
        drawCards(2);
    }

    return super.performAction(target, card);
}

    
    void attackguage(){
        if(hit>=5)
        {
            fullguage=true;
            if(fullguage==true){
            hit=0;
            heal(200000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            setDrawSpeedFor(2,10);
            System.out.println(name + " draws 1 card!");
            applyDamageBuff(0.55, 10);
            fullguage=false;
            myTeam.drawCards(1); 
    }
        }
        
    }
    
        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1400000);
        damageMul += 0.8;  
        defenseMul += 0.5;
        System.out.println("[" + myTeam.teamName + "] "
    + "I WILL SHATTER MY LIMITS IN MY OWN TERMS IN MY OWN WAY");
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
       applyDamageBuff(0.35, 15);
       myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
        
}
@Override
    boolean strike(Fighter target, double strikedmg) {
        hit++;
        attackguage();
        target.applyTempDefenseDown(0.15, 5);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        hit++;
        attackguage();
        target.applyTempDefenseDown(0.15, 5);
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        hit++;
        attackguage();
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    myTeam.drawCards(1);
    
    return super.quirk(target, quirkcannotbevanished); 
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    if(type==Card.SPMOVE || type==Card.ULT){
        heal(200000);
        baseDamage*=0.5;
        applyTempDefenseUp(2, 10);
        applyDamageDown(0.5, 10);
    }
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    return dmg;
}
   

}

class Gohan extends Fighter {
    
    int vanishcount=0;
    int special=0;
    int times=0;
    
    Gohan(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.0;   
        defenseMul = 2.8;  
        ultdmg=2000000;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }


        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"I GOT THIS!!");
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        this.vanish+=20;
        vanish=Math.min(vanish, 100);
        heal(150000);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        this.vanish+=20;
        vanish=Math.min(vanish, 100);
        hp = Math.min(hp, maxhp);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        if(vanishcount<=2){
        vanish=100;
        vanishcount++;
        }
        gainKi(30);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        if(special<=2){
           this.myTeam.forceCard(Card.SPMOVE);
           special++;
        }
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}


void disrupt(Fighter attacker){
    if(hp<=0)
    return;
  if(times==0){
if(this.hp<=2000000)
{
    if(!attacker.kiimmune)
    attacker.ki=0;
if(!attacker.handImmune){
    for (int i = 0; i < attacker.myTeam.hand.length; i++) {
    attacker.myTeam.hand[i] =null;
}
}
 heal(800000);
 applyDamageBuff(0.3, 10);
 times++;
}
}
        
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    disrupt(attacker);
    return dmg;
}

}

class Vegito extends Fighter {
        
        boolean main1Used=false;
        boolean main2Used=false;
        boolean fullguage=false; 
        int gethit=0;
        int hit=0;
        int buffCancelCharges = 0;
        
        Vegito(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.8;   
        defenseMul = 3.0;  
        drawspeed=1;
        applyKiImmune(120);
        applyImmuneHand(120);
        hasBlastArmor = true;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }


@Override
void mainAbility(Fighter target) {
    if(sealmain)
        return;
        else{

    // 🔹 FIRST MAIN → TRANSFORM
    if (!main1Used && Main.timer >= 25) {
        main1Used = true;

        System.out.println("[" + myTeam.teamName + "] "
    +"I am Super Vegito!");
        System.out.println(">>> TRANSFORMING...");

        name = "Super Vegito";

        damageMul += 0.8;
        defenseMul += 0.5;
        buffCancelCharges = 3;
        setDrawSpeedFor(2,15);
        applyBuffCancelImmunity(20);
        applyKiImmune(10);
        applyImmuneHand(10);
        ki = 100;

        heal(1500000);

        applyDamageBuff(0.35, 20);
        // Replace Vegito's own hand with his transformation loadout.
        myTeam.hand[0]=(Card.STRIKE);
        myTeam.hand[1]=(Card.STRIKE);
        myTeam.hand[2]=(Card.SPMOVE);
        myTeam.hand[3]=(Card.BLAST);
        return;
    }

    // 🔹 SECOND MAIN → POWER SPIKE
    if (main1Used && !main2Used && Main.timer >= 40) {

        main2Used = true;

        System.out.println("[" + myTeam.teamName + "] "
    +"YOU'RE FINISHED!!!");
        hit=4;
        ki = 100;
        vanish = 100;
        setDrawSpeedFor(2,15);

        damageMul += 0.5;

        applyDamageBuff(0.40, 15);
        applyBuffCancelImmunity(20);
        applyKiImmune(20);
        applyImmuneHand(20);

        // DISRUPT ENEMY
        if(!target.kiimmune)
        target.ki = 0;
    if(!target.handImmune){
        for (int i = 0; i < target.myTeam.hand.length; i++) {
            target.myTeam.hand[i] = null;
        }

        System.out.println(">>> Enemy hand destroyed!");
    }

        myTeam.forceCard(Card.SPMOVE);
        myTeam.forceCard(Card.QUIRK);
    }
        }
}
    
    @Override
    int baseKiRequired(Card card){
    if (card == null) {
        return 0;
    }
    
    switch (card) {
        case STRIKE: return 9;
        case BLAST:  return 19;
        case QUIRK:  return 9;
        case SPMOVE: return 35;
        case ULT:    return 15;
        default: return 0;
    }
    }
    
    
    void defenceguage(Fighter attacker){
        if(hp<=0)
        return;
        if(gethit>=5)
        {
            fullguage=true;
            if(fullguage==true){
            gethit=0;
            heal(500000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            if(!attacker.kiimmune)
            attacker.ki-=50;
            if(!attacker.handImmune)
             for (int i = 0; i < 2; i++) {
            attacker.myTeam.hand[i] = null;
            }
        attacker.applyTempDefenseDown(0.15, 5);
            myTeam.forceCard(Card.SPMOVE);
            fullguage=false;
            
        } 
    }
        
    }
    
    

      void attackguage(){
        if(hit>=4)
        {
            fullguage=true;
            if(fullguage==true){
            hit=0;
            heal(200000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            setDrawSpeedFor(2,10);
            System.out.println(name + " draws card!");
            applyDamageBuff(0.35, 10);
            applyImmuneHand(10);
            fullguage=false;
            myTeam.drawCards(1); 
    }
        }
        
    }    
    
    
    @Override
    boolean strike(Fighter target, double strikedmg) {
        hit++;
        attackguage();
        applyDamageBuff(0.05, 5);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        hit++;
        attackguage();
        applyDamageBuff(0.05, 5);
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        hit++;
        
        attackguage();
        applyDamageBuff(0.25, 5);
        target.applyTempDefenseDown(0.15, 5);
        if(main1Used && buffCancelCharges > 0){
        applyBuffCancelImmunity(10);
        EndNullify(10);
        buffCancelCharges--;
}
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    myTeam.drawCards(1);
        applyDamageBuff(0.15, 5);
        applyBuffCancelImmunity(10);
        applyKiImmune(10);
        setDrawSpeedFor(2,15);
    return super.quirk(target, quirkcannotbevanished); 
}


@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
     if(type == Card.SPMOVE || type == Card.ULT){
        applyTempDefenseUp(1.5, 5);
        baseDamage*=0.5;
     }
    if(type == Card.SPMOVE || type == Card.ULT)
    gethit+=3;
    else
    gethit++;
    if(hp>0){
    heal(50000);
    }
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    defenceguage(attacker);
    return dmg;
}
    
}

class Superbaby2 extends Fighter {

int hit=0;
int buffcancle=3;

    Superbaby2(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 2.5;  
        defenseMul = 3.2;  
        ultdmg=1200000;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    @Override
    void switchIn(Fighter target)
{
    heal(100000);
    gainKi(30);
    target.ki-=10;
    target.ki=Math.max(0,target.ki);
    ki=Math.min(ki, 100);
    myTeam.drawCards(1);
}
    
     @Override
    void mainAbility(Fighter target) {
        if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
            mainused=true;
            ultunlocked = true;
            heal(1000000);
            switchCD = Math.max(0, switchCD - 5);
            myTeam.healTeam(0.25);
            applyDamageDown(0.35, 10);
            applyTempDefenseUp(0.2,10);
            System.out.println("[" + myTeam.teamName + "] "
    +"Tuffles will rule!");
            System.out.println(">>> Team healed by 25%!");
            damageMul +=0.3;
            defenseMul +=0.6;
                    applyDamageBuff(0.8, 10);
       myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
            
    }
    
     @Override
    boolean strike(Fighter target, double strikedmg) {
        target.ki = Math.max(0, target.ki - 10);
        applyDamageBuff(0.15, 2);
        heal(50000);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        target.ki = Math.max(0, target.ki - 10);
        heal(50000);
        applyDamageBuff(0.15, 2);
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        target.ki = Math.max(0, target.ki - 30);
        applyDamageBuff(0.25, 2);
         target.applyTempDefenseDown(0.15, 5);
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    myTeam.drawCards(1);
    heal(100000);
    hit++;
        applyDamageBuff(0.15, 5);
    
    return super.quirk(target, quirkcannotbevanished); 
}
    
    
    void disrupt(Fighter attacker){
        if(hp<=0)
        return;
        if(hit>=5){
    for (Fighter f : attacker.myTeam.fighters) {
    f.switchCD += 3;
            }
            gainKi(50);
            if(!attacker.kiimmune)
            attacker.ki = Math.max(0, attacker.ki - 50);
            attacker.vanish=0;
            setDrawSpeedFor(2,15);
            attacker.applyTempDefenseDown(0.25, 3);
            myTeam.forceCard(Card.SPMOVE);
            heal(400000);
            hit=0;
        }
            
    }
    
    @Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    double dmg;
    if(type == Card.SPMOVE || type == Card.ULT){
        if(buffcancle>0)
        {
        baseDamage*=0.7;
        attacker.cancelBuffs();
        buffcancle--;
        }
    heal(250000);
    applyDamageDown(0.15, 10);
    applyTempDefenseUp(0.2,10);
    dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    hit+=3;
    }
    else{
    applyTempDefenseUp(0.15,2);
       dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    hit++;
    }
    disrupt(attacker);
    return dmg;
}
    
    
}

class RoseGb extends Fighter {
    
    int ult=0;
    int qult=0;
    int buffcancle=3;
    
    RoseGb(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.5;  
        defenseMul = 3.0;  
        ultdmg=1600000;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    
    
    
            @Override
    void mainAbility(Fighter target) {
        if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1400000);
        damageMul += 0.8;  
        defenseMul += 0.5;
        System.out.println("[" + myTeam.teamName + "] "
    + "WORSHIP ME,I AM DIVINITY!!");
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
}


@Override
boolean ultimate(Fighter target, double ultdmg) {
    
    int required = kiRequired(Card.ULT);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }

    ki -= required;
    ult++;
     System.out.println("[" + myTeam.teamName + "] "
    + name + " uses ultimate card on ["
    + target.myTeam.teamName + "] "
    + target.name + "!");

    double finalScore = target.takeDamage(this,
        ultdmg * mul,
        (this.damageMul * lbDamage)*(1 + currentDamageBuff()),
        Math.random(),Card.ULT
    );
    bleed(target);
    paralysis(target);
    if(ult==3)
    ultunlocked=false;
    if (myTeam != null)
        myTeam.addDamage(finalScore);

    return false;
}

        @Override
    boolean strike(Fighter target, double strikedmg) {
        bleed(target);
        applyDamageBuff(0.15, 5);
        if(Math.random()<0.05)
        faint(target);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
       applyDamageBuff(0.15, 5);
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        bleed(target);
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.15, 5);
        if (Math.random() < 0.35) {
    paralysis(target);
}
        target.cancelBuffs();
        sealMain(target, 10);
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    myTeam.drawCards(1);
        if(qult<=1){
        myTeam.forceCard(Card.ULT);  
        ultunlocked=true;
        qult++;
        }
        applyDamageBuff(0.15, 5);
    
    return super.quirk(target, quirkcannotbevanished); 
}

void bleed(Fighter target){
    if(target == null || target.isImmune){
    return;
    }
    target.bleedDamage += target.maxhp * 0.015; 
    target.bleedEndTime = Main.timer + 10;
}

void paralysis(Fighter target) {
    if(target.isImmune){
    return;
    }
    target.isParalyzed = true;
    target.paralysisEndTime = Main.timer + 10; // lasts 10 counts
}

void faint(Fighter target) {
    if(target == null || target.isImmune){
    return;
    }
    target.isFaint = true;
    target.faintEndTime = Main.timer + 3; // 3 timer counts (balanced)
    System.out.println(target.name + " is FAINTED!");
}



@Override
double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul, double critical, Card type) {

    // 🔴 CANCEL BUFFS if hit by strong attacks
    if (type == Card.SPMOVE || type == Card.ULT) {
        if(buffcancle>0)
        {
        baseDamage*=0.5;
        attacker.cancelBuffs();
        buffcancle--;
        }
    }

    // 🔢 Calculate damage FIRST (but don't apply yet)
    double actualDamage = baseDamage
        * Main.colorDamageMultiplier(attacker, this);

    // CRITICAL
    if (critical > 0.8) {
        actualDamage *= 1.5;
        System.out.print("CRITICAL HIT!!! ");
    }

    // DEFENSE CALCULATION
    double effectiveDefense = defenseMul * lbDefense;

    if (Main.timer <= tempDefEndTime) {
        effectiveDefense -= tempDefDown;
    }

    if (Main.timer <= tempDefUpEndTime) {
        effectiveDefense += tempDefUp;
    }

    effectiveDefense = Math.max(0, effectiveDefense);
    double defenseFactor = 1.0 / (1.0 + effectiveDefense);

    actualDamage *= attackerDamageMul;
    actualDamage *= defenseFactor;

    // DAMAGE REDUCTION BUFF
    actualDamage *= (1 - currentDamageDown());

    // 🟡 ENDURANCE CHECK (BEFORE HP DROPS TO 0)
    if (!endurancehas && hp - actualDamage <= 0
            && !isEnduranceNullifiedBy(attacker)) {
        endurancehas = true;
        hp = 1500000; // endurance HP
        System.out.println(name + " ENDURES THE ATTACK!");
        return actualDamage;
    }
    else{
        if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }

    // 🔻 APPLY DAMAGE
    hp -= actualDamage;
    if (hp < 0) hp = 0;

    System.out.println(name + " took " + Math.round(actualDamage) +
                       " damage! HP left: " + Math.round(hp));

    return actualDamage;
}

}

class TagGokuVegeta extends Fighter {
    
    int chargeswitch=0;
    int switches=0;
    int usedstrike=2;
    int usedblast=2;
    int spmoveused=0;
    int count=0;
    int buffCancelCharges=3;
    
    TagGokuVegeta(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 2.8;  
        defenseMul = 2.8;
        applyImmune(120);
        setSpecialMoveRange(AttackRange.MELEE);
    }
    
    @Override
    boolean strike(Fighter target, double strikedmg) {
        chargeswitch+=40;
        chargeswitch=Math.min(100,chargeswitch);
        applyDamageBuff(0.15, 5);
        switchAbility();
        if(switches>=4 && usedstrike>0){
            usedstrike--;
            gainKi(10);
        myTeam.drawCards(1);
    }
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        chargeswitch+=40;
        chargeswitch=Math.min(100,chargeswitch);
        applyDamageBuff(0.15, 5);
        switchAbility();
        if(switches>=4 && usedblast>0){
            usedblast--;
            gainKi(10);
        myTeam.drawCards(1);
        }
        return super.blast(target,blastdmg);
    }
    
     @Override
    boolean spmove(Fighter target, double spmovedmg) {
        chargeswitch+=100;
        chargeswitch=Math.min(100,chargeswitch);
        applyDamageBuff(0.25, 2);
        switchAbility();
        if(spmoveused<=1){
        this.myTeam.forceCard(Card.QUIRK);
        spmoveused++;
        }
        if(this.name.equals("Super Gogeta") && buffCancelCharges>0)
        {
        buffCancelImmune = true;
        EndNullify(10);
        buffCancelCharges--;
        }
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    myTeam.drawCards(1);
        chargeswitch+=50;
        chargeswitch=Math.min(100,chargeswitch);
        this.vanish+=70;
        vanish=Math.min(100,vanish);
        applyDamageBuff(0.15, 5);
        switchAbility();
    
    return super.quirk(target, quirkcannotbevanished); 
}

void switchAbility()
{
    if(name.equals("Angel Goku")){
    applyImmune(120);
    if(chargeswitch>=100)
    {
    System.out.println("Switches to Angel Vegeta");
    this.name="Angel Vegeta";
    fuse();
    chargeswitch = 0;
    gainKi(50);
    heal(300000);
     myTeam.fillHand();
    this.vanish+=70;
    switches++;
    vanish=Math.min(100, vanish);
    applyDamageBuff(0.25, 5);
    }
    }
    else if(name.equals("Angel Vegeta") && chargeswitch >= 100)
    {
    applyImmune(120);
    System.out.println("Switches to Angel Goku");
    this.name="Angel Goku";
    fuse();
    chargeswitch = 0;
    gainKi(50);
    heal(300000);
     myTeam.fillHand();
    this.vanish+=70;
    switches++;
    vanish=Math.min(100, vanish);
    applyDamageBuff(0.25, 5);
    }
}
    
    void fuse(){
        if(switches>=4 && !name.equals("Super Gogeta"))
         {
           applyImmune(120);
           hasBlastArmor=true;
            name="Super Gogeta";
            System.out.println("I am Neither Goku nor Vegeta I am here to destroy you ");
            ki=100;
            maxhp=3500000 * lbHp;
            hp=maxhp;
            myTeam.fillHand();
            vanish=100;
            setDrawSpeedFor(2,10);
            this.myTeam.forceCard(Card.QUIRK);
            damageMul+=1.2;
            defenseMul+=0.5;
            strikedmg+=150000;
            blastdmg+=150000;
            spmovedmg+=400000;
            EndNullify(10);
        }
    }
    
    @Override
    void switchIn(Fighter target){
            applyImmune(120);
            setDrawSpeedFor(2,10);
            for(Fighter f : myTeam.fighters) {
            if (f!=this && f.hp > 0) 
            count++;
            }
            if(count<=1){
            switches=4;
            fuse();
            }
            count=0;
        if(switches>=4)
        {
            applyDamageBuff(0.3, 10);
            EndNullify(10);
            super.switchIn(target);
        }
    }
    
    @Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    chargeswitch+=30;
    switchAbility();
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    return dmg;
}
    
    @Override
     void mainAbility(Fighter target) {
    }

}

class MajinVegeta extends Fighter {
    
    int usedcards=4;
    
    MajinVegeta(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.5;   
        defenseMul = 3.0;  
        ultdmg=2500000;
        drawspeed=1;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }
    

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 25)
            return;
        mainused = true;
        ultunlocked=true;
        applyImmuneHand(30);
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"I will disintegrate you into thin air!!");
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        applyImmune(10);
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(100000);
        if(usedcards>0){
        this.myTeam.forceCard(Card.QUIRK);
        usedcards--;
        }
        return super.strike(target,strikedmg);
        
    }
        
    @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(100000);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        if(usedcards>0){
        this.myTeam.forceCard(Card.QUIRK);
        usedcards--;
        }
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(30);
        if(usedcards>0){
        this.myTeam.forceCard(Card.QUIRK);
        usedcards--;
        }
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}

@Override
   void cancelBuffs() {
        
        if(buffCancelImmune){
            System.out.println(name + "'s buffs cannot be CANCELLED!");
            buffCancelImmune=false;
            return;
        }
        super.cancelBuffs(); 
   }
   
       @Override
    boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }
    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
    + name + " uses ultimate card on " + target.name + "!");
    target.cancelBuffs();
    applyBuffCancelImmunity(5);
    EndNullify(10);
    sacrifice=true;
    double finalScore = target.takeDamage(this,
        ultdmg * 3,
        (this.damageMul * lbDamage),
        1,Card.ULT
    );
    target.vanish=0;
    for (Fighter f : target.myTeam.fighters) {
    f.switchCD += 3;
    }
    System.out.println("I WILL TAKE YOU WITH ME!");
    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    target.myTeam.DamageTeam(this, finalScore*0.5);
    die(target,this,myTeam);
    ultunlocked = false; 
    return false;
    }
   
   
   
@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
         return dmg;
}
}

class SOHTrunks extends Fighter {

    int gethit = 0;
    int buffcancle = 3;
    int ultcancle = 0;
    int superhit = 0;
    boolean fullguage = false;

    SOHTrunks(String name, double hp, int ki, int vanish, double strikedmg, double blastdmg, double spmovedmg, double rate, double clash) {
        super(name, hp, ki, vanish, strikedmg, blastdmg, spmovedmg, rate, clash);
        damageMul = 3.8;
        defenseMul = 4.2;
        ultdmg = 2800000;
        justattacked = false;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }

    @Override
    void assignTeam(Teams t) {
        super.assignTeam(t);
        if (t != null) {
            t.MAX_COOLDOWN = 10;
        }
    }

    @Override
    int baseKiRequired(Card card) {
        if (card == null) {
            return 0;
        }

        switch (card) {
            case STRIKE: return 9;
            case BLAST:  return 19;
            case QUIRK:  return 9;
            case SPMOVE: return 35;
            case ULT:    return 15;
            default: return 0;
        }
    }

    @Override
    void mainAbility(Fighter target) {
        if (sealmain)
            return;
        else {
            if (mainused || Main.timer <= 50)
                return;
            mainused = true;
            ultunlocked = true;
            ki = 100;
            heal(1200000);
            damageMul += 0.5;
            defenseMul += 0.3;
            setDrawSpeedFor(2, 10);
            hp = Math.min(hp, maxhp);
            applyDamageBuff(0.35, 15);
            System.out.println("[" + myTeam.teamName + "] "
                    + "I will end this!");
                    myTeam.hand[0]=Card.STRIKE;
            myTeam.hand[1] = Card.ULT;
        }
    }

    @Override
    boolean strike(Fighter target, double strikedmg) {
        gainKi(5);
        superhit++;
        applyDamageBuff(0.05, 5);
        ki = Math.min(ki, 100);
        heal(150000);
        hopeattack(target);
        return super.strike(target, strikedmg);
    }

    @Override
    boolean blast(Fighter target, double blastdmg) {
        heal(150000);
        gainKi(5);
        applyDamageBuff(0.05, 5);
        superhit++;
        ki = Math.min(ki, 100);
        hp = Math.min(hp, maxhp);
        hopeattack(target);
        return super.blast(target, blastdmg);
    }

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyBuffCancelImmunity(10);
        EndNullify(10);
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.15, 5);
        hopeattack(target);
        return super.spmove(target, spmovedmg);
    }

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        heal(150000);
        applyDamageBuff(0.05, 5);
        applyImmuneHand(10);
        gethit++;
        superhit += 2;
        hopeattack(target);
        return super.quirk(target, quirkcannotbevanished);
    }

    void defenceguage(Fighter attacker) {
        if (hp <= 0)
            return;
        if (gethit >= 5) {
            gethit = 0;
            heal(300000);
            gainKi(30);
            hp = Math.min(hp, maxhp);
            ki = Math.min(ki, 100);
            attacker.applyTempDefenseDown(0.15, 5);
            myTeam.forceCard(Card.SPMOVE);
            System.out.println("Switching to release guage");
            releaseguageend(15);
        }
    }

    void hopeattack(Fighter attacker) {
        if (superhit >= 15) {
            applyColorImmunity(5);
            permanentendurancenull=true;
            attacker.cancelBuffs();
            attacker.cancelAtributesUpdrades();
            applyDamageBuff(2, 5);
            updateTimedStatuses();
            System.out.println("I WILL FINISH IT!!");
            superhit = 0;   // reset BEFORE the nested takeDamage so a reaction can't re-enter this
            double dmg = this.ultdmg * 0.5;
            applyIndestructibleImmunity(3);
            double dam = attacker.takeDamage(this, dmg, (this.damageMul * lbDamage) * (1 + currentDamageBuff()), 0, null);
            attacker.myTeam.DamageTeam(this, dam * 0.5);
            System.out.println("hp left " + (int) attacker.hp);
            justattacked = true;
            myTeam.addDamage(dam);
            myTeam.addDamage(dam * 0.5);
            System.out.println(this.name + " dealt " + dam + " to " + attacker.myTeam.teamName);
            clearIndestructibleImmunity();
            permanentendurancenull=false;
            if (Main.checkDeathAndHandle(attacker.myTeam, this))
                return;
        }
    }

    @Override
    double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul, double critical, Card type) {
        // Indestructible (release guage): refresh the timer flag first so it can't outlive its 15 counts.
        updateTimedEndIndestructible();
        if (releaseguagetimer && !attacker.immuneindestructible) {
            System.out.println("Indestructible Activated can't DIE!!");
            return 0;
        }

        if (type == Card.SPMOVE) {
            if (buffcancle > 0) {
                baseDamage*=0.3;
                applyColorImmunity(10);
                // applyDamageDown(0.5, 5);   // NOTE: had no expiry in the old code, so it never took effect
                applyTempDefenseUp(2, 5);
                attacker.cancelBuffs();
                buffcancle--;
            }
            gethit += 3;
            superhit += 3;
            return super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);
        }

        if (type == Card.ULT) {
            if (!attacker.cannotultnull && !attacker.sacrifice) {
                if (ultcancle < 1) {
                    ultcancle = 1;   // set before the nested takeDamage
                    System.out.println("NO ONE CAN TAKE MY HOME FROM ME!!");
                    System.out.println("ULTIMATE NULLIFIED");
                    double dam = attacker.takeDamage(this, ultdmg * 0.5, (this.damageMul * lbDamage) * (1 + currentDamageBuff()), 0, null);
                    myTeam.addDamage(dam);
                    myTeam.addDamage(attacker.spmovedmg * 0.7);
                    System.out.println(attacker.name + " hp left " + (int) attacker.hp);
                    attacker.myTeam.DamageTeam(this, attacker.spmovedmg * 0.7);
                    System.out.println(this.name + " dealt " + (attacker.spmovedmg * 0.7) + " to " + attacker.myTeam.teamName);
                    justDodged = true;
                    return 0;
                } else {
                    baseDamage*=0.3;
                    applyColorImmunity(10);
                    applyTempDefenseUp(2, 5);
                    attacker.cancelBuffs();
                }
                gethit += 3;
                superhit += 3;
            }
            return super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);
        }

        // Strike / Blast / Quirk / neutral hits
        gethit++;
        superhit++;
        if (hp > 0) {
            heal(50000);
            defenceguage(attacker);
            hopeattack(attacker);
        }

        // If this very hit just switched on the release guage, absorb it too.
        if (releaseguagetimer && !attacker.immuneindestructible) {
            System.out.println("Indestructible Activated can't DIE!!");
            return 0;
        }
        // applyDamageDown(0.3, 5);   // NOTE: had no expiry in the old code, so it never took effect
        applyTempDefenseUp(0.4, 5);
        return super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);
    }
}

class FusionZamasu extends Fighter {
    
    int special=0;
    int times=0;
    int buffcancle=3;
    int supercharge=0;
    
    FusionZamasu(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.5;   
        defenseMul = 4.0;  
        justattacked=false;
        revive=true;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"You cannot defy a GOD!!!");
        myTeam.hand[0] = Card.SPMOVE;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        gainKi(5);
        this.vanish+=20;
        heal(250000);
        supercharge++;
        defy(target);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        heal(250000);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        supercharge++;
        defy(target);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.25, 5);
        target.applyTempDefenseDown(0.35, 5);
        gainKi(30);
        supercharge++;
        ki=Math.min(ki, 100);
                if (Math.random() < 0.35) {
    paralysis(target);
}
        target.cancelBuffs();
        defy(target);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        if(special<=2){
           this.myTeam.forceCard(Card.SPMOVE);
           special++;
        }
           applyDamageBuff(0.15, 10);
               applyDamageDown(0.15, 10);
               applyTempDefenseUp(0.2,10);
           heal(150000);
           supercharge+=2;
           defy(target);
    return super.quirk(target,quirkcannotbevanished); 
}


void revival(Fighter attacker){
    if(revive && !attacker.sacrifice){
    if(times==0){
System.out.println("I WILL DESTROY YOU ALL FOR THIS COSMOS FOR DIVINITY");
 hp=maxhp;
 hp=Math.min(maxhp, hp);
 applyDamageBuff(0.3, 10);
 this.name=("Corrupted Merged Zamasu");
 damageMul += 0.8;   
 defenseMul += 0.8;
 special=2;
 buffcancle=3;
 sealMain(attacker, 10);
 mainused=false;
 times++;
 revive=false;
    }
    }
}



boolean inDefy = false;

void defy(Fighter attacker){
    if (inDefy) return;
    if(times==1 && supercharge>=20){
    inDefy = true;
    try {
    System.out.println("You havent learned anything Mortals!!");
    applyIndestructibleImmunity(5);
    permanentendurancenull=true;
    double dmg=this.spmovedmg*2;
           double dam=attacker.takeDamage(this, dmg,(this.damageMul * lbDamage)*(1 + currentDamageBuff()) , 0, null);
    attacker.myTeam.DamageTeam(this, dam*0.5);
    System.out.println(this.name+" dealt "+dam+" to "+attacker.myTeam.teamName);
    supercharge=0;
    System.out.println("hp left "+(int)attacker.hp);
    justattacked=true;
    myTeam.addDamage(dam);
    myTeam.addDamage(dam*0.5);
    clearIndestructibleImmunity();
    permanentendurancenull=false;
    if (Main.checkDeathAndHandle(attacker.myTeam, this)) 
            return;
    } finally {
        inDefy = false;
    }
    }
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {

    if (type == Card.SPMOVE || type == Card.ULT) {
            heal(300000);
            if (buffcancle > 0) {
            attacker.clearBuffCancelImmunity();
            attacker.cancelBuffs();
            buffcancle--;
            supercharge += 3;
            defy(attacker);
        }
        }
        else {
        supercharge++;
        defy(attacker);
        applyTempDefenseUp(0.2, 10);

    }

    double dmg = super.takeDamage(
            attacker, baseDamage, attackerDamageMul, critical, type);
            if(hp<=0)
            revival(attacker);
        return dmg;

        
    
    } 


void paralysis(Fighter target) {
    if(target.isImmune){
    return;
    }
    target.isParalyzed = true;
    target.paralysisEndTime = Main.timer + 10; // lasts 10 counts
}

}

class SSJ4Gogeta extends Fighter {
    
    int gethit=0;
    boolean hitback=false;
    
    SSJ4Gogeta(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.0;   
        defenseMul = 3.5;  
        ultdmg=2800000;
        hasBlastArmor=true;
        applyImmune(120);
        justDodged=false;
        setSpecialMoveRange(AttackRange.MELEE);
    }
    

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"it's Over For you!!");
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        colourNullGrantsPermanent=true;
        applyColorImmunity(10);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(30);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           colourNullGrantsPermanent=true;
           applyColorImmunity(10);
           heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}


    void defenceguage(Fighter attacker){
        if(hp<=0)
        return;
        if(gethit>=3)
        {
            heal(200000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            myTeam.forceCard(Card.SPMOVE);
            hitback=true;
    }
        
    }

    @Override
    void switchIn(Fighter target){
        cannotultnull=false;
        immuneindestructible=false;
            
    }


@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(hitback){
        if(!attacker.handImmune){
        for (int i = 0; i < attacker.myTeam.hand.length; i++) {
            attacker.myTeam.hand[i] = null;
        }
    }
        System.out.println("BLINK!!");
        hitback=false;
        if(!attacker.kiimmune){
        attacker.ki-=50;
        attacker.ki=Math.min(attacker.ki, 100);
        }
        colourNullGrantsPermanent=true;
        applyColorImmunity(10);
        applyDamageBuff(0.55, 2);
        attacker.applyTempDefenseDown(0.50, 2);
        attacker.vanish=0;
        applyCannotUltNullification(10);
        applyIndestructibleImmunity(10);
        gethit=0;
        justDodged=true;
        return 0;    
    }
    if(type == Card.SPMOVE || type == Card.ULT){
    gethit+=2;
    baseDamage*=0.3;
    applyColorImmunity(10);
    applyDamageDown(0.5, 10);
    applyTempDefenseUp(2, 10);
    heal(300000);
    }
    else{
    gethit++;
    }
    baseDamage*=0.7;
    applyTempDefenseUp(0.2,10);
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    defenceguage(attacker);
    return dmg;
}
}

class Cooler extends Fighter {
    
        boolean main1Used=false;
        boolean main2Used=false;
        boolean fullguage=false; 
    
    Cooler(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.3;   
        defenseMul = 3.3;  
        ultdmg=2500000;
        hasBlastArmor=true;
        applyBuffCancelImmunity(120);
       setDrawSpeedFor(2,60);
        setSpecialMoveRange(AttackRange.MELEE);
    }
    
    
        @Override
    void switchIn(Fighter target){
            applyImmune(10);
            applyBuffCancelImmunity(10);
            applyDamageBuff(0.35, 10);
            super.switchIn(target);
        }
    
        @Override
void mainAbility(Fighter target) {
    if(sealmain)
        return;
        else{

    // 🔹 FIRST MAIN → TRANSFORM
    if (!main1Used && Main.timer >= 25) {
        main1Used = true;

        System.out.println("[" + myTeam.teamName + "] "
    +"I am Strongest In the Universe!");
        System.out.println(">>> TRANSFORMING...");

        name = "Final Form Cooler";

        damageMul += 0.8;
        defenseMul += 0.5;

        ki = 100;

        heal(1500000);

        applyDamageBuff(0.35, 20);
    for (int i = 0; i <myTeam.hand.length; i++) {
    myTeam.hand[i] =null;
}
        myTeam.hand[0]=(Card.STRIKE);
        myTeam.hand[1]=(Card.STRIKE);
        myTeam.hand[2]=(Card.SPMOVE);
        myTeam.hand[3]=(Card.STRIKE);
        return;
    }

    // 🔹 SECOND MAIN → POWER SPIKE
    if (main1Used && !main2Used && Main.timer >= 50) {

        main2Used = true;

        System.out.println("[" + myTeam.teamName + "] "
    +"You SHOULD DIE!!!");
        ki = 100;
        vanish = 100;
        drawspeed+=1;

        damageMul += 0.5;
        defenseMul +=0.5;

        applyDamageBuff(0.40, 20);

        // DISRUPT ENEMY
        if(!target.kiimmune)
        target.ki = 0;
        if(!target.handImmune){
        for (int i = 0; i < target.myTeam.hand.length; i++) {
            target.myTeam.hand[i] = null;
        }

        System.out.println(">>> Enemy hand destroyed!");
    }

        myTeam.forceCard(Card.SPMOVE);
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1]=(Card.ULT);
    }
        }
}

        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.3, 5);
        target.applyTempDefenseDown(0.15, 5);
        gainKi(10);
        this.vanish+=20;
        vanish=Math.min(vanish, 100);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.3, 5);
        target.applyTempDefenseDown(0.15, 5);
        gainKi(10);
        this.vanish+=20;
        vanish=Math.min(vanish, 100);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyBuffCancelImmunity(10);
        applyDamageBuff(0.5, 2);
        target.applyTempDefenseDown(0.5, 3);
        gainKi(30);
        target.cancelBuffs();
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}


    @Override
    boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }
    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
    + name + " uses ultimate card on " + target.name + "!");
    applyBuffCancelImmunity(10);
    double finalScore = target.takeDamage(this,
        ultdmg * mul,
        (this.damageMul * lbDamage),
        1,Card.ULT
    );
    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    ultunlocked = false; 
    return false;   // non-comboable
    }

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    return dmg;
}

}

class Frieza extends Fighter {
    
    int times=0;
    int special=0;
    Frieza(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.8;   
        defenseMul = 4.5;  
        ultdmg=2500000;
        endurancehas=false;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
    }
    

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"I AM THE EMPEROR!!");
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);

        hp = Math.min(hp, maxhp);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(30);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        if(special<=2){
          this.myTeam.forceCard(Card.SPMOVE);
          special++;
        }
          applyDamageBuff(0.15, 10);
          drawspeed=2;
          heal(300000);
    return super.quirk(target, quirkcannotbevanished); 
}


void disrupt(Fighter attacker){
    if(hp<=0)
    return;
  if(times<=2){
if(this.hp<=2500000)
{
    if(!attacker.kiimmune)
    attacker.ki=0;
    if(!attacker.handImmune)
    for (int i = 0; i < attacker.myTeam.hand.length; i++) {
    attacker.myTeam.hand[i] =null;
}
 heal(800000);
 damageMul += 0.2;  
 defenseMul += 0.3;
 if(times ==0){
 System.out.println("Tranforms to 2nd Form");
 this.name="2nd Form frieza";
 }
  else if(times ==1){
 System.out.println("Tranforms to 3rd Form");
 this.name="3rd Form frieza";
 }
 else if(times ==2){
 System.out.println("Tranforms to Final Form");
 this.name="Final Form frieza";
 }
  times++;
 
}
}
        
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    attacker.cancelBuffs();
     if((type == Card.SPMOVE || type == Card.ULT) && !attacker.kiimmune){
        attacker.ki=0;
     }
    heal(150000);
    applyDamageDown(0.25, 3);
    applyTempDefenseUp(0.2,2);
    double hpBeforeDamage = hp;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    if (!endurancehas && hpBeforeDamage - dmg <= 0
            && !isEnduranceNullifiedBy(attacker)) {
        endurancehas = true;
        hp = 1500000; // endurance HP
        System.out.println(name + " ENDURES THE ATTACK!");
        return dmg;
    }
    else{
        if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }

    disrupt(attacker);
    return dmg;
}

}

class Janemba extends Fighter {

    int dimensionalPressure = 0;
    int maxDimensionalPressure = 3;
    int vanished=0;
    int usedstrike=2;
    int usedblast=2;
    int ultimate=0;

    Janemba(String name, double hp, int ki, int vanish,
            double strikedmg, double blastdmg, double spmovedmg,
            double rate, double clash) {

        super(name, hp, ki, vanish, strikedmg, blastdmg,
              spmovedmg, rate, clash);

        damageMul = 3.8;
        defenseMul = 3.3;
        ultdmg = 1800000;
        drawspeed = 1;
        endurancehas=false;
        applyImmuneHand(10);
        applyKiImmune(10);
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }

    // ==========================================
    // DIMENSIONAL PRESSURE
    // ==========================================

    void buildDimensionalPressure() {

        dimensionalPressure++;

        if (dimensionalPressure >= maxDimensionalPressure) {

            dimensionalPressure = 0;

            // Major but short offensive burst
            applyDamageBuff(0.35, 10);

            // Resource recovery
            gainKi(40);

            // Partial vanish recovery
            vanish=100;

            System.out.println(
                "[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] "
                + name + "'s DIMENSIONAL PRESSURE IS FULL!"
            );

            System.out.println(
                name + " tears reality apart!"
            );

            usedstrike=0;
            usedblast=0;

        }
    }
void bleed(Fighter target){
    if(target.isImmune){
    return;
    }
    target.bleedDamage += target.maxhp * 0.015; 
    target.bleedEndTime = Main.timer + 10;
}
void faint(Fighter target) {
    if(target.isImmune){
    return;
    }
    target.isFaint = true;
    target.faintEndTime = Main.timer + 3; // 3 timer counts (balanced)
    System.out.println(target.name + " is FAINTED!");
}


    @Override
    boolean strike(Fighter target, double strikedmg) {

        boolean success = super.strike(target, strikedmg);
        if (!success) {
            return false;
        }

        vanish += 20;
        vanish = Math.min(vanish, 100);
        gainKi(10);
        if (usedstrike > 0) {
            usedstrike--;
            if (myTeam != null) {
                myTeam.drawCards(1);
            }
        }

        buildDimensionalPressure();
        bleed(target);
        return success;
    }


    @Override
    boolean blast(Fighter target, double blastdmg) {

        boolean success = super.blast(target, blastdmg);
        if (!success) {
            return false;
        }

        if (usedblast > 0) {
            usedblast--;
            if (myTeam != null) {
                myTeam.drawCards(1);
            }
        }

        buildDimensionalPressure();
        bleed(target);
        return success;
    }


    // ==========================================
    // QUIRK
    // ==========================================

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {

        int required = kiRequired(Card.QUIRK);

        if (target == null || target.hp <= 0 ||
            hp <= 0 || ki < required) {
            return false;
        }

        boolean success = super.quirk(target, quirkcannotbevanished);

        if (success) {

            // Dimensional Shift
            vanish += 35;
            vanish = Math.min(vanish, 100);

            applyDamageBuff(0.15, 8);
        }

        return success;
    }


    // ==========================================
    // MAIN ABILITY
    // ==========================================

    @Override
    void mainAbility(Fighter target) {

        if (sealmain || mainused || Main.timer <= 40) {
            return;
        }

        mainused = true;
        ultunlocked = true;
        if (myTeam != null) {
            myTeam.hand[0]=Card.STRIKE;
            myTeam.hand[1] = Card.ULT;
        }
        System.out.println(
            "[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] "
            + "THE DIMENSION ITSELF SHALL OBEY ME!"
        );

        // Full resource reset
        ki = 100;
        vanish = 100;

        // Transformation power-up
        damageMul += 0.6;
        defenseMul += 0.3;

        // Short transformation burst
        applyDamageBuff(0.40, 15);

        // Reset pressure so Janemba can build another cycle
        dimensionalPressure = 0;

        // Small enemy disruption
        if (target != null && target.hp > 0) {

            target.applyTempDefenseDown(0.20, 5);

            if (target.ki > 20) {
                target.ki -= 20;
            } else {
                target.ki = 0;
            }
        }
    }


    // ==========================================
    // SPECIAL MOVE
    // ==========================================

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        int required = kiRequired(Card.SPMOVE);
        if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
            return false;
        }

        vanish += 60;
        vanish = Math.min(vanish, 100);
        gainKi(40);
        bleed(target);
        faint(target);
        if (target != null && target.hp > 0) {

            // Stronger dimensional cut
            target.applyTempDefenseDown(0.25, 5);
        }

        // SPMove itself helps Janemba reach his next pressure cycle
        dimensionalPressure++;

        if (dimensionalPressure >= maxDimensionalPressure) {
            dimensionalPressure = 0;

            applyDamageBuff(0.35, 10);

            gainKi(20);

            vanish += 20;
            vanish = Math.min(vanish, 100);
        }

        return super.spmove(target, spmovedmg);
    }


    // ==========================================
    // ULTIMATE
    // ==========================================

    @Override
    boolean ultimate(Fighter target, double ultdmg) {

        int required = kiRequired(Card.ULT);

        if (target == null || target.hp <= 0 ||
            hp <= 0 || ki < required) {
            return false;
        }

        ultimate++;
        if (myTeam != null) {
            myTeam.hand[0]=Card.STRIKE;
            myTeam.hand[1] = Card.ULT;
        }
        ki -= required;
        faint(target);

        System.out.println(
            "[" + (myTeam == null ? "Unassigned" : myTeam.teamName) + "] "
            + name + " uses DIMENSIONAL DESTRUCTION on "
            + target.name + "!"
        );

        // Janemba becomes difficult to interrupt during the finisher
        buffCancelImmune = true;

        double finalScore;
        try {
            finalScore = target.takeDamage(
                this,
                ultdmg * mul,
                (this.damageMul * lbDamage) * (1 + currentDamageBuff()),
                1,
                Card.ULT
            );
        } finally {
            clearBuffCancelImmunity();
        }

        if (myTeam != null) {
            myTeam.addDamage(finalScore);
        }
        if(ultimate==2){
        ultunlocked = false;
        }

        return false;
    }

    @Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(type == Card.SPMOVE || type == Card.ULT){         
    if (attacker != null) {
        attacker.cancelBuffs();
    }
    baseDamage*=0.5;      
    }
    double hpBeforeDamage = hp;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
     if (!endurancehas && hpBeforeDamage - dmg <= 0
             && !isEnduranceNullifiedBy(attacker)) {
        endurancehas = true;
        hp = 1500000; // endurance HP
        System.out.println(name + " ENDURES THE ATTACK!");
        return dmg;
    }
    else{
        if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }
    return dmg;
}
}

class SsjbVegeta extends Fighter {
    
    int hit=0;
    boolean fullguage=false;
    
    SsjbVegeta(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.5;   
        defenseMul = 3.8;  
        ultdmg=2500000;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
        applyImmune(120);
        applyKiImmune(120);
    }
    
    @Override
   void cancelBuffs() {
        super.cancelBuffs();
        }


        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"I am the Prince of all Saiyans!!");
    EndNullify(10);
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }

          void attackguage(){
        if(hit>=4)
        {
            fullguage=true;
            if(fullguage==true){
            hit=0;
            applyImmuneHand(15);
            heal(200000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            setDrawSpeedFor(2,10);
            applyDamageBuff(0.35, 10);
            fullguage=false;
            drawCardsSmart();
    }
        }
        
    }  
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        hit++;
        attackguage();
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        hit++;
        attackguage();
        hp = Math.min(hp, maxhp);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        applyBuffCancelImmunity(10);
        target.applyTempDefenseDown(0.05, 5);
        EndNullify(10);
        gainKi(30);
        target.cancelBuffs();
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    boolean success = super.quirk(target, quirkcannotbevanished);
        heal(300000);
        setDrawSpeedFor(2,10);
        if (!success) {
            return false;
        }
           applyDamageBuff(0.15, 10);
        hit++;
        attackguage();
    return success;
}

@Override
boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    updateTimedBuffs();

    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }
    EndNullify(10);

    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
        + name + " uses ultimate card on " + target.name + "!");

    applyBuffCancelImmunity(10);

    double finalScore = target.takeDamage(
        this,
        ultdmg * mul,
        (this.damageMul * lbDamage) * (1 + currentDamageBuff()),
        1,
        Card.ULT
    );

    mul = 1;
    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }

    ultunlocked = false;
    return false; // Ultimate ends the combo
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(type == Card.SPMOVE || type == Card.ULT){
        baseDamage*=0.5;
    }
    applyTempDefenseUp(0.2,10);
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    return dmg;
}

}

class MajinVegeta2 extends Fighter {
    
    int times=0;
    int noofcounter=3;
    int duration=10;
    int cancelbuffnum=3;

    MajinVegeta2(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.5;   
        defenseMul = 3.8;  
        ultdmg=3000000;
        applyautotimer(duration);
        quirkcannotbevanished=true;
        endurancehas=false;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }
    

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"YOU ARE MINE!!");
    myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        boolean hit = super.blast(target,blastdmg);
        if (hit) {
            target.strikecardcostincrease(20, 5);   // every blast card that lands
        }
        return hit;
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.25, 5);
        gainKi(30);
        if(autocounter){
            for(int i=0;i<myTeam.hand.length;i++){
                if(myTeam.hand[i]==Card.SPMOVE){
                    myTeam.hand[i]=Card.QUIRK;
                    System.out.println("Drawn a quirk card");
                    break;
                }
            }
        }
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           clearCardCostIncrease();   // wipe any cost debuff on him
           cardcostimmune(10);        // and block new ones for 10 counts
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(3,10);
           heal(300000);
           target.cancelAtributesUpdrades();
           applyColorImmunity(10);
           applyBuffCancelImmunity(10);
           target.cancelBuffs();
           applyImmuneHand(10);
           drawCardsSmart();
    return super.quirk(target, quirkcannotbevanished); 
}

@Override 
boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }
    target.applyNoswitch(3);
    EndNullify(10);
    applyBuffCancelImmunity(10);
    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
    + name + " uses ultimate card on " + target.name + "!");
    double finalScore = target.takeDamage(this,
        ultdmg * mul,
        (this.damageMul * lbDamage),
        1,Card.ULT
    );
    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    ultunlocked = false; 
    return false; // Ultimate ends the combo
}

void autocounterguageforstrikeandblast(Fighter attacker){
    if(autocounter && noofcounter>0){
    clearautotimer();
    applyautotimer(duration);
    attacker.applyNoswitch(3);
    noofcounter--;
    vanish=100;
    gainKi(50);
    applyImmune(10);
    applyColorImmunity(10);
    System.out.println("MY TURN TO PLAY WITH YOU!");
    if(myTeam.hand[1]==Card.ULT){
    Main.executeCombo(myTeam, attacker.myTeam, true);
    }
    else{
    myTeam.hand[0] = Card.SPMOVE;
    Main.executeCombo(myTeam, attacker.myTeam, true);
    }
}
}

void disrupt(Fighter attacker){
    if(hp<=0)
    return;
  if(times==0){
if(this.hp<=maxhp*0.5)
{
attacker.clearHandImmune();
    for (int i = 0; i < attacker.myTeam.hand.length; i++) {
    Card card = attacker.myTeam.hand[i];
    if(Card.STRIKE!=card && Card.BLAST!=card){
        attacker.myTeam.hand[i] =null;
    }
}
 sealMain(attacker,10);
 heal(800000);
 applyDamageBuff(0.3, 10);
 times++;
}
}
        
}

@Override
void switchIn(Fighter target){
    for (Fighter f : target.myTeam.fighters) {
    if (f != null) f.switchCD += 2;
    }
    super.switchIn(target);
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(autocounter && (type == Card.BLAST || type == Card.STRIKE) && noofcounter>0){
        autocounterguageforstrikeandblast(attacker);
        justDodged=true;
        return 0;
    }
    else if(type == Card.SPMOVE || type == Card.ULT){
        if(cancelbuffnum>0){
        baseDamage*=0.5;
        attacker.cancelBuffs();
        cancelbuffnum--;
    }
}
    double hpBeforeDamage = hp;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
     if (!endurancehas && hpBeforeDamage - dmg <= 0
             && !isEnduranceNullifiedBy(attacker)) {
        endurancehas = true;
        hp = 1500000; // endurance HP
        System.out.println(name + " ENDURES THE ATTACK!");
        times=0;
        disrupt(attacker);
        return dmg;
    }
    else{
        if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }
    applyTempDefenseUp(0.2,5);
    attacker.applyTempDefenseDown(0.2,5);
    disrupt(attacker);
    return dmg;
}

}

class GokuBlackRoseTyrant extends Fighter {

    int roseStacks = 0;
    int maxRoseStacks = 5;
    int hitsTaken = 0;

    boolean roseAwakened = false;

    GokuBlackRoseTyrant(
        String name,
        double hp,
        int ki,
        int vanish,
        double strikedmg,
        double blastdmg,
        double spmovedmg,
        double rate,
        double clash
    ) {
        super(
            name, hp, ki, vanish,
            strikedmg, blastdmg, spmovedmg,
            rate, clash
        );

        damageMul = 4.0;
        defenseMul = 3.5;
        ultdmg = 2000000;

        setDrawSpeedFor(2, 20);
        permanentendurancenull=true;
        hasBlastArmor=true;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(true);
    }

    // =========================================
    // ROSE PASSIVE: DIVINE EVOLUTION
    // =========================================

    void gainRoseStack() {

        if (roseStacks >= maxRoseStacks)
            return;

        roseStacks++;

        damageMul += 0.2;
        defenseMul += 0.14;

        System.out.println(
            "[" + myTeam.teamName + "] "
            + name + "'s Divine Power increases! "
            + "Rose Stacks: "
            + roseStacks + "/" + maxRoseStacks
        );

        if (roseStacks == maxRoseStacks) {

            colourNullGrantsPermanent=true;
            applyColorImmunity(20);

            roseAwakened = true;
            
            System.out.println(
                name + ": YOU MORTALS HAVE PUSHED ME "
                + "TO REVEAL MY TRUE DIVINITY!"
            );
        }
    }

    // =========================================
    // MAIN ABILITY: DIVINE JUDGMENT
    // =========================================

    @Override
    void mainAbility(Fighter target) {

        if (sealmain)
            return;

        if (mainused || Main.timer <= 50)
            return;

        mainused = true;
        ultunlocked = true;

        ki = 100;

        heal(700000);

        damageMul += 0.5;
        defenseMul += 0.3;

        setDrawSpeedFor(2, 10);

        applyImmune(10);
        applyImmuneHand(10);

        if (myTeam != null){
            myTeam.hand[0]=Card.STRIKE;
            myTeam.hand[1] = Card.ULT;
        }

        System.out.println(
            "[" + myTeam.teamName + "] "
            + name + ": "
            + "BEHOLD THE POWER OF A GOD!"
        );
    }

    // =========================================
    // STRIKE
    // =========================================

    @Override
    boolean strike(Fighter target, double strikedmg) {

        gainRoseStack();

        gainKi(5);

        target.applyTempDefenseDown(0.05, 5);

        return super.strike(target, strikedmg);
    }

    // =========================================
    // BLAST
    // =========================================

    @Override
    boolean blast(Fighter target, double blastdmg) {

        gainRoseStack();

        gainKi(5);

        target.applyTempDefenseDown(0.05, 5);

        return super.blast(target, blastdmg);
    }

    // =========================================
    // SPECIAL MOVE: DIVINE PUNISHMENT
    // =========================================

    @Override
    boolean spmove(Fighter target, double spmovedmg) {

        gainRoseStack();

        gainKi(25);

        target.applyTempDefenseDown(0.20, 5);

        System.out.println(
            name + ": KNOW YOUR PLACE, MORTAL!"
        );

        return super.spmove(target, spmovedmg);
    }

    // =========================================
    // QUIRK: GODLY DOMINATION
    // =========================================

    @Override
    boolean quirk(
        Fighter target,
        boolean quirkcannotbevanished
    ) {

        gainRoseStack();

        heal(250000);

        gainKi(50);

        applyImmune(5);

        if (target != null && target.hp > 0) {

            target.cancelBuffs();

            target.applyTempDefenseDown(0.15, 5);
        }

        if(roseAwakened)
        applyColorImmunity(10);

        System.out.println(
            name + ": YOUR POWER IS NOTHING BEFORE A GOD!"
        );

        return super.quirk(
            target,
            quirkcannotbevanished
        );
    }

@Override 
void switchIn(Fighter target){
    if(roseAwakened)
    applyColorImmunity(10);
    super.switchIn(target);
}

    // =========================================
    // DAMAGE TAKEN: DIVINE RETRIBUTION
    // =========================================

    @Override
    double takeDamage(
        Fighter attacker,
        double baseDamage,
        double attackerDamageMul,
        double critical,
        Card type
    ) {
        if(type==Card.SPMOVE || type==Card.ULT){
        baseDamage*=0.5;
        applyTempDefenseUp(3, 10);
        applyDamageDown(0.5, 10);
        }
        baseDamage*=0.7;
        if (attacker != null)
        attacker.updateTimedStatuses();
        double dmg = super.takeDamage(
            attacker,
            baseDamage,
            attackerDamageMul,
            critical,
            type
        );

        if (dmg > 0 && hp > 0) {

            hitsTaken++;

            if (hitsTaken % 3 == 0
                && attacker != null
                && attacker.hp > 0) {

                System.out.println(
                    name + "'s Divine Retribution activates!"
                );

                attacker.cancelBuffs();

                attacker.ki -= 20;
                attacker.ki = Math.max(attacker.ki, 0);

                heal(150000);
            }
        }

        return dmg;
    }

    // =========================================
    // ULTIMATE: DIVINE ERASURE
    // =========================================

    @Override
    boolean ultimate(Fighter target, double ultdmg) {
        updateTimedBuffs();

        int required = kiRequired(Card.ULT);

        if (target == null
            || target.hp <= 0
            || hp <= 0
            || ki < required) {

            return false;
        }

        ki -= required;

        System.out.println(
            "[" + myTeam.teamName + "] "
            + name + " unleashes DIVINE ERASURE!"
        );

        // Prevent switching and block Endurance.
        target.applyNoswitch(3);
        EndNullify(10);

        // Strip enemy buffs before the attack.
        target.cancelBuffs();

        // Fully evolved Rose gains a stronger finisher.
        double ultimateMultiplier =
            roseAwakened ? 2.5 : 2.0;

        double finalScore = target.takeDamage(
            this,
            ultdmg * ultimateMultiplier,
            (this.damageMul * lbDamage),
            1,
            Card.ULT
        );

        target.vanish = 0;

        if (myTeam != null)
            myTeam.addDamage(finalScore);

        System.out.println(
            name + ": ERASE YOUR EXISTENCE!"
        );

        ultunlocked = false;

        return false;
    }
}

class MUIGoku extends Fighter {
    
    int uniqueGauge = 100;      
        int maxUniqueGauge = 100;      
        int decayPerTick = 1; 
        int onespmove=0;

    MUIGoku(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.0;   
        defenseMul = 3.0;  
        justDodged=false;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(false);

    }
        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ki = 100;
        heal(1200000);
        recoverUniqueGauge(100);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2, 10);
        System.out.println("[" + myTeam.teamName + "] "
    +"This ends now!");
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.20, 15);
        applyColorImmunity(15);
        myTeam.hand[0] = Card.SPMOVE;
        target.applyNoswitch(10);
        }
        
}

    @Override
    void switchIn(Fighter target) {
        uniqueGauge = maxUniqueGauge;
        System.out.println("[" + myTeam.teamName + "] "
    + name + " resets Unique Gauge! Ready to dodge again.");
        applyColorImmunity(5);
        super.switchIn(target);
    }
    
    void decayUniqueGauge() {
        if (uniqueGauge > 0) {
            uniqueGauge -= decayPerTick;
            if (uniqueGauge < 0) uniqueGauge = 0;
        }
    }
    
    boolean tryUniqueDodge() {
        if (uniqueGauge >=33) {
            uniqueGauge -= 33; // each dodge also reduces gauge
            uniqueGauge=Math.max(uniqueGauge,0);
            System.out.println("[" + myTeam.teamName + "] "
    + name + " Too slow.! gauge: " + uniqueGauge + "");
            return true; // attack avoided
        }
        return false; // no dodge left, take damage normally
    }
    
    
@Override
    boolean strike(Fighter target, double strikedmg) {
        recoverUniqueGauge(10); 
        applyDamageBuff(0.15, 5);
        return super.strike(target,strikedmg);
    }
    
    @Override
    boolean blast(Fighter target, double blastdmg) {
        recoverUniqueGauge(10); 
        applyDamageBuff(0.15, 5);
        return super.blast(target,blastdmg);
    }
    
    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyBuffCancelImmunity(10);
        EndNullify(10);
        recoverUniqueGauge(30); 
        applyDamageBuff(0.25, 5);
        return super.spmove(target,spmovedmg);
    }
    
    @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
    recoverUniqueGauge(30); 
    myTeam.drawCards(1);
    setDrawSpeedFor(2, 10);
    
    return super.quirk(target, quirkcannotbevanished); 
}

    
    void recoverUniqueGauge(int amount) {
    uniqueGauge = Math.min(uniqueGauge + amount, maxUniqueGauge);
    System.out.println(name + " recovers " + amount + " Unique Gauge! Current: " + uniqueGauge);
}

void counter(Fighter target){
        System.out.println("You are finished!");
        myTeam.hand[0]=Card.SPMOVE;
        Main.executeCombo(myTeam, target.myTeam, true);
        applyImmuneGuage(5);
        applyColorImmunity(5);
        onespmove++;
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if ((type == Card.STRIKE || type == Card.BLAST) && !attacker.immuneguage) {
        if(tryUniqueDodge()){
        cardcostimmune(5);
        attacker.updateTimedStatuses();
        justDodged=true;
        clearCardCostIncrease();
        applyColorImmunity(5);
        double dmg=2*strikedmg;
        if(attacker.releaseguagetimer)
            dmg=0;
            double dam=attacker.takeDamage(this,dmg , (this.damageMul * lbDamage)*(1 + currentDamageBuff()),0, null);
            if (attacker.hp < 1) attacker.hp = 1;  
            System.out.println("hp left "+(int)attacker.hp);
            myTeam.addDamage(dam);
            System.out.print(this.name+" dealt "+dam+" to "+attacker.name);
        return 0; 
        }
    }
    else if ((type == Card.SPMOVE) && !attacker.immuneguage && onespmove==0) {
        if(tryUniqueDodge()){
        attacker.updateTimedStatuses();
        counter(attacker);
        justDodged=true;
        applyColorImmunity(5);
        return 0; 
        }
    }
    if((type == Card.ULT || type == Card.SPMOVE)){
    attacker.cancelBuffs();
    applyTempDefenseUp(2,3);
    }
    applyTempDefenseUp(0.2,5);
    return super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
}


}

class Ssj3Goku extends Fighter {

    int special = 1;
    int times = 0;
    int gauge = 0;
    int number = 0; // Number of completed gauge transformations, not helper calls.
    boolean spirit = false;
    boolean nogaugeswitch = false;
    int cancelbuffnum=3;

    private boolean vegetaForm;
    private boolean preparationStarted = false;
    private boolean preparationUsed = false; // Permanent: this fighter can prepare only once.

    Ssj3Goku(String name, double hp, int ki, int vanish,
             double strikedmg, double blastdmg, double spmovedmg,
             double rate, double clash) {
        super(name, hp, ki, vanish, strikedmg, blastdmg, spmovedmg, rate, clash);
        damageMul = 4.0;
        defenseMul = 3.5;
        hasBlastArmor=true;
        
        applyImmuneHand(120);
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
        vegetaForm = "Ssj2Vegeta".equalsIgnoreCase(name)
                  || "Base Vegeta".equalsIgnoreCase(name);
    }

    private void gainGauge(int amount, Fighter target) {
        if (nogaugeswitch || preparationStarted || spirit) return;
        gauge += amount;
        transformIfReady(target);
    }

    /** Handles one threshold crossing in one place, so one gauge event cannot
     * run both the old debuff and buff transformation paths. */
    private void transformIfReady(Fighter target) {
        if (gauge < 100 || nogaugeswitch || preparationStarted || spirit) return;

        gauge = 0;
        number++;
        special = 0;
        times = 0;

        if (vegetaForm) {
            name = "Ssj3Goku";
            vegetaForm = false;
            System.out.println("Switching to Goku");
        } else {
            name = "Ssj2Vegeta";
            vegetaForm = true;
            System.out.println("Switching to Vegeta");
        }

        applyDamageBuff(0.30, 5);
        applyBuffCancelImmunity(5);
        applyColorImmunity(5);

        if (target != null && target.hp > 0) {
            target.applyNoswitch(5);
            target.applyTempDefenseDown(0.20, 5);
        }

        if (number >= 3) {
            preparation(target);
        }
    }

    @Override
    boolean strike(Fighter target, double strikedmg) {
        gainGauge(20, target);
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        preparation(target);
        return super.strike(target, strikedmg);
    }

    @Override
    boolean blast(Fighter target, double blastdmg) {
        gainGauge(20, target);
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        preparation(target);
        return super.blast(target, blastdmg);
    }

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        gainGauge(20, target);
        applyColorImmunity(10);
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(30);
        preparation(target);
        return super.spmove(target, spmovedmg);
    }

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        gainGauge(40, target);
        if (special == 1) {
            special--;
            vanish = 100;
        }
        applyDamageBuff(0.15, 10);
        setDrawSpeedFor(2, 10);
        heal(300000);
        applyKiImmune(10);
        preparation(target);
        return super.quirk(target, quirkcannotbevanished);
    }

    @Override
    void switchIn(Fighter target) {
        super.switchIn(target);
        gainGauge(40, target);
        applyColorImmunity(5);
        preparation(target);
    }

    /** Once the HP threshold is crossed, perform the disruption effects and
     * add gauge. The caller resolves the combined gauge gain only once. */
    private void disrupt(Fighter attacker) {
        if (hp <= 0 || times != 0 || hp > maxhp * 0.5) return;

        gauge += 100;
        attacker.clearHandImmune();
        attacker.clearKiImmune();
        attacker.applyNoswitch(5);

        if (!attacker.kiimmune) attacker.ki = 0;
        if (!attacker.handImmune && attacker.myTeam != null && attacker.myTeam.hand != null) {
            for (int i = 0; i < attacker.myTeam.hand.length; i++) {
                attacker.myTeam.hand[i] = null;
            }
        }

        heal(800000);
        applyDamageBuff(0.30, 10);
        times++;
    }

    /** Compatibility shim for any existing simulator code calling the old
     * helper directly. Both old helper names now route to the same handler. */
    void switchguagedebuff(Fighter target) {
        transformIfReady(target);
    }

    void switchguagebuff() {
        transformIfReady(null);
    }

    private void preparation(Fighter target) {
        if (number < 3 || preparationUsed || preparationStarted || spirit || hp <= 0) return;

        preparationUsed = true;
        preparationStarted = true;
        nogaugeswitch = true;
        name = "Base Vegeta";
        System.out.println("Dont Die Vegeta");
        applypreparationmode(15); // Absolute deadline: Main.timer + 10.
        if (target != null && target.hp > 0) target.applyNoswitch(10);
        applyBuffCancelImmunity(16);
        applyTempDefenseUp(2,15);
        applyColorImmunity(16);
        applyKiImmune(15);
        applyImmuneHand(15);
        applyNoswitch(2);
        applyIndestructibleImmunity(16);
        EndNullify(16);
    }

    @Override
    void checkpreperation() {
        if (!preparationStarted || preparation <= 0 || spirit) {
            preparationmodeon = false;
            return;
        }
        preparationmodeon = Main.timer >= preparation;
    }

    /** Called for every living instance, including benched fighters. The
     * inherited preparation deadline is absolute, so benching does not pause it. */
    void updateSpiritBomb(Fighter activeEnemy) {
        if (!preparationStarted || spirit || hp <= 0 || preparation <= 0) return;
        if (Main.timer >= preparation && activeEnemy != null && activeEnemy.hp > 0) {
            preparationmodeon = true;
            spiritbomb(activeEnemy);
        }
    }

    void spiritbomb(Fighter activeEnemy) {
        if (!preparationStarted || !preparationmodeon || spirit ||
            Main.timer < preparation || activeEnemy == null || activeEnemy.hp <= 0) {
            return;
        }
        sacrifice=true;
        activeEnemy.clearColorImmunity();
        System.out.println("THANKS EVERYONE!!: Launching spirit bomb");
        double directDamage = this.spmovedmg *3;
        double dam=activeEnemy.takeDamage(this, directDamage,(this.damageMul * lbDamage)*(1 + currentDamageBuff()) , 0, null);
        this.name="Ssj3 Goku";
        myTeam.addDamage(dam);
        sacrifice=false;
        // Preserve the original team-wide damage in addition to direct damage
        // to the active enemy (the active enemy receives both components).
        if (activeEnemy.myTeam != null) {
            activeEnemy.myTeam.DamageTeam(this, this.spmovedmg);
        }
        myTeam.addDamage(directDamage*0.5);
        System.out.println(this.name+" dealt "+directDamage*0.5+" to "+activeEnemy.myTeam.teamName);

        clearpreparationmode();
        spirit = true;
        applyBuffCancelImmunity(10);
        preparationStarted = false; // Preparation is finished; preparationUsed stays true forever.
        System.out.println("hp left " + (int)activeEnemy.hp);
        justattacked = true;
        if (activeEnemy.myTeam != null) {
            Main.checkDeathAndHandle(activeEnemy.myTeam, this);
        }
        if (hp <= 0) { sacrifice = false; clearpreparationmode(); spirit = true; return; }
    }

    @Override
    double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul,
                      double critical, Card type) {
    attacker.updateTimedStatuses();

    if(type==Card.ULT || type==Card.SPMOVE){                    
    if(cancelbuffnum>0){
        baseDamage*=0.7;
        attacker.cancelBuffs();
        cancelbuffnum--;
    }
    }
    
        double dmg = super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);

        
        if (hp <= 0) {
            return dmg;
        }

        disrupt(attacker);
        if (!nogaugeswitch && !preparationStarted && !spirit) {
            gauge += 50;
            transformIfReady(attacker);
        }
        preparation(attacker);
        return dmg;
    }
}

class SsjbGoku extends Fighter {

    int hit=3;
    int draws=1;
    int transform=1;
    boolean transformed=false;
    boolean fullguage=false;
    int superhit=0;

    SsjbGoku(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.0;   
        defenseMul = 3.5;  
        EndNullify(10);
        applyImmuneHand(10);
        applyBuffCancelImmunity(10);
        justDodged=false;
        endurancehas=false;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(true);
    }
    
        @Override 
        void switchIn(Fighter target){
        if(transformed){
        EndNullify(20);
        applyImmuneHand(20);
        applyBuffCancelImmunity(20);
        applyKiImmune(20);
        applyImmuneGuage(20);
        applyColorImmunity(20);
        }
        else{
        EndNullify(10);
        applyImmuneHand(10);
        applyColorImmunity(10);
        applyBuffCancelImmunity(10);
        }
        hit++;
        super.switchIn(target);
        }

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        hit+=4;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(3,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"You ain't goint far!!");
        myTeam.hand[0]=(Card.STRIKE);
        myTeam.hand[1]=(Card.STRIKE);
        myTeam.hand[2]=(Card.BLAST);
        myTeam.hand[3]=(Card.BLAST);
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        hit++;
        kiloweffect();
        drawcard();
        transform();
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        hit++;
        kiloweffect();
        drawcard();
        transform();
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyKiImmune(10);
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.05, 5);
        kiloweffect();
        applyBuffCancelImmunity(10);
        EndNullify(10);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000);
           hit++;
           kiloweffect();
           drawcard();
           transform();
    return super.quirk(target, quirkcannotbevanished); 
}

void drawcard(){
    if(hit>=4)
        {
            fullguage=true;
            if(fullguage==true){
            superhit+=hit;
            hit=0;
            applyImmuneHand(15);
            heal(200000);
            gainKi(50);
            hp=Math.min(hp,maxhp);
            ki=Math.min(ki, 100);
            setDrawSpeedFor(2,10);
            applyDamageBuff(0.35, 10);
            fullguage=false;
            drawCardsSmart();
    }
        }
}

void kiloweffect(){
    if(ki<50){
        gainKi(50);
    }
    applyKiImmune(10);
    drawCardsSmart();
}

void disrupt(Fighter attacker){
    if(this.hp>0)
    heal(500000);
    attacker.clearColorImmunity();
    attacker.clearHandImmune();
    attacker.clearKiImmune();
    attacker.cancelBuffs();
    if(!attacker.kiimmune)
    attacker.ki=0;
    int delete=0;
     if(!attacker.handImmune)
    for (int i = 0; i < attacker.myTeam.hand.length; i++) {
    if(attacker.myTeam.hand[i]!=null){
        attacker.myTeam.hand[i]=null;
        delete++;
        if(delete==2)
            break;
    }
}
    superhit++;

}

void transform(){
    if(superhit>=10 && transform==1){
    System.out.println("Transforming... KAIOKEN TIMES 10....");
    this.name="KAIOKEN SSJB GOKU";
        ki = 100;
        heal(500000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(3,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        myTeam.hand[0]=(Card.STRIKE);
        myTeam.hand[1]=(Card.STRIKE);
        myTeam.hand[2]=(Card.BLAST);
        myTeam.hand[3]=(Card.BLAST);
        transform--;
        transformed=true;
        justDodged=true;
    }
}

@Override
    int baseKiRequired(Card card){
    if (card == null) {
        return 0;
    }
    
    switch (card) {
        case STRIKE: return 9;
        case BLAST:  return 19;
        case QUIRK:  return 9;
        case SPMOVE: return 35;
        case ULT:    return 15;
        default: return 0;
    }
    }

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(type==Card.SPMOVE || type==Card.ULT){
        baseDamage*=0.7;
        applyTempDefenseUp(2,5);
        disrupt(attacker);
        transform();
    }
    applyDamageDown(0.2,10);
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
        return dmg;
}

}

class Cell extends Fighter {
    
    int activeguage=20;
    int maxactiveguage=100;

    Cell(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.4;   
        defenseMul = 3.4;  
        ultdmg=2500000;
        applyImmuneGuage(10);
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(true);
    }
    
    @Override 
    void switchIn(Fighter target){
        applyImmuneGuage(10);
        applyColorImmunity(10);
        activeguageattack(target);
        super.switchIn(target);
    }

        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"You should be perfect or you are not me!!");
    for (int i = 0; i < myTeam.hand.length; i++) {
    myTeam.hand[i] =null;
    }
    myTeam.hand[0]=Card.BLAST;
    myTeam.hand[1]=Card.ULT;
    target.vanish=0;
    if(!target.kiimmune)
    target.ki=0;
    target.applyNoswitch(5);
    EndNullify(10);
        }    
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(10);
        heal(150000);
        activeguage+=10;
        activeguage=(int)Math.min(100,activeguage);
        boolean success = super.strike(target, strikedmg);
        if(success){
        activeguageattack(target);
        }
        return success;
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(10);
        hp = Math.min(hp, maxhp);
        activeguage+=10;
        activeguage=(int)Math.min(100,activeguage);
        boolean success = super.blast(target, blastdmg);
        if(success){
        activeguageattack(target);
        }
        return success;
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.15, 5);
        gainKi(20);
        target.applyTempDefenseDown(0.05, 5);
        activeguage+=10;
        activeguage=(int)Math.min(100,activeguage);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000);
           gainKi(20);
           activeguage+=20;
           activeguage=(int)Math.min(100,activeguage);
        boolean success = super.quirk(target, quirkcannotbevanished);
        if(success){
        activeguageattack(target);
        }
        return success;
}

void activeguageattack(Fighter target){
    if(activeguage>=30){
    heal(100000);
    applyColorImmunity(5);
    applyImmuneGuage(30);
    applyImmuneHand(10);
    target.applyTempDefenseDown(0.3,5);
    target.clearColorImmunity();
    System.out.println("HAHA...HOW IS IT?");
    double dmg=this.blastdmg*0.7;
    if(target.releaseguagetimer)
    dmg=0;
    double dam=target.takeDamage(this, dmg,(this.damageMul * lbDamage)*(1 + currentDamageBuff()) , 0, null);
    myTeam.addDamage(dam);
    activeguage-=30;
    System.out.println("hp left "+target.hp);
    if (Main.checkDeathAndHandle(target.myTeam, this)) 
            return;
    target.cancelBuffs();
    activeguage+=10;
    drawCardsSmart();
        }
}

@Override
    boolean ultimate(Fighter target, double ultdmg) {
    int required = kiRequired(Card.ULT);
    if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
        return false;
    }
    applyCannotUltNullification(10);
    target.applyNoswitch(10);
    target.applyTempDefenseDown(2, 10);
    applyBuffCancelImmunity(10);
    ki -= required;
    System.out.println("[" + myTeam.teamName + "] "
    + name + " uses ultimate card on " + target.name + "!");
    applyBuffCancelImmunity(10);
    double finalScore = target.takeDamage(this,
        ultdmg * mul,
        (this.damageMul * lbDamage),
        1,Card.ULT
    );
    if (myTeam != null) {
        myTeam.addDamage(finalScore);
    }
    ultunlocked = false; 
    return false;   // non-comboable
    }

void destroy(Fighter target){
    target.clearHandImmune();
    inflictFaint(target);
    target.clearBuffCancelImmunity();
    heal(800000);
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(type==Card.SPMOVE || type==Card.ULT){
        applyColorImmunity(10);
        baseDamage*=0.6;
    }
    double hpBeforeDamage = hp;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    activeguage+=20;
    activeguage=(int)Math.min(100,activeguage);
    if(this.mainused && myTeam.hand[0]==Card.ULT){
       Main.executeCombo(myTeam, attacker.myTeam, true);
    }
    if (!endurancehas && hpBeforeDamage - dmg <= 0 && (!attacker.sacrifice && !attacker.permanentendurancenull)) {
        endurancehas = true;
        hp = 1500000; // endurance HP
        System.out.println(attacker.name + "...I wont lose!!");
        activeguage+=100;
        justDodged=true;
        destroy(attacker);
        return dmg;
    }
    else{
            if(hp<=0 && !endurancehas && (!attacker.sacrifice && !attacker.permanentendurancenull) )
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }
    return dmg;
}

}

class BaseVegito extends Fighter {

    int hit = 0;
    int buffcancle = 5;
    int superhit = 0;
    boolean transform = false;
    int time = 0;
    Fighter lastVictim = null;
    int disrupttime=0;

    BaseVegito(String name, double hp, int ki, int vanish, double strikedmg, double blastdmg, double spmovedmg, double rate, double clash) {
        super(name, hp, ki, vanish, strikedmg, blastdmg, spmovedmg, rate, clash);
        damageMul = 4.0;
        defenseMul = 3.5;
        hasBlastArmor = true;
        permanentapplyimmuneguage = true;
        endNullGrantsPermanent=true;
        permanentimmunity = true;
        permanentbuffcancelimmunity = true;
        colourNullGrantsPermanent=true;
        applyBuffCancelImmunity(240);
        EndNullify(240);
        applyImmune(240);
        applyImmuneGuage(240);
        applyIndestructibleImmunity(10);
        applyColorImmunity(10);
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }

    @Override
    void mainAbility(Fighter target) {
        if (sealmain)
            return;
        else {
            if (mainused || Main.timer <= 50)
                return;
            mainused = true;
            ki = 100;
            heal(1200000);
            target.vanish = 0;
            this.vanish = 100;
            damageMul += 0.5;
            defenseMul += 0.3;
            setDrawSpeedFor(2, 10);
            hp = Math.min(hp, maxhp);
            applyDamageBuff(0.35, 15);
            System.out.println("[" + myTeam.teamName + "] "
                    + "Seriously are you crying?");
            myTeam.hand[0] = Card.SPMOVE;
            applyImmuneHand(15);
        }
    }

    // Called by Main.checkDeathAndHandle after the victim has been swapped out.
    @Override
    void onKill(Fighter victim) {
        if (victim == lastVictim) return;   // same corpse reported twice
        lastVictim = victim;
        vanish = 100;
        applyDamageBuff(0.15, 5);
        heal(0.1 * maxhp);
    }

    @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.1, 5);
        gainKi(5);
        heal(150000);
        hit++;
        attack(target);
        return super.strike(target, strikedmg);
    }

    @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.1, 5);
        heal(150000);
        gainKi(5);
        hit++;
        attack(target);
        return super.blast(target, blastdmg);
    }

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.35, 5);
        heal(150000);
        target.applyTempDefenseDown(0.1, 5);
        return super.spmove(target, spmovedmg);
    }

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        applyDamageBuff(0.15, 10);
        hit++;
        attack(target);
        boolean success = super.quirk(target, quirkcannotbevanished);
        myTeam.hand[0] = Card.STRIKE;
        myTeam.hand[1] = Card.BLAST;
        if (success) {
            target.cancelAtributesUpdrades();
            Main.executeCombo(myTeam, target.myTeam, true);
            vanish = 100;
        }
        return success;
    }

    @Override
    void switchIn(Fighter target) {
        if (time == 0) {
            target.vanish = 0;
        }
        applyKiImmune(10);
        applyImmuneHand(10);
        super.switchIn(target);
    }

    void disrupt(Fighter attacker) {
        if (hp <= 0 || disrupttime==1)
            return;
        else if (hp <= maxhp * 0.5) {
            heal(maxhp * 0.7);
            attacker.ki = 0;
            for (int i = 0; i < attacker.myTeam.hand.length; i++) {
                attacker.myTeam.hand[i] = null;
            }
            applyTempDefenseUp(2, 5);
            attacker.applyNoswitch(5);
            attacker.applyTempDefenseDown(2, 5);
            applyColorImmunity(5);
            superhit += 10;
            disrupttime++;
        }
    }

    void attack(Fighter target) {
        if (hit >= 5) {
            superhit += hit;
            hit = 0;
            applyKiImmune(10);
            applyImmuneHand(10);
            setDrawSpeedFor(3, 5);
            target.clearColorImmunity();
            gainKi(50);
            heal(200000);
            drawCardsSmart();
        }
        if (superhit >= 10 && !transform) {
            System.out.println("THERE'S SOMETHING NEW....VEGITO..BLUE.... ");
            this.name = "Vegito Blue";
            applyColorImmunity(100);
            defenseMul += 0.7;
            damageMul += 1.2;
            transform = true;
            vanish = 100;
        }
    }

    @Override
    double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul, double critical, Card type) {
        if (type == Card.SPMOVE || type == Card.ULT) {
            baseDamage *= 0.55;
            if (buffcancle > 0) {
                attacker.clearBuffCancelImmunity();
                attacker.cancelBuffs();
                buffcancle--;
                applyTempDefenseUp(2, 6);
                applyColorImmunity(10);
            }
            hit += 3;
        } else {
            applyTempDefenseUp(0.2, 5);
            hit++;
        }
        if(attacker.sacrifice){
            attacker.clearColorImmunity();
        }
        double dmg = super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);
        disrupt(attacker);
        return dmg;
    }

}

class Beerus extends Fighter {
    
    int hakai=20;
    int maxhakai=100;

    Beerus(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.8;   
        defenseMul = 3.3;  
        ultdmg=2500000;
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
    }


        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        hakai=Math.min(100,hakai+50);
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"Before creation… must come destruction!!!");
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.1, 5);
        target.applyTempDefenseDown(0.05, 5);
        hakai=Math.min(100,hakai+10);
        gainKi(5);
        hakai(target);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.1, 5);
        target.applyTempDefenseDown(0.05, 5);
        hakai=Math.min(100,hakai+10);
        heal(150000);
        gainKi(5);
        hakai(target);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.2, 5);
        target.applyTempDefenseDown(0.05, 5);
        hakai=Math.min(100,hakai+20);
        gainKi(30);
        target.cancelBuffs();
        hakai(target);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           hakai=Math.min(100,hakai+10);
           setDrawSpeedFor(2,10);
           boolean success=super.quirk(target, quirkcannotbevanished);
            if(success){
            myTeam.hand[0]=Card.STRIKE;
            myTeam.hand[1]=Card.BLAST;
            hakai(target);
            Main.executeCombo(myTeam, target.myTeam, true);
            }
    return success ; 
}

void hakai(Fighter target){
    if(hakai>=80){
        sacrifice=true;
        target.clearImmunity();
        target.applyTempDefenseDown(2, 3);
        inflictBleed(target);
        inflictFaint(target);
        target.applyBuffCancelImmunity(5);
        applyColorImmunity(5);
        target.applyNoswitch(3);
        applyImmuneGuage(5);
        System.out.println("HAKAI!!");
        double dmg=this.ultdmg*0.7;
        double dam=target.takeDamage(this, dmg,(this.damageMul * lbDamage)*(1 + currentDamageBuff()) , 0, null);
        myTeam.addDamage(dam);
        hakai=Math.max(0,hakai-80);
        System.out.println("hp left "+target.hp);
        sacrifice=false;
        if (Main.checkDeathAndHandle(target.myTeam, this)) 
            return;

        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1]=Card.BLAST;
        myTeam.hand[2]=Card.QUIRK;
    }
}


@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    if(type==Card.SPMOVE || type==Card.ULT){
        attacker.cancelBuffs();
        baseDamage*=0.7;
        applyTempDefenseUp(2,5);
    }
    baseDamage*=0.8;
    return super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
}

}

class UltimateGohan extends Fighter {

    // ---- Ultimate Gauge (tune these) ----
    static final double GAUGE_MAX = 100;
    static final double GAUGE_DECAY_PER_COUNT = 0.2;    // drains slowly while he is on the battlefield
    static final double GAUGE_BENCH_GAIN_PER_COUNT = 0.5;   // fills while he waits on the bench
    static final double GAUGE_ENEMY_SWITCH_GAIN = 20;   // gained whenever an enemy switches in against him
    static final double GAUGE_SPMOVE_COST = 30;         // paid every time he uses his special move
    static final double GAUGE_REFILL_MIN = 20;          // special move is re-drawn only if this much gauge is left

    double gauge = GAUGE_MAX;      // starts full
    int gaugeClock = 0;            // timer value the gauge has been drained up to
    int vanishRecoveries = 3;      // quirk: refills vanish to 100% this many times
    int spmoveCounters = 3;        // special moves faced: full counter this many times
    int ultCounters = 2;           // ultimates faced: indestructible this many times
    boolean disrupted = false;     // half-HP disrupt happens once

    UltimateGohan(String name, double hp, int ki, int vanish, double strikedmg, double blastdmg, double spmovedmg, double rate, double clash) {
        super(name, hp, ki, vanish, strikedmg, blastdmg, spmovedmg, rate, clash);
        damageMul = 4.2;
        defenseMul = 4.0;
        endurancehas = false;
        setSpecialMoveRange(AttackRange.LONG_RANGE);
        setQuirkComboable(false);   // his quirk cannot open a combo
        setCoverChange(Card.STRIKE, Card.SPMOVE);   // covers STRIKE, combos into SPMOVE
    }


    @Override
    int baseKiRequired(Card card) {
        if (card == null) {
            return 0;
        }

        switch (card) {
            case STRIKE: return 19;
            case BLAST:  return 29;
            case QUIRK:  return 19;
            case SPMOVE: return 20;
            case ULT:    return 15;
            default: return 0;
        }
    }

    /* ---------- Ultimate Gauge ---------- */

    // Brings the gauge up to date. The counts since the last update were spent on the
    // battlefield (drain) or on the bench (fill), whichever 'wasActive' says.
    void settleGauge(boolean wasActive) {
        int now = Main.timer;
        if (now <= gaugeClock) {
            return;
        }
        if (hp > 0) {
            int counts = now - gaugeClock;
            if (wasActive) {
                gauge = Math.max(0, gauge - counts * GAUGE_DECAY_PER_COUNT);
            } else {
                gauge = Math.min(GAUGE_MAX, gauge + counts * GAUGE_BENCH_GAIN_PER_COUNT);
            }
        }
        gaugeClock = now;
    }

    void updateGauge() {
        settleGauge(myTeam != null && myTeam.getActive() == this);
    }

    @Override
    void updateTimedStatuses() {
        super.updateTimedStatuses();
        updateGauge();
    }

    // His spmove colour null can't be cancelled while it lasts, so it needs its own expiry.
    @Override
    void updateColorImmunity() {
        if (colorImmuneExpiresAt > 0 && Main.timer >= colorImmuneExpiresAt) {
            colourimmunitypermanent = false;
            colorImmune = false;
            colorImmuneExpiresAt = 0;
        }
    }

    @Override
    void switchIn(Fighter target) {
        settleGauge(false);   // everything up to now was spent on the bench
        target.cardcostincreaseall(10, 5);   // enemy facing him pays +10 ki on every card
        super.switchIn(target);
    }

    @Override
    void switchOut() {
        settleGauge(true);    // everything up to now was spent on the battlefield
    }

    /* ---------- card helpers ---------- */

    // Puts 'card' into an empty slot, or replaces a random slot - never a ULT, never the
    // 'keep' card, and never a card of the same type as the one being drawn.
    private void forceDraw(Card card, Card keep) {
        Card[] hand = myTeam.hand;
        String label = (card == Card.SPMOVE) ? "blue" : card.toString().toLowerCase();

        for (int i = 0; i < hand.length; i++) {
            if (hand[i] == null) {
                hand[i] = card;
                System.out.println(name + " Drawn a " + label + " card");
                return;
            }
        }

        java.util.ArrayList<Integer> options = new java.util.ArrayList<>();
        for (int i = 0; i < hand.length; i++) {
            if (hand[i] != Card.ULT && hand[i] != keep && hand[i] != card) {
                options.add(i);
            }
        }
        if (options.isEmpty()) {
            return;
        }

        int slot = options.get((int) (Math.random() * options.size()));
        Card discarded = hand[slot];
        hand[slot] = card;
        System.out.println(">>> " + name + " Discarded " + discarded + " for " + card + "!");
    }

    // Re-draws a special move into the slot the last played card left empty.
    private void refillSpecialMove() {
        int slot = myTeam.lastConsumedSlot;
        if (slot < 0 || slot >= myTeam.hand.length || myTeam.hand[slot] != null) {
            slot = -1;
            for (int i = 0; i < myTeam.hand.length; i++) {
                if (myTeam.hand[i] == null) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot >= 0) {
            myTeam.hand[slot] = Card.SPMOVE;
            System.out.println(name + " Drawn a blue card");
        }
    }

    // Destroys up to 'count' random cards from the enemy hand (respects hand immunity).
    private void destroyEnemyCards(Fighter enemy, int count) {
        if (enemy.myTeam == null) {
            return;
        }
        if (enemy.handImmune) {
            System.out.println(enemy.name + "'s hand is protected!");
            return;
        }
        java.util.ArrayList<Integer> filled = new java.util.ArrayList<>();
        for (int i = 0; i < enemy.myTeam.hand.length; i++) {
            if (enemy.myTeam.hand[i] != null) {
                filled.add(i);
            }
        }
        int destroyed = 0;
        while (destroyed < count && !filled.isEmpty()) {
            int pick = (int) (Math.random() * filled.size());
            enemy.myTeam.hand[filled.remove(pick)] = null;
            destroyed++;
        }
        System.out.println(">>> " + destroyed + " enemy card" + (destroyed == 1 ? "" : "s") + " destroyed!");
    }

    /* ---------- main ability: no ultimate card ---------- */

    @Override
    void mainAbility(Fighter target) {
        if (sealmain) {
            return;
        }
        if (mainused || Main.timer <= 50) {
            return;
        }
        mainused = true;
        System.out.println("[" + myTeam.teamName + "] " + name + " unleashes his hidden power!");

        // Same self buffs as the other fighters without an ultimate (MUI Goku's main).
        ki = 100;
        heal(1200000);
        damageMul += 0.5;
        defenseMul += 0.3;
        setDrawSpeedFor(2, 10);
        applyDamageBuff(0.20, 15);
        applyColorImmunity(15);

        // His Ultimate Gauge is refilled to 100%.
        updateGauge();
        gauge = GAUGE_MAX;
        System.out.println(name + "'s Ultimate Gauge is fully restored: 100%");

        // He draws a quirk card and a spmove card (and never an ultimate card).
        forceDraw(Card.SPMOVE, Card.QUIRK);
        forceDraw(Card.QUIRK, Card.SPMOVE);

        // The enemy loses all of its vanish.
        removeVanish(target);
        System.out.println(">>> " + (target == null ? "The enemy" : target.name) + "'s vanish drops to 0!");
        super.mainAbility(target);
    }

    /* ---------- enemy switches in against him ---------- */

    @Override
    void onEnemySwitchIn(Fighter incoming) {
        if (hp <= 0 || incoming == null || myTeam == null || myTeam.getActive() != this) {
            return;
        }

        updateGauge();
        gauge = Math.min(GAUGE_MAX, gauge + GAUGE_ENEMY_SWITCH_GAIN);
        System.out.println(name + " senses the switch! Ultimate Gauge: " + Math.round(gauge) + "%");

        // The enemy that came in: draw speed -1, loses 2 cards and 30 ki.
        incoming.drawspeedtime(incoming, 10);
        destroyEnemyCards(incoming, 2);
        if (!incoming.kiimmune) {
            incoming.ki = Math.max(0, incoming.ki - 30);
        }

        // Him: a forced quirk card (his special move is never discarded) and colour null.
        forceDraw(Card.QUIRK, Card.SPMOVE);
        applyColorImmunity(10);
    }

    /* ---------- cards ---------- */

    @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        gainKi(5);
        heal(150000);
        return super.strike(target, strikedmg);
    }

    @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        target.applyTempDefenseDown(0.05, 5);
        heal(150000);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        return super.blast(target, blastdmg);
    }

    @Override
    boolean quirk(Fighter target, boolean quirkcannotbevanished) {
        if (target == null || target.hp <= 0 || hp <= 0 || ki < kiRequired(Card.QUIRK)) {
            return false;
        }
        if (vanishRecoveries > 0) {
            vanishRecoveries--;
            vanish = 100;
            System.out.println(name + "'s vanish is fully restored!");
        }
        setDrawSpeedFor(2, 10);
        return super.quirk(target, quirkcannotbevanished);
    }

    @Override
    boolean spmove(Fighter target, double spmovedmg) {
        int required = kiRequired(Card.SPMOVE);
        updateTimedBuffs();
        if (target == null || target.hp <= 0 || hp <= 0 || ki < required) {
            return false;
        }

        // Applied BEFORE the hit so the special move itself ignores the target's endurance.
        colourNullGrantsPermanent=true;   // colour null that buff cancels cannot remove
        applyColorImmunity(10);
        EndNullify(10);
        applyBuffCancelImmunity(10);

        Teams enemyTeam = target.myTeam;
        double targetHpBefore = target.hp;
        super.spmove(target, spmovedmg);
        boolean hit = target.hp < targetHpBefore;

        // Gauge cost, and a fresh special move while enough gauge is left.
        updateGauge();
        gauge = Math.max(0, gauge - GAUGE_SPMOVE_COST);
        System.out.println(name + "'s Ultimate Gauge: " + Math.round(gauge) + "%");
        if (gauge >= GAUGE_REFILL_MIN) {
            refillSpecialMove();
        }

        // A special move that hits throws the enemy out (never if that would empty the field).
        if (hit && target.hp > 0 && enemyTeam != null) {
            boolean benchAlive = false;
            for (int i = 1; i < enemyTeam.fighters.length; i++) {
                if (enemyTeam.fighters[i] != null && enemyTeam.fighters[i].hp > 0) {
                    benchAlive = true;
                }
            }
            if (benchAlive) {
                System.out.println(name + "'s special move forces " + target.name + " off the battlefield!");
                Main.forceSwap(enemyTeam, this);
                Fighter incoming = enemyTeam.getActive();
                if (incoming != null && incoming != target) {
                    onEnemySwitchIn(incoming);
                }
            }
        }
        return false;
    }

    /* ---------- defence ---------- */

    // Half-HP disrupt: wipes the attacker's whole hand and drains its ki to 0.
    // Deliberately checks NO protection (hand immunity and ki immunity are both ignored).
    private void disrupt(Fighter attacker) {
        if (disrupted || hp <= 0 || attacker == null || attacker.myTeam == null) {
            return;
        }
        if (hp > maxhp * 0.5) {
            return;
        }
        disrupted = true;
        System.out.println("[" + myTeam.teamName + "] " + name + " DISRUPTS the battlefield!");
        for (int i = 0; i < attacker.myTeam.hand.length; i++) {
            attacker.myTeam.hand[i] = null;
        }
        attacker.ki = 0;
        System.out.println(">>> Enemy hand destroyed and " + attacker.name + "'s ki drained to 0!");
    }

    @Override
    double takeDamage(Fighter attacker, double baseDamage, double attackerDamageMul, double critical, Card type) {
        if (attacker == null) {
            return super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);
        }

        attacker.cardcostincreaseall(20, 5);   // whoever hits him pays +20 ki on every card

        // Indestructible window (same system as SOHTrunks' release gauge).
        updateTimedEndIndestructible();
        if (releaseguagetimer && !attacker.immuneindestructible) {
            System.out.println("Indestructible Activated can't DIE!!");
            return 0;
        }

        if (type == Card.SPMOVE) {
            if (spmoveCounters > 0) {
                spmoveCounters--;
                baseDamage *= 0.3;
                applyDamageDown(0.5, 10);
                applyTempDefenseUp(0.5, 10);
                attacker.cancelBuffs();
            } else {
                baseDamage *= 0.7;
            }
        } else if (type == Card.ULT) {
            if (ultCounters > 0) {
                ultCounters--;
                releaseguageend(15);
                applyTempDefenseUp(0.2, 10);
                if (!attacker.immuneindestructible) {
                    System.out.println("Indestructible Activated can't DIE!!");
                    return 0;
                }
            }
            baseDamage *= 0.7;
        } else {
            baseDamage *= 0.7;   // strike, blast, quirk and neutral hits
        }

        double hpBeforeDamage = hp;
        double dmg = super.takeDamage(attacker, baseDamage, attackerDamageMul, critical, type);

        // Endurance: survives a lethal hit once and recovers to 50% HP.
        if (!endurancehas && hpBeforeDamage - dmg <= 0 && !isEnduranceNullifiedBy(attacker)) {
            endurancehas = true;
            hp = maxhp * 0.5;
            System.out.println(name + " ENDURES THE ATTACK!");
        }
        else{
            if(hp<=0 && !endurancehas && isEnduranceNullifiedBy(attacker))
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }

        disrupt(attacker);
        return dmg;
    }
}

class Buuhan extends Fighter {
    
    int carddraw=0;

    Buuhan(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 4.2;   
        defenseMul = 3.8;  
        ultdmg=3500000;
        hasBlastArmor = true;
        endNullGrantsPermanent=true;
        applyCannotUltNullification(240);
        applyKiImmune(240);
        setSpecialMoveRange(AttackRange.MELEE);
        setQuirkComboable(true);
        setCoverChange(Card.BLAST, Card.SPMOVE);   // covers BLAST, combos into SPMOVE
    }

    @Override
void switchIn(Fighter target){
    for (Fighter f : target.myTeam.fighters) {
    if (f != null) f.switchCD += 2;
    }
    target.cardcostincreaseall(20,5);
    carddraw=0;
    myTeam.hand[0]=Card.SPMOVE;
    EndNullify(10);
    super.switchIn(target);
}


        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"Gohan's power is mine to wield now! I'm stronger than I ever was before!!!");
        applyIndestructibleImmunity(20);
        EndNullify(20);
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.10, 5);
        gainKi(5);
        heal(250000);
        boolean hit =super.strike(target,strikedmg);
        if(hit){
            target.applyTempDefenseDown(0.10, 5);
            inflictBleed(target);
            damageMul+=0.1;
            if(carddraw<=2){
                drawCards(1);
                EndNullify(6);
                carddraw++;
            }
        }
        return hit ;
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.10, 5);
        heal(250000);
        gainKi(8);
        boolean hit =super.blast(target,blastdmg);
        if(hit){
            target.applyTempDefenseDown(0.10, 5);
            inflictBleed(target);
            damageMul+=0.1;
                if(carddraw<=2){
                drawCards(1);
                EndNullify(8);
                carddraw++;
            }
        }
        return hit ;
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.25, 5);
        gainKi(30);
        applyBuffCancelImmunity(10);
        EndNullify(10);
        applyImmuneGuage(10);
            target.applyTempDefenseDown(0.20, 5);
            inflictFaint(target);
            damageMul+=0.2;
            carddraw=0;
        return super.spmove(target,spmovedmg); 
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(400000);
           boolean hit=super.quirk(target, quirkcannotbevanished);
           if(hit){
           applyColorImmunity(10);
           applyImmuneHand(10);
           clearCardCostIncrease();
           cardcostimmune(10);
           setDrawSpeedFor(2, 10);
           drawCardsSmart();
           }
           else{
            applyDamageDown(0.5, 20);
            applyTempDefenseUp(1, 10);
            updateTimedStatuses();
           }
    return hit ; 
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.5;
    if(type==Card.SPMOVE || type==Card.ULT){
        if(!attacker.kiimmune){
            attacker.ki=Math.max(0,attacker.ki-80);
        }
        defenseMul+=0.25;
        applyDamageDown(0.5, 10);
    }
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
    attacker.cardcostincreaseall(20, 10);
    defenseMul+=0.1;
    return dmg;
}

}

class MetalCooler extends Fighter {
    
    int endurancetimes=0;
    double heal=1;
    int cancelbuffnum=3;
    
    MetalCooler(String name, double hp, int ki, int vanish,double strikedmg, double blastdmg, double spmovedmg,double rate,double clash) {
        super(name, hp, ki, vanish, strikedmg,blastdmg,spmovedmg,rate,clash);
        damageMul = 3.5;   
        defenseMul = 3.0;  
        ultdmg=3200000;
        setCoverChange(Card.STRIKE, Card.QUIRK); 
        setSpecialMoveRange(AttackRange.MELEE);
        endurancehas=false;
        cardcostimmune(240);
        colourNullGrantsPermanent=true;
        setQuirkComboable(true);
    }


        @Override
    void mainAbility(Fighter target) {
         if(sealmain)
        return;
        else{
        if (mainused || Main.timer <= 50)
            return;
        mainused = true;
        ultunlocked=true;
        ki = 100;
        heal(1200000);
        damageMul += 0.5;  
        defenseMul += 0.3;
        setDrawSpeedFor(2,10);
        hp = Math.min(hp, maxhp);
        applyDamageBuff(0.35, 15);
        System.out.println("[" + myTeam.teamName + "] "
    +"I Will get my Revenge!!");
    applyIndestructibleImmunity(20);
    applyCannotUltNullification(20);
    EndNullify(20);
        myTeam.hand[0]=Card.STRIKE;
        myTeam.hand[1] = Card.ULT;
        }
    }
        
        @Override
    boolean strike(Fighter target, double strikedmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.10, 5);
        gainKi(5);
        heal(100000*heal);
        return super.strike(target,strikedmg);
        
    }
        
                @Override
    boolean blast(Fighter target, double blastdmg) {
        applyDamageBuff(0.15, 5);
        target.applyTempDefenseDown(0.10, 5);
        heal(100000*heal);
        gainKi(5);
        hp = Math.min(hp, maxhp);
        return super.blast(target,blastdmg);
    }
    
    
            @Override
    boolean spmove(Fighter target, double spmovedmg) {
        applyDamageBuff(0.30, 5);
        applyBuffCancelImmunity(10);
        applyColorImmunity(10);
        target.applyTempDefenseDown(0.2, 5);
        gainKi(30);
        return super.spmove(target,spmovedmg);
    }
    
            @Override
boolean quirk(Fighter target, boolean quirkcannotbevanished) {
           applyDamageBuff(0.15, 10);
           setDrawSpeedFor(2,10);
           heal(300000*heal);
           drawCardsSmart();
           boolean hit=super.quirk(target, quirkcannotbevanished);
           if(hit)
           Main.executeCombo(myTeam, target.myTeam, true);
           return hit; 
}

void counter(Fighter attacker){
    if(endurancetimes==1){
        System.out.println("DONT RUN!");
        damageMul+=0.5;
        defenseMul+=0.5;
        heal=1.0;
    }
    else if(endurancetimes==2){
        System.out.println("Piece of trash!!");
        damageMul+=0.8;
        defenseMul+=0.8;
        heal=1.5;
    }
    justDodged=true;
    gainKi(100);
    applyKiImmune(20);
    setDrawSpeedFor(2,10);
    applyBuffCancelImmunity(20);
    applyDamageDown(0.15, 20);
    applyTempDefenseUp(0.2, 20);
    applyDamageBuff(0.15, 20);
    applyImmune(40);
    applyColorImmunity(10);
    applyImmuneHand(10);
    applyCannotUltNullification(10);
    sealMain(attacker, 10);
    drawCardsSmart();
    attacker.vanish=0;
}

@Override
double takeDamage(Fighter attacker,double baseDamage, double attackerDamageMul, double critical, Card type) {
    baseDamage*=0.7;
    if(type==null){
        heal(200000*heal);
        attacker.applyNoswitch(5);
        applyTempDefenseUp(2, 10);
        applyDamageDown(0.5, 10);
        attacker.cancelBuffs();
        baseDamage*=0.7;
    }
    else if(type == Card.SPMOVE || type == Card.ULT){
        if(cancelbuffnum>0){
        baseDamage*=0.7;
        attacker.cancelBuffs();
        cancelbuffnum--;
    }
}
    updateTimedStatuses();
    double hpBeforeDamage = hp;
    double dmg=super.takeDamage(attacker,baseDamage, attackerDamageMul, critical,type);
     if (!endurancehas && hpBeforeDamage - dmg <= 0
             && !attacker.sacrifice) {
                if(endurancetimes==1){
                endurancehas = true;
                }
        endurancetimes++;
        hp = maxhp; // endurance HP
        System.out.println(name + " You cannot Defeat me!");
        counter(attacker);
        return dmg;
    }
    else{
        if(hp<=0 && !endurancehas && attacker.sacrifice)
            System.out.println("ENDURANCE NULLIFIED BY "+attacker.name);
        }
    applyTempDefenseUp(0.2,5);
    attacker.cardcostincreaseall(10, 10);
    attacker.applyTempDefenseDown(0.2,5);
    return dmg;
}


}
