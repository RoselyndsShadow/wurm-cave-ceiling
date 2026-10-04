package net.roselyndsshadow.caveceiling;

import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.mesh.Tiles;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CaveCeiling
 *
 * Client-side visual mod for Wurm Unlimited.
 * In caves, the vanilla client always tessellates the ceiling with TILE_CAVE's
 * rock texture. This patch makes the ceiling use the same paving texture that
 * the client is already using for the floor beneath it.
 *
 * Debugging:
 *   cave_ceiling_debug          - toggle debug mode
 *   cave_ceiling_debug on       - enable debug mode
 *   cave_ceiling_debug off      - disable debug mode
 *   cave_ceiling_debug status   - print current diagnostic status
 *
 * When debug mode is enabled, detailed ceiling-selection decisions are written
 * to CaveCeiling_Debug.txt. Logging is deliberately capped/rate-limited because
 * resolveCeilingTile() runs from the renderer and may be called very frequently.
 */
public class CaveCeiling implements WurmClientMod, PreInitable, Initable, Configurable {

    private static final Logger LOGGER = Logger.getLogger(CaveCeiling.class.getName());
    public static final String VERSION = "1.2-debug";

    private static final String DEBUG_FILE = "CaveCeiling_Debug.txt";
    private static final int MAX_LOGGED_TILE_STATES = 400;
    private static final long SUMMARY_INTERVAL = 2500L;

    public static HeadsUpDisplay hud;

    /** If false, the injected expression falls back to vanilla TILE_CAVE. */
    public static volatile boolean enabled = true;

    /**
     * If true, only real paving/road tile types are mirrored onto the ceiling.
     * Bare cave, reinforced cave floor, and prepared reinforced cave floor keep
     * the normal rock ceiling.
     */
    public static volatile boolean pavedOnly = true;

    /** Runtime debugger, controlled with cave_ceiling_debug. */
    private static volatile boolean debug = false;

    /** Number of TILE_CAVE reads patched in CaveTileData.tesselate(). */
    private static volatile int patchCount = -1;

    /** Human-readable result of preInit instrumentation. */
    private static volatile String patchStatus = "preInit has not run yet";

    /** Total calls to resolveCeilingTile() since client startup. */
    private static final AtomicLong resolverCalls = new AtomicLong(0L);

    /** Resolver call count at the moment debug mode was enabled. */
    private static volatile long debugStartCall = 0L;

    /** Last complete decision, useful for the status command. */
    private static volatile String lastDecision = "none yet";

    /**
     * A tile can be tessellated many times. Log a particular tile/material state
     * only once so debug mode does not murder rendering performance or fill disk.
     */
    private static final ConcurrentHashMap<String, Boolean> loggedTileStates =
            new ConcurrentHashMap<String, Boolean>();

    private static final Object FILE_LOCK = new Object();

    @Override
    public void configure(Properties properties) {
        enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true").trim());
        pavedOnly = Boolean.parseBoolean(properties.getProperty("pavedOnly", "true").trim());
    }

    // ---------------------------------------------------------------------
    // Bytecode patch
    // ---------------------------------------------------------------------

