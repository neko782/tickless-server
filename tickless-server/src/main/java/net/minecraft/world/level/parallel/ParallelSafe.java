package net.minecraft.world.level.parallel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A mod callback that obeys world ownership and transaction rules. It may run on a worker.
 * Shared mod state must be thread safe; irreversible effects must use WorldTxn.afterCommit.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ParallelSafe {
}
