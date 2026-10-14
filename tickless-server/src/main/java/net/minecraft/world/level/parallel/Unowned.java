package net.minecraft.world.level.parallel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a field, or every field write in a method, from ownership checks.
 *
 * <p>The build rewrites field writes on entities and block entities to lock (or check) their
 * owner first; see {@link WorldOwnership}. Use this for deliberate races that a player cannot
 * notice, such as caches and packet bookkeeping written by threads that do not own the object.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Unowned {
}
