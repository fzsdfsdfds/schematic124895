package com.example.schematicbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class Builder {
    private static final int SCAN_BUDGET = 8000;
    private static final int CAND_MAX = 64;
    private static final int MAX_TRIES = 24;
    private static final int SPARE_HOTBAR_SLOT = 8;

    /** properties that are decided by HOW a block is placed (the rest is derived from neighbours) */
    private static final Set<String> ORIENT = Set.of(
        "facing", "half", "type", "axis", "face", "rotation", "attachment", "orientation", "hanging");

    /** offsets (in the plane of the clicked face) to try as the click point */
    private static final double[][] OFFSETS = {
        {0, 0}, {0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25},
        {0.35, 0}, {-0.35, 0}, {0, 0.35}, {0, -0.35}
    };

    private final Minecraft mc = Minecraft.getInstance();

    // settings
    public double range = 4.5;
    public int blocksPerTick = 1;
    public int delay = 1;
    public int maxAttempts = 3;
    public boolean strictOrientation = true;
    public boolean preview = true;
    public int previewRadius = 32;
    public boolean autoWalk = true;

    private Schematic schematic;
    private String loadedName = "";
    private BlockPos origin;
    private boolean running;
    private boolean[] done;
    private int[] attempts;
    private int[] retryAt;
    private int first;
    private int doneCount;
    private int timer;
    private int tickCounter;
    private final Set<net.minecraft.world.level.block.Block> warned = new HashSet<>();

    private record Cand(int i, double d) {}
    private record Plan(BlockHitResult hit, float yaw, float pitch) {}

    // ---------- helpers ----------

    public static Path schematicsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("schematics").normalize();
    }

    public static List<String> listFiles() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(schematicsDir())) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase().endsWith(".litematic")) out.add(n.substring(0, n.length() - ".litematic".length()));
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    public void msg(String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[SchemBuilder] " + text), false);
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    public Schematic schematic() {
        return schematic;
    }

    public BlockPos origin() {
        return origin;
    }

    // ---------- load / control ----------

    public void load(String name) {
        stop(false);
        if (mc.player == null) return;

        name = name.trim();
        if (!name.toLowerCase().endsWith(".litematic")) name += ".litematic";

        Path dir = schematicsDir();
        Path path = dir.resolve(name).normalize();
        if (!path.startsWith(dir)) {
            msg("Niedozwolona sciezka pliku.");
            return;
        }
        if (!Files.isRegularFile(path)) {
            msg("Nie znaleziono pliku: " + path);
            return;
        }

        Schematic loaded;
        try {
            loaded = Schematic.load(path);
        } catch (IOException | RuntimeException e) {
            msg("Nie udalo sie wczytac schematu: " + e.getMessage());
            return;
        }

        if (loaded.entries.isEmpty()) {
            msg("Schemat nie zawiera zadnych blokow do postawienia.");
            return;
        }

        schematic = loaded;
        loadedName = name;
        origin = mc.player.blockPosition();
        resetProgress();

        msg("Wczytano " + loaded.entries.size() + " blokow z " + name + ". Origin: " + fmt(origin) + ".");
        if (loaded.skipped > 0) msg("Pominieto " + loaded.skipped + " blokow, ktorych nie da sie postawic z przedmiotu.");
        msg("Podglad (ghost) jest wlaczony. Ustaw miejsce: /schem origin, potem /schem start.");
    }

    private void resetProgress() {
        int n = schematic.entries.size();
        done = new boolean[n];
        attempts = new int[n];
        retryAt = new int[n];
        first = 0;
        doneCount = 0;
        timer = 0;
        warned.clear();
    }

    public void setOrigin(BlockPos pos) {
        origin = pos;
        if (schematic != null) resetProgress();
        msg("Origin: " + fmt(pos));
    }

    public void start() {
        if (schematic == null) {
            msg("Najpierw wczytaj schemat: /schem load <nazwa>");
            return;
        }
        running = true;
        timer = 0;
        msg("Start budowy.");
    }

    public void stop(boolean announce) {
        if (running && announce) msg("Zatrzymano.");
        running = false;
        if (mc.player != null) {
            mc.player.zza = 0;
            mc.player.xxa = 0;
        }
    }

    public void status() {
        if (schematic == null) {
            msg("Brak wczytanego schematu.");
            return;
        }
        msg((running ? "Buduje" : "Zatrzymany") + ": " + loadedName + " " + doneCount + "/" + schematic.entries.size()
            + ", origin " + fmt(origin) + ", zasieg " + range + ", " + blocksPerTick + " blok/tick, delay " + delay
            + ", orientacja " + (strictOrientation ? "strict" : "loose") + ", podglad " + (preview ? "on" : "off")
            + ", walk " + (autoWalk ? "on" : "off") + ".");
    }

    // ---------- main loop ----------

    public void tick() {
        tickCounter++;
        if (!running || schematic == null || done == null) return;

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || player.connection == null) return;
        if (mc.screen != null) return; // pauza gdy otwarte GUI

        if (timer > 0) {
            timer--;
            return;
        }
        timer = delay;

        List<Schematic.Entry> entries = schematic.entries;
        int n = entries.size();

        while (first < n && done[first]) first++;
        if (first >= n) {
            msg("Budowa zakonczona.");
            running = false;
            return;
        }

        double reach = Math.min(range, player.blockInteractionRange());
        double lim = (reach + 1.0) * (reach + 1.0);
        Vec3 eye = player.getEyePosition();

        // 1) zbierz kandydatow
        List<Cand> cands = new ArrayList<>();
        int scanned = 0;
        for (int i = first; i < n && scanned < SCAN_BUDGET && cands.size() < CAND_MAX; i++) {
            if (done[i] || retryAt[i] > tickCounter) continue;
            scanned++;

            Schematic.Entry e = entries.get(i);
            BlockPos pos = origin.offset(e.x(), e.y(), e.z());
            double d = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d > lim) continue;

            BlockState current = level.getBlockState(pos);
            if (current.is(e.state().getBlock())) {
                done[i] = true;
                doneCount++;
                continue;
            }
            if (!current.canBeReplaced()) continue; // zajete innym blokiem - nie niszczymy

            if (attempts[i] >= maxAttempts) {
                done[i] = true;
                continue;
            }
            cands.add(new Cand(i, d));
        }

        // 2) najblizsze najpierw
        cands.sort(Comparator.comparingDouble(Cand::d));

        // 2b) brak kandydatow w zasiegu -> podejdź do najbliższego bloku do postawienia
        if (cands.isEmpty() && autoWalk) {
            walkTowardsNearest(player, level, entries, n, eye);
            return;
        }

        Inventory inv = player.getInventory();
        int placed = 0;
        int tries = 0;

        for (Cand c : cands) {
            if (placed >= blocksPerTick || tries >= MAX_TRIES) break;
            tries++;

            int i = c.i();
            Schematic.Entry e = entries.get(i);
            BlockPos pos = origin.offset(e.x(), e.y(), e.z());
            Item item = e.state().getBlock().asItem();

            int slot = -1;
            for (int s = 0; s < 36; s++) {
                if (inv.getItem(s).is(item)) {
                    slot = s;
                    break;
                }
            }
            if (slot < 0) {
                if (warned.add(e.state().getBlock())) msg("Brak w ekwipunku: " + e.state().getBlock().getName().getString());
                retryAt[i] = tickCounter + 40;
                continue;
            }

            if (slot >= 9) {
                // przenies do paska (swap ze slotem 9), postaw w kolejnej turze
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, SPARE_HOTBAR_SLOT, ClickType.SWAP, player);
                timer = 1;
                return;
            }

            Plan plan = plan(player, level, pos, e.state(), inv.getItem(slot), reach);
            if (plan == null) {
                retryAt[i] = tickCounter + 10; // sprobuj ponownie gdy sie przesuniesz
                continue;
            }

            attempts[i]++;
            place(player, inv, slot, plan);
            placed++;
        }
    }

    // ---------- placement planning ----------

    private static boolean interactable(BlockState s) {
        var b = s.getBlock();
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
            || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
            || b instanceof BedBlock || b instanceof NoteBlock || b instanceof DiodeBlock;
    }

    private static Vec3 pointOnFace(BlockPos n, Direction face, double a, double b) {
        double cx = n.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = n.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = n.getZ() + 0.5 + face.getStepZ() * 0.5;
        switch (face.getAxis()) {
            case Y -> {
                cx += a;
                cz += b;
            }
            case X -> {
                cz += a;
                cy += b;
            }
            default -> {
                cx += a;
                cy += b;
            }
        }
        return new Vec3(cx, cy, cz);
    }

    private static boolean matches(BlockState placed, BlockState target) {
        for (Property<?> p : target.getProperties()) {
            if (!ORIENT.contains(p.getName())) continue;
            if (!placed.hasProperty(p)) continue;
            if (!Objects.equals(placed.getValue(p), target.getValue(p))) return false;
        }
        return true;
    }

    /**
     * Szuka punktu klikniecia, ktory:
     *  - jest w zasiegu,
     *  - jest realnie widoczny (promien z oczu trafia dokladnie w ta sciane),
     *  - przy patrzeniu w ten punkt vanilla da dokladnie taki stan bloku (facing/half/axis...) jak w schemacie.
     */
    private Plan plan(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState target, ItemStack stack, double reach) {
        Vec3 eye = player.getEyePosition();
        double r2 = reach * reach;
        Plan fallback = null;

        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty()) continue;
            if (interactable(ns) || level.getBlockEntity(n) != null) continue;

            Direction face = d.getOpposite();

            for (double[] off : OFFSETS) {
                Vec3 hitVec = pointOnFace(n, face, off[0], off[1]);
                if (eye.distanceToSqr(hitVec) > r2) continue;

                // widocznosc: promien lekko poza punkt, musi trafic w ten blok i te sciane
                Vec3 dir = hitVec.subtract(eye);
                double len = dir.length();
                if (len < 1.0E-4) continue;
                Vec3 end = hitVec.add(dir.scale(0.05 / len));
                BlockHitResult clip = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                if (clip.getType() != HitResult.Type.BLOCK || !clip.getBlockPos().equals(n) || clip.getDirection() != face) continue;

                float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));

                BlockHitResult hit = new BlockHitResult(hitVec, face, n, false);
                Plan plan = new Plan(hit, yaw, pitch);
                if (fallback == null) fallback = plan;

                if (orientationOk(player, target, stack, plan)) return plan;
            }
        }
        return strictOrientation ? null : fallback;
    }

    private boolean orientationOk(LocalPlayer player, BlockState target, ItemStack stack, Plan plan) {
        float oy = player.getYRot(), ox = player.getXRot();
        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());
        try {
            BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, plan.hit());
            BlockState placed = target.getBlock().getStateForPlacement(ctx);
            return placed != null && matches(placed, target);
        } catch (RuntimeException ex) {
            return false;
        } finally {
            player.setYRot(oy);
            player.setXRot(ox);
        }
    }


    /** Podchodzi do najbliższego jeszcze niepostawionego bloku (poza zasięgiem). */
    private void walkTowardsNearest(LocalPlayer player, ClientLevel level, List<Schematic.Entry> entries, int n, Vec3 eye) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        int scanned = 0;
        for (int i = first; i < n && scanned < SCAN_BUDGET; i++) {
            if (done[i] || retryAt[i] > tickCounter) continue;
            scanned++;
            Schematic.Entry e = entries.get(i);
            BlockPos pos = origin.offset(e.x(), e.y(), e.z());
            BlockState current = level.getBlockState(pos);
            if (current.is(e.state().getBlock())) {
                done[i] = true;
                doneCount++;
                continue;
            }
            if (!current.canBeReplaced()) continue;
            if (attempts[i] >= maxAttempts) {
                done[i] = true;
                continue;
            }
            double d = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return;

        Schematic.Entry e = entries.get(best);
        BlockPos pos = origin.offset(e.x(), e.y(), e.z());
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 from = player.position();
        Vec3 delta = target.subtract(from);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        // patrz w stronę bloku
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.max(horiz, 1.0E-4)));
        player.setYRot(yaw);
        player.setXRot(Math.max(-45f, Math.min(45f, pitch)));
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, player.getXRot(), player.onGround(), player.horizontalCollision));

        // idź do przodu (zza = forward impulse w układzie gracza)
        player.zza = 1.0f;
        player.xxa = 0.0f;
        if (player.input != null) {
            try {
                player.input.forwardImpulse = 1.0f;
                player.input.leftImpulse = 0.0f;
            } catch (Throwable ignored) {
            }
        }
        // proste unikanie przeszkód: skok gdy blok przed nogami
        BlockPos ahead = player.blockPosition().relative(player.getDirection());
        BlockState feet = level.getBlockState(ahead);
        BlockState head = level.getBlockState(ahead.above());
        if (!feet.canBeReplaced() && head.canBeReplaced() && player.onGround()) {
            player.jumpFromGround();
        }
    }

    private void place(LocalPlayer player, Inventory inv, int slot, Plan plan) {
        float oy = player.getYRot(), ox = player.getXRot();
        int prev = inv.getSelectedSlot();

        // serwer musi znac rotacje, z ktorej "patrzymy" na blok (ta sama, ktora symulowalismy)
        player.connection.send(new ServerboundMovePlayerPacket.Rot(plan.yaw(), plan.pitch(), player.onGround(), player.horizontalCollision));
        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());

        inv.setSelectedSlot(slot);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
        player.swing(InteractionHand.MAIN_HAND);

        player.setYRot(oy);
        player.setXRot(ox);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(oy, ox, player.onGround(), player.horizontalCollision));

        if (prev != slot) {
            inv.setSelectedSlot(prev);
            player.connection.send(new ServerboundSetCarriedItemPacket(prev));
        }
    }
}