    @Override
    public void preInit() {
        try {
            final ClassPool pool = HookManager.getInstance().getClassPool();
            final CtClass caveTileData = pool.getCtClass("com.wurmonline.client.renderer.cave.CaveTileData");
            final CtMethod tessellate = caveTileData.getDeclaredMethod(
                    "tesselate",
                    new CtClass[]{pool.get("java.util.List"), CtClass.booleanType}
            );

            final int[] patches = {0};

            tessellate.instrument(new ExprEditor() {
                @Override
                public void edit(FieldAccess field) throws CannotCompileException {
                    // In this WU client there is one TILE_CAVE read in tesselate()
                    // used to choose the ceiling texture source.
                    if (field.isReader()
                            && "com.wurmonline.mesh.Tiles$Tile".equals(field.getClassName())
                            && "TILE_CAVE".equals(field.getFieldName())) {

                        field.replace(
                                "{ $_ = net.roselyndsshadow.caveceiling.CaveCeiling.resolveCeilingTile(" +
                                "this.caveBuffer, this.xTilePos, this.yTilePos); }"
                        );
                        patches[0]++;
                    }
                }
            });

            patchCount = patches[0];
            if (patchCount == 1) {
                patchStatus = "OK: patched exactly 1 TILE_CAVE ceiling site";
                LOGGER.info("CaveCeiling v" + VERSION + " " + patchStatus);
            } else {
                patchStatus = "WARNING: expected 1 TILE_CAVE ceiling site, patched " + patchCount;
                LOGGER.warning("CaveCeiling v" + VERSION + " " + patchStatus
                        + ". Client version may differ.");
            }
        } catch (Throwable t) {
            patchCount = -1;
            patchStatus = "FAILED: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            LOGGER.log(Level.SEVERE, "CaveCeiling preInit failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // HUD + console command
    // ---------------------------------------------------------------------

    @Override
    public void init() {
        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                    "init", "(II)V",
                    () -> (proxy, method, args) -> {
                        method.invoke(proxy, args);
                        hud = (HeadsUpDisplay) proxy;
                        return null;
                    }
            );
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "CaveCeiling could not hook HeadsUpDisplay.init", t);
        }

        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.console.WurmConsole",
                    "handleDevInput", "(Ljava/lang/String;[Ljava/lang/String;)Z",
                    () -> (proxy, method, args) -> {
                        String cmd = args != null && args.length > 0 ? (String) args[0] : null;
                        String[] data = args != null && args.length > 1 ? (String[]) args[1] : null;
                        if (handleInput(cmd, data)) {
                            return true;
                        }
                        return method.invoke(proxy, args);
                    }
            );
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "CaveCeiling could not hook WurmConsole.handleDevInput", t);
        }
    }

    public static boolean handleInput(String cmd, String[] data) {
        if (cmd == null) return false;

        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        if (!"cave_ceiling_debug".equalsIgnoreCase(clean)) {
            return false;
        }

        String arg = firstRealArgument(data, clean);

        if (arg == null || arg.isEmpty()) {
            setDebug(!debug);
            return true;
        }

        if ("on".equalsIgnoreCase(arg)
                || "true".equalsIgnoreCase(arg)
                || "1".equals(arg)) {
            setDebug(true);
            return true;
        }

        if ("off".equalsIgnoreCase(arg)
                || "false".equalsIgnoreCase(arg)
                || "0".equals(arg)) {
            setDebug(false);
            return true;
        }

        if ("status".equalsIgnoreCase(arg)) {
            printStatus();
            return true;
        }

        console("Usage: cave_ceiling_debug [on|off|status]");
        return true;
    }

    private static String firstRealArgument(String[] data, String command) {
        if (data == null) return null;
        for (int i = 0; i < data.length; i++) {
            String s = data[i];
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("/")) s = s.substring(1);
            if (s.equalsIgnoreCase(command)) continue;
            return s;
        }
        return null;
    }

    private static void setDebug(boolean value) {
        if (value == debug) {
            console("CaveCeiling debug is already " + (debug ? "ON" : "OFF") + ".");
            printStatus();
            return;
        }

        debug = value;

        if (debug) {
            debugStartCall = resolverCalls.get();
            loggedTileStates.clear();
            clearDebugFile();

            debugWrite("=== CaveCeiling v" + VERSION + " DEBUG ENABLED ===");
            debugWrite("patchStatus=" + patchStatus);
            debugWrite("patchCount=" + patchCount);
            debugWrite("enabled=" + enabled + " pavedOnly=" + pavedOnly);
            debugWrite("resolverCallsBeforeDebug=" + debugStartCall);
            debugWrite("Move/turn around inside a cave, preferably while standing near a visibly paved tile.");

            console("CaveCeiling debug ENABLED. Logging to " + DEBUG_FILE);
            printStatus();
        } else {
            long during = resolverCalls.get() - debugStartCall;
            debugWrite("=== DEBUG DISABLED; resolver calls while debugging=" + during + " ===");
            console("CaveCeiling debug DISABLED. Resolver calls while debugging: " + during);
        }
    }

    private static void printStatus() {
        long total = resolverCalls.get();
        long sinceDebug = debug ? total - debugStartCall : 0L;

        console("CaveCeiling v" + VERSION
                + " | debug=" + (debug ? "ON" : "OFF")
                + " | enabled=" + enabled
                + " | pavedOnly=" + pavedOnly);
        console("Patch: " + patchStatus + " | patchCount=" + patchCount);
        console("resolveCeilingTile calls: total=" + total
                + (debug ? " sinceDebug=" + sinceDebug : ""));
        console("Logged tile states: " + loggedTileStates.size()
                + "/" + MAX_LOGGED_TILE_STATES);
        console("Last decision: " + lastDecision);

        if (debug && sinceDebug == 0L) {
            console("IMPORTANT: debug is ON but resolveCeilingTile has not been called yet.");
            console("If this stays at 0 while cave terrain rebuilds, the injected ceiling patch is not executing.");
        }
    }

    private static void console(String message) {
        try {
            if (hud != null) {
                hud.consoleOutput(">>> [CaveCeiling] " + message);
            } else {
                LOGGER.info("[CaveCeiling] " + message);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Ceiling selection
    // ---------------------------------------------------------------------

    /**
     * Return the tile whose texture should be used on the cave ceiling.
     *
     * IMPORTANT: In this WU client the actual cave paving type is already stored
     * as CaveDataBuffer.getTileType(x,y). CaveDataBuffer.extra is NOT where the
     * normal paving material lives. The v1.1 debugger proved this: paved tiles
     * reported extra=0 while base was TILE_POTTERY_BRICKS, TILE_SANDSTONE_BRICKS,
     * TILE_COBBLESTONE_ROUND, etc.
     *
     * Selection order:
     *   1. temporary visual ROAD type, if present;
     *   2. base cave-floor type when it is a ROAD/paving tile;
     *   3. extra only as a guarded compatibility fallback when it resolves to a road;
     *   4. when pavedOnly=false, allow any non-null temporary/base type;
     *   5. otherwise vanilla TILE_CAVE.
     */
    public static Tiles.Tile resolveCeilingTile(CaveDataBuffer caveBuffer, int tileX, int tileY) {
        final long call = resolverCalls.incrementAndGet();

        if (!enabled) {
            Tiles.Tile result = Tiles.Tile.TILE_CAVE;
            maybeDebugDecision(call, tileX, tileY, caveBuffer,
                    false, null, (byte) 0, null, null,
                    result, "mod disabled");
            return result;
        }

        if (caveBuffer == null) {
            Tiles.Tile result = Tiles.Tile.TILE_CAVE;
            maybeDebugDecision(call, tileX, tileY, null,
                    false, null, (byte) 0, null, null,
                    result, "caveBuffer == null");
            return result;
        }

        try {
            boolean hasTemp = caveBuffer.hasTempType(tileX, tileY);
            Tiles.Tile temp = hasTemp ? caveBuffer.getTileTempType(tileX, tileY) : null;
            byte extra = caveBuffer.getExtra(tileX, tileY);
            Tiles.Tile extraTile = extra != 0 ? Tiles.getTile(extra) : null;
            Tiles.Tile base = caveBuffer.getTileType(tileX, tileY);

            Tiles.Tile result;
            String reason;

            // The renderer's temporary type should win when it is actual paving.
            if (hasTemp && isPavingTile(temp)) {
                result = temp;
                reason = "temporary paving/road type";

            // This is the normal case. The debugger showed that cave paving is
            // stored directly as the base tile type, not in CaveDataBuffer.extra.
            } else if (isPavingTile(base)) {
                result = base;
                reason = "base cave floor is paving/road";

            // Keep this only as a safe compatibility path for client/server variants.
            } else if (isPavingTile(extraTile)) {
                result = extraTile;
                reason = "paving/road from CaveDataBuffer.extra compatibility fallback";

            // Optional non-paving mode retained from v1.1.
            } else if (!pavedOnly && hasTemp && temp != null) {
                result = temp;
                reason = "temporary floor type (pavedOnly=false)";
            } else if (!pavedOnly && base != null) {
                result = base;
                reason = "base cave floor type (pavedOnly=false)";
            } else {
                result = Tiles.Tile.TILE_CAVE;
                reason = "base is not paving; vanilla cave ceiling fallback";
            }

            maybeDebugDecision(call, tileX, tileY, caveBuffer,
                    hasTemp, temp, extra, extraTile, base, result, reason);
            return result;

        } catch (Throwable t) {
            Tiles.Tile result = Tiles.Tile.TILE_CAVE;
            if (debug) {
                String msg = "ERROR call=" + call
                        + " tile=(" + tileX + "," + tileY + ") "
                        + t.getClass().getName() + ": " + String.valueOf(t.getMessage());
                lastDecision = msg;
                debugWrite(msg);
            }
            return result;
        }
    }

    /**
     * Wurm marks all normal paving types as roads. This gives us a much safer
     * test than hard-coding every individual material ID and automatically
     * covers pottery, sandstone, marble, slate, stone slabs, planks, gravel,
     * round/rough cobblestone, and other normal paving variants.
     */
    private static boolean isPavingTile(Tiles.Tile tile) {
        if (tile == null) return false;
        try {
            return tile.isRoad();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void maybeDebugDecision(long call,
                                           int tileX,
                                           int tileY,
                                           CaveDataBuffer caveBuffer,
                                           boolean hasTemp,
                                           Tiles.Tile temp,
                                           byte extra,
                                           Tiles.Tile extraTile,
                                           Tiles.Tile base,
                                           Tiles.Tile result,
                                           String reason) {
        if (!debug) return;

        int unsignedExtra = extra & 0xFF;

        String decision = "call=" + call
                + " tile=(" + tileX + "," + tileY + ")"
                + " hasTemp=" + hasTemp
                + " temp=" + describeTile(temp)
                + " tempRoad=" + isPavingTile(temp)
                + " extraSigned=" + extra
                + " extraUnsigned=" + unsignedExtra
                + " extraTile=" + describeTile(extraTile)
                + " extraRoad=" + isPavingTile(extraTile)
                + " base=" + describeTile(base)
                + " baseRoad=" + isPavingTile(base)
                + " => ceiling=" + describeTile(result)
                + " reason=" + reason;

        lastDecision = decision;

        String stateKey = tileX + ":" + tileY
                + ":" + (hasTemp ? 1 : 0)
                + ":" + tileId(temp)
                + ":" + unsignedExtra
                + ":" + tileId(extraTile)
                + ":" + tileId(base)
                + ":" + tileId(result)
                + ":" + enabled
                + ":" + pavedOnly;

        if (loggedTileStates.size() < MAX_LOGGED_TILE_STATES
                && loggedTileStates.putIfAbsent(stateKey, Boolean.TRUE) == null) {
            debugWrite(decision);
        }

        long sinceDebug = call - debugStartCall;
        if (sinceDebug > 0L && sinceDebug % SUMMARY_INTERVAL == 0L) {
            debugWrite("SUMMARY sinceDebug=" + sinceDebug
                    + " totalCalls=" + call
                    + " uniqueTileStates=" + loggedTileStates.size()
                    + " last=" + lastDecision);
        }
    }

    private static int tileId(Tiles.Tile tile) {
        if (tile == null) return -1;
        try {
            return tile.getId() & 0xFF;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String describeTile(Tiles.Tile tile) {
        if (tile == null) return "null";
        try {
            return tile.name()
                    + "[id=" + (tile.getId() & 0xFF)
                    + ", name=\"" + tile.getName() + "\""
                    + ", texture=\"" + tile.getTextureResource() + "\"]";
        } catch (Throwable t) {
            return String.valueOf(tile);
        }
    }

    // ---------------------------------------------------------------------
    // Debug file
    // ---------------------------------------------------------------------

    private static void clearDebugFile() {
        synchronized (FILE_LOCK) {
            try {
                FileWriter writer = new FileWriter(DEBUG_FILE, false);
                writer.write("");
                writer.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void debugWrite(String message) {
        if (message == null) return;
        synchronized (FILE_LOCK) {
            try {
                FileWriter writer = new FileWriter(DEBUG_FILE, true);
                String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
                writer.write("[" + time + "] " + message + System.lineSeparator());
                writer.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
