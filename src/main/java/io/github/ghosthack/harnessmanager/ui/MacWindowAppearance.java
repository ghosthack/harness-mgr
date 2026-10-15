package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Keeps the native macOS window chrome — title bar, traffic lights, native scrollbars —
 * in step with the app's light/dark theme, which is otherwise styled only inside the
 * Swing content area by the look and feel.
 *
 * <p>Two mechanisms, because one alone can't cover both cases:
 *
 * <ul>
 *   <li>{@link #applyBeforeAwt} sets {@code apple.awt.application.appearance}, which the
 *       JDK reads in {@code -[NSApplicationAWT finishLaunching]}. It must therefore run
 *       before any AWT/Swing initialization, and it is read <em>exactly once</em> — a
 *       later {@code setProperty} has no effect at all, which is why the live toggle
 *       cannot use it.
 *   <li>{@link #apply} sends {@code [NSApp setAppearance:]} through the FFM API, which
 *       does take effect immediately on a running app.
 * </ul>
 *
 * <p>Everything here is best-effort and platform-guarded: on a non-macOS host, or if the
 * Objective-C runtime can't be reached, the theme toggle still works and only the native
 * chrome stays where it was. A single failure disables further attempts, so a broken
 * environment can't produce a warning on every toggle.
 */
public final class MacWindowAppearance {

    private static final Logger LOG = LoggerFactory.getLogger(MacWindowAppearance.class);

    /** Apple AWT property controlling the application-wide native appearance. */
    static final String AWT_PROPERTY = "apple.awt.application.appearance";

    private static final String AQUA = "NSAppearanceNameAqua";
    private static final String DARK_AQUA = "NSAppearanceNameDarkAqua";

    /** The AppKit appearance matching an app theme. */
    static String appearanceName(AppSettings.UiTheme theme) {
        return theme == AppSettings.UiTheme.LIGHT ? AQUA : DARK_AQUA;
    }

    static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isMac() {
        return isMac(System.getProperty("os.name", ""));
    }

    /**
     * Sets the startup appearance. Must be called on the main thread before AWT/Swing is
     * touched — after that the JDK has already read the property and this does nothing.
     */
    public static void applyBeforeAwt(AppSettings.UiTheme theme) {
        applyBeforeAwt(theme, System.getProperty("os.name", ""));
    }

    /** Test seam that avoids mutating the process OS name. */
    static void applyBeforeAwt(AppSettings.UiTheme theme, String osName) {
        if (isMac(osName)) {
            System.setProperty(AWT_PROPERTY, appearanceName(theme));
        }
    }

    /**
     * Re-themes the chrome of a running app. Safe to call from the EDT: the AppKit work
     * is handed to the main thread asynchronously, so this never blocks on it.
     */
    public static void apply(AppSettings.UiTheme theme) {
        if (!isMac() || !ObjC.AVAILABLE) {
            return;
        }
        try {
            ObjC.setAppAppearance(appearanceName(theme));
        } catch (Throwable t) {
            // Chrome that stays dark is cosmetic; a failed toggle is not worth an
            // exception escaping into the EDT's handler.
            ObjC.disable("[NSApp setAppearance:] failed", t);
        }
    }

    private MacWindowAppearance() {
    }

    /**
     * The Objective-C runtime calls, bound once and reused.
     *
     * <p>{@code objc_msgSend} is declared variadic in C but must be called through a
     * signature matching the actual selector — the same rule as casting the function
     * pointer in C — so each arity gets its own handle rather than a variadic one.
     */
    private static final class ObjC {

        private static volatile boolean AVAILABLE;

        private static Linker linker;
        private static MethodHandle getClass;
        private static MethodHandle selRegister;
        private static MethodHandle poolPush;
        private static MethodHandle poolPop;
        private static MethodHandle send0;
        private static MethodHandle send1;
        private static MethodHandle sendPerform;

        static {
            boolean ok = false;
            try {
                if (isMac()) {
                    linker = Linker.nativeLinker();
                    // libobjc is not in the default lookup; it has to be opened by name.
                    SymbolLookup objc = SymbolLookup
                            .libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
                            .or(linker.defaultLookup());
                    getClass = handle(objc, "objc_getClass",
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    selRegister = handle(objc, "sel_registerName",
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    poolPush = handle(objc, "objc_autoreleasePoolPush",
                            FunctionDescriptor.of(ValueLayout.ADDRESS));
                    poolPop = handle(objc, "objc_autoreleasePoolPop",
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                    MemorySegment msgSend = objc.find("objc_msgSend").orElseThrow();
                    send0 = linker.downcallHandle(msgSend, FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    send1 = linker.downcallHandle(msgSend, FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
                    sendPerform = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
                    ok = true;
                }
            } catch (Throwable t) {
                // A JDK without FFM access, a hardened runtime, an unexpected libobjc —
                // all of it just means no live chrome switching.
                LOG.debug("native window appearance unavailable: {}", t.toString());
            }
            AVAILABLE = ok;
        }

        private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), fd);
        }

        static void disable(String message, Throwable cause) {
            AVAILABLE = false;
            LOG.warn("{} — native window chrome will not follow the theme ({})",
                    message, cause.toString());
        }

        /**
         * {@code [NSApp performSelectorOnMainThread:@selector(setAppearance:)
         * withObject:[NSAppearance appearanceNamed:name] waitUntilDone:NO]}
         */
        static void setAppAppearance(String appearanceName) throws Throwable {
            // An explicit pool: the EDT has none of its own, and the calls below return
            // autoreleased objects that would otherwise leak (and log about it).
            MemorySegment pool = (MemorySegment) poolPush.invokeExact();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = (MemorySegment) send1.invokeExact(
                        (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSString")),
                        (MemorySegment) selRegister.invokeExact(arena.allocateFrom("stringWithUTF8String:")),
                        arena.allocateFrom(appearanceName));
                MemorySegment appearance = (MemorySegment) send1.invokeExact(
                        (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSAppearance")),
                        (MemorySegment) selRegister.invokeExact(arena.allocateFrom("appearanceNamed:")),
                        name);
                MemorySegment app = (MemorySegment) send0.invokeExact(
                        (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication")),
                        (MemorySegment) selRegister.invokeExact(arena.allocateFrom("sharedApplication")));
                if (appearance.address() == 0 || app.address() == 0) {
                    disable("AppKit returned no appearance or no NSApp",
                            new IllegalStateException(appearanceName));
                    return;
                }
                // waitUntilDone:NO — blocking the EDT on the AppKit main thread invites a
                // deadlock, and nothing here needs the result. performSelectorOnMainThread
                // retains receiver and argument until it runs, so popping the pool at the
                // end of this method cannot leave the appearance dangling.
                sendPerform.invokeExact(app,
                        (MemorySegment) selRegister.invokeExact(arena.allocateFrom(
                                "performSelectorOnMainThread:withObject:waitUntilDone:")),
                        (MemorySegment) selRegister.invokeExact(arena.allocateFrom("setAppearance:")),
                        appearance, false);
            } finally {
                poolPop.invokeExact(pool);
            }
        }

        private ObjC() {
        }
    }
}
