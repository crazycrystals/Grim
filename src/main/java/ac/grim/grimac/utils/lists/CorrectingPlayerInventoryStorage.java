package ac.grim.grimac.utils.lists;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.inventory.InventoryStorage;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is responsible for lag compensation of the player's inventory
 * Since I don't want to spend months finding version differences in inventory
 * Or copy (and debug) over around 5k lines of code to accomplish inventories
 * Grim uses a hybrid system for inventories - we lag compensate but rely on the server
 * for truth when the client seems to match the server's state
 * <p>
 * To accomplish this we:
 * - Track items changed when the player swaps or moves items in a basic inventory
 * - Track items when the player has placed a block, for example
 * <p>
 * There is somewhat of a race condition that we must fix, however, with some smart thinking.
 * We only see bukkit changes at the end of the tick.
 * <p>
 * Apply this only to the player's inventory for simplicity reasons
 * Horses and stuff, the metadata for saddles is server authoritative
 * No inventory directly affects us other than the player's inventory, if other inventories
 * desync and break the player's inventory, we will recover from it with this class.
 */
public class CorrectingPlayerInventoryStorage extends InventoryStorage {

    GrimPlayer player;
    // The key for this map is the inventory slot ID
    // The value for this map is the transaction that we care about
    Map<Integer, Integer> serverIsCurrentlyProcessingThesePredictions = new HashMap<>();
    // A list of predictions the client has made for inventory changes
    // Remove if the server rejects these changes
    Map<Integer, Integer> pendingFinalizedSlot = new ConcurrentHashMap<>();

    public CorrectingPlayerInventoryStorage(GrimPlayer player, int size) {
        super(size);
        this.player = player;
    }

    // 1.17+ clients send what slots they have changed.  This makes our jobs much easier.
    // Required as server now only sends changes if client disagrees with them.
    public void handleClientClaimedSlotSet(int slotID) {
        if (slotID >= 0 && slotID <= Inventory.ITEMS_END) {
            pendingFinalizedSlot.put(slotID, GrimAPI.INSTANCE.getTickManager().currentTick + 5);
        }
    }

    public void handleServerCorrectSlot(int slotID) {
        if (slotID >= 0 && slotID <= Inventory.ITEMS_END) {
            serverIsCurrentlyProcessingThesePredictions.put(slotID, player.lastTransactionSent.get());
        }
    }

    // This is more meant for pre-1.17 clients, but mojang fucked up netcode AGAIN in 1.17, so
    // we must use this for 1.17 clients as well... at least you tried Mojang.
    @Override
    public void setItem(int item, ItemStack stack) {
        // If there is a more recent change to this one, don't override it
        Integer finalTransaction = serverIsCurrentlyProcessingThesePredictions.get(item);

        // If the server is currently sending a packet to the player AND it is the final change to the slot
        // OR, the client was in control of setting this slot
        if (finalTransaction == null || player.lastTransactionReceived.get() >= finalTransaction) {
            // This is the last change for this slot, try to resync this slot if possible
            pendingFinalizedSlot.put(item, GrimAPI.INSTANCE.getTickManager().currentTick + 5);
            serverIsCurrentlyProcessingThesePredictions.remove(item);
        }

        super.setItem(item, stack);
    }

    private void checkThatBukkitIsSynced(int slot) {
        // The player isn't fully logged in yet, don't bother checking
        if (player.bukkitPlayer == null) return;
        // We aren't tracking the player's inventory, so don't bother
        if (!player.getInventory().isPacketInventoryActive) return;

        // Bukkit uses different slot ID's to vanilla
        int bukkitSlot = player.getInventory().getBukkitSlot(slot); // 8 -> 39, should be 36

        if (bukkitSlot != -1) {
            org.bukkit.inventory.ItemStack bukkitItem = player.bukkitPlayer.getInventory().getItem(bukkitSlot);

            ItemStack existing = getItem(slot);
            ItemStack toPE = SpigotConversionUtil.fromBukkitItemStack(bukkitItem);


            if (!ItemStack.isSameItemSameTags(existing, toPE) || existing.getAmount() != toPE.getAmount()) {
                player.bukkitPlayer.updateInventory();
                setItem(slot, toPE);
            }
        }
    }

    public void tickWithBukkit() {
        if (player.bukkitPlayer == null) return;

        int tickID = GrimAPI.INSTANCE.getTickManager().currentTick;
        for (Iterator<Map.Entry<Integer, Integer>> it = pendingFinalizedSlot.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (entry.getValue() <= tickID) {
                checkThatBukkitIsSynced(entry.getKey());
                it.remove();
            }
        }

        // Every five ticks, we pull a new item for the player
        // This means no desync will last longer than 10 seconds
        // (Required as mojang has screwed up some things with inventories that we can't easily fix.
        // Don't spam this as it could cause lag (I was getting 0.3 ms to query this, this is done async though)
        // TODO: PacketEvents is being really inefficient here to convert this... we should improve this!
        if (tickID % 5 == 0) {
            int slotToCheck = (tickID / 5) % getSize();
            // If both these things are true, there is nothing that should be broken.
            if (!pendingFinalizedSlot.containsKey(slotToCheck) && !serverIsCurrentlyProcessingThesePredictions.containsKey(slotToCheck)) {
                checkThatBukkitIsSynced(slotToCheck);
            }
        }
    }
}
