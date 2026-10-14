package net.minecraft.server.level;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.parallel.WorldTxn;

/** Opt-in benchmark tracing; one committed terminal outcome per handled attack packet. */
public final class AttackAudit {
    public static final boolean ENABLED = Boolean.getBoolean("tickless.attackAudit");
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private static final class Attempt {
        final String player; final long sequence; final int target;
        String reason = "unexplained";
        Attempt(String player, long sequence, int target) { this.player=player; this.sequence=sequence; this.target=target; }
    }
    public static void begin(String player, long sequence, int target) {
        if (ENABLED) CURRENT.set(new Attempt(player, sequence, target));
    }
    public static void reason(String reason) {
        if (!ENABLED) return;
        Attempt attempt=CURRENT.get(); if(attempt!=null) attempt.reason=reason;
    }
    public static void reason(Entity target, String reason) {
        if (!ENABLED) return;
        Attempt attempt=CURRENT.get(); if(attempt!=null && target.getId()==attempt.target) attempt.reason=reason;
    }
    public static boolean reject(Entity target, String reason) { reason(target,reason); return false; }
    public static void accepted(Entity target, boolean accepted) { if(ENABLED && accepted) reason(target,"accepted"); }
    public static void finish() {
        if (!ENABLED) return;
        Attempt attempt=CURRENT.get(); CURRENT.remove();
        if(attempt!=null) {
            String row="ATTACK_AUDIT "+attempt.player+" "+attempt.sequence+" "+attempt.target+" "+attempt.reason;
            WorldTxn.afterCommit(() -> LogUtils.getLogger().info(row));
        }
    }
    private AttackAudit() {}
}
