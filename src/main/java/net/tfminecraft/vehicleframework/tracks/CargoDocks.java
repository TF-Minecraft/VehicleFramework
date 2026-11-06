package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.ownership.OwnershipAccess;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.tracks.DockRegistry.Dock;
import net.tfminecraft.vehicleframework.tracks.DockRegistry.Mode;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;

/**
 * Loads and unloads tape-driven trains at cargo docks. When a train stops at a stop on its
 * tape, each car with storage beside a dock its owner marked moves items: a load dock into
 * the car, an unload dock out of it, as much as fits up to a limit per stop.
 *
 * <p>Only a car and a dock with the same owner trade, so a dock cannot take from or fill
 * another player's train. Items are always worked out on copies; for a train running
 * unloaded the car's saved row is written first and the dock changed only once it is saved,
 * so a failed save changes nothing.
 */
public final class CargoDocks {
	/** A dock block's slots, and whether it is a shulker box (which cannot hold another). */
	public record Storage(Inventory inventory, boolean shulker) {
	}

	private CargoDocks() {
	}

	/** A loaded train stopped at a recorded stop: serve the docks beside its cars. */
	public static void serveLive(ActiveVehicle loco) {
		if (!Cache.trainDocksEnabled || loco == null || DockRegistry.size() == 0) {
			return;
		}
		TrainHandler train = loco.getTrainHandler();
		int budget = Cache.trainDockItemsPerStop;
		int moved = 0;
		for (ActiveVehicle car : train.layout().cars()) {
			if (!cargoCar(train, car) || car.getEntity() == null) {
				continue;
			}
			Location at = car.getEntity().getLocation();
			World world = at.getWorld();
			for (Dock dock : DockRegistry.near(world.getName(), at.getX(), at.getY(), at.getZ(), Cache.trainDockReach)) {
				String owner = car.getOwnerData() == null ? null : car.getOwnerData().getOwner();
				if (moved >= budget || !owns(owner, dock) || !loaded(world, chunks(dock))) {
					continue;
				}
				Storage storage = storage(world, dock);
				if (storage == null) {
					continue;
				}
				Inventory inventory = storage.inventory();
				List<Container> containers = new ArrayList<>(car.getContainerHandler().getContainers().values());
				ItemStack[] before = copy(inventory.getContents());
				Plan plan = plan(dock.mode(), before, containers, storage.shulker(), budget - moved);
				if (plan.moved() > 0) {
					for (int i = 0; i < containers.size(); i++) {
						containers.get(i).setContents(plan.cars().get(i));
					}
					applyDock(inventory, before, plan.dock());
					moved += plan.moved();
					RecorderLog.append("DOCK live mode=" + dock.mode() + " moved=" + plan.moved() + " car="
							+ car.getUUID() + " at=" + dock.key());
				}
			}
		}
	}

	/** A train running unloaded arrived at a recorded stop: serve the docks beside its cars. */
	public static void servePaper(GhostTrain ghost, int stop) {
		if (!Cache.trainDocksEnabled || DockRegistry.size() == 0) {
			return;
		}
		World world = Bukkit.getWorld(ghost.world);
		if (world == null) {
			return;
		}
		ghost.dockBudget = Cache.trainDockItemsPerStop;
		int serial = ghost.stopSerial;
		for (int i = 0; i < ghost.spots.size() && i < ghost.cars.size(); i++) {
			// As loaded: every car of a type the locomotive takes fuel from.
			if (ghost.isFuelCar(i) && !Cache.trainDockFuelCars) {
				continue;
			}
			TrackPose pose = ghost.spots.get(i).pose();
			for (Dock dock : DockRegistry.near(ghost.world, pose.x, pose.y, pose.z, Cache.trainDockReach)) {
				if (owns(ghost.owners.get(i), dock)) {
					int car = i;
					whenLoaded(world, dock, () -> paper(ghost, car, dock, serial, stop));
				}
			}
		}
	}

	// Runs the work with the dock's chunks loaded; those that are not are loaded in the
	// background and held only while it runs.
	static void whenLoaded(World world, Dock dock, Runnable work) {
		List<int[]> chunks = chunks(dock);
		List<CompletableFuture<?>> loading = new ArrayList<>();
		for (int[] chunk : chunks) {
			if (!world.isChunkLoaded(chunk[0], chunk[1])) {
				loading.add(world.getChunkAtAsync(chunk[0], chunk[1], false));
			}
		}
		if (loading.isEmpty()) {
			work.run();
			return;
		}
		CompletableFuture.allOf(loading.toArray(new CompletableFuture[0])).whenComplete((done, failure) -> {
			// A chunk never generated comes back null and stays unloaded: leave that dock for now.
			if (failure != null || VehicleFramework.plugin == null || !VehicleFramework.plugin.isEnabled()
					|| !loaded(world, chunks)) {
				return;
			}
			for (int[] chunk : chunks) {
				world.addPluginChunkTicket(chunk[0], chunk[1], VehicleFramework.plugin);
			}
			try {
				work.run();
			} catch (RuntimeException e) {
				VFLogger.log("A cargo dock trade failed: " + e);
			} finally {
				for (int[] chunk : chunks) {
					world.removePluginChunkTicket(chunk[0], chunk[1], VehicleFramework.plugin);
				}
			}
		});
	}

	// The dock's chunk, and the neighbour a double chest at the chunk's edge may reach into.
	static List<int[]> chunks(Dock dock) {
		List<int[]> chunks = new ArrayList<>();
		int cx = dock.x() >> 4;
		int cz = dock.z() >> 4;
		chunks.add(new int[] {cx, cz});
		int edgeX = (dock.x() & 15) == 0 ? -1 : (dock.x() & 15) == 15 ? 1 : 0;
		int edgeZ = (dock.z() & 15) == 0 ? -1 : (dock.z() & 15) == 15 ? 1 : 0;
		if (edgeX != 0) {
			chunks.add(new int[] {cx + edgeX, cz});
		}
		if (edgeZ != 0) {
			chunks.add(new int[] {cx, cz + edgeZ});
		}
		return chunks;
	}

	private static boolean loaded(World world, List<int[]> chunks) {
		for (int[] chunk : chunks) {
			if (!world.isChunkLoaded(chunk[0], chunk[1])) {
				return false;
			}
		}
		return true;
	}

	// One car of a train on paper at one dock, against the car's saved row.
	static void paper(GhostTrain ghost, int car, Dock dock, int serial, int stop) {
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		World world = Bukkit.getWorld(ghost.world);
		// It may have spawned, or moved on to its next stop, while the chunk loaded.
		if (repository == null || world == null || !TrainGhosts.running(ghost) || ghost.stopSerial != serial
				|| ghost.dockBudget <= 0) {
			return;
		}
		Storage storage = storage(world, dock);
		if (storage == null) {
			return;
		}
		Inventory inventory = storage.inventory();
		boolean fuelCar = car == 1 && ghost.fuelPath != null;
		if (fuelCar) {
			TrainGhosts.flushFuel(repository, ghost);
		}
		Optional<StoredCars.Row> row = StoredCars.read(repository, ghost.cars.get(car));
		// The owner may have changed while it ran unloaded.
		if (row.isEmpty() || !owns(row.get().owner(), dock)) {
			return;
		}
		List<Container> containers = StoredCars.containers(row.get());
		ItemStack[] before = copy(inventory.getContents());
		Plan plan = plan(dock.mode(), before, containers, storage.shulker(), ghost.dockBudget);
		if (plan.moved() <= 0) {
			return;
		}
		for (int i = 0; i < containers.size(); i++) {
			containers.get(i).setContents(plan.cars().get(i));
		}
		StoredCars.putContainers(row.get(), containers);
		if (!StoredCars.write(repository, row.get())) {
			VFLogger.log("Could not save train car " + ghost.cars.get(car) + " at a dock; nothing was moved");
			return;
		}
		applyDock(inventory, before, plan.dock());
		ghost.dockBudget -= plan.moved();
		if (fuelCar) {
			int reserve = 0;
			for (Container container : containers) {
				reserve += container.countMatching(ghost.fuelPath);
			}
			ghost.reserve = reserve;
		}
		RecorderLog.append("DOCK paper mode=" + dock.mode() + " moved=" + plan.moved() + " car="
				+ ghost.cars.get(car) + " stop=" + stop + " at=" + dock.key());
	}

	/** What a transfer leaves in the dock and in each container, and how many items moved. */
	record Plan(ItemStack[] dock, List<ItemStack[]> cars, int moved) {
	}

	static Plan plan(Mode mode, ItemStack[] dockBefore, List<Container> containers, boolean shulkerDock,
			int budget) {
		ItemStack[] dock = dockBefore.clone();
		List<ItemStack[]> cars = new ArrayList<>();
		int moved = 0;
		for (Container container : containers) {
			ItemStack[] contents = container.contents();
			if (mode == Mode.LOAD) {
				moved += CargoTransfer.move(dock, contents, container::allows, budget - moved, CargoTransfer.ITEMS);
			} else {
				moved += CargoTransfer.move(contents, dock, accepts(shulkerDock), budget - moved, CargoTransfer.ITEMS);
			}
			cars.add(contents);
		}
		return new Plan(dock, cars, moved);
	}

	// A shulker box cannot hold another shulker box.
	static Predicate<ItemStack> accepts(boolean shulkerDock) {
		return item -> !shulkerDock || !item.getType().name().endsWith("SHULKER_BOX");
	}

	// Changes only the slots the plan changed.
	static void applyDock(Inventory inventory, ItemStack[] before, ItemStack[] after) {
		for (int i = 0; i < after.length; i++) {
			if (after[i] != before[i]) {
				inventory.setItem(i, after[i]);
			}
		}
	}

	static ItemStack[] copy(ItemStack[] contents) {
		ItemStack[] out = new ItemStack[contents.length];
		for (int i = 0; i < contents.length; i++) {
			out[i] = contents[i] == null ? null : contents[i].clone();
		}
		return out;
	}

	// Not a car being destroyed: it already dropped what it held.
	static boolean cargoCar(TrainHandler loco, ActiveVehicle car) {
		return car.hasContainers() && !car.isDestroyed() && (Cache.trainDockFuelCars || !loco.acceptsFuelCar(car));
	}

	static boolean owns(String owner, Dock dock) {
		return owner != null && !owner.isBlank() && !"none".equalsIgnoreCase(owner)
				&& owner.equalsIgnoreCase(dock.owner());
	}

	/** The dock block's slots, or null (and the dock forgotten) if it is no longer storage. */
	static Storage storage(World world, Dock dock) {
		Storage storage = storage(world.getBlockAt(dock.x(), dock.y(), dock.z()));
		if (storage == null) {
			DockRegistry.set(dock.world(), dock.x(), dock.y(), dock.z(), null, null);
			RecorderLog.append("DOCK gone at=" + dock.key());
		}
		return storage;
	}

	/**
	 * A storage block's slots, the whole of a double chest: chests, barrels, shulker boxes,
	 * hoppers, dispensers and droppers. Null for anything else, such as a furnace, whose
	 * slots have rules.
	 */
	public static Storage storage(Block block) {
		BlockState state = block.getState(false);
		if (state instanceof Chest || state instanceof Barrel || state instanceof ShulkerBox
				|| state instanceof Hopper || state instanceof Dispenser || state instanceof Dropper) {
			return new Storage(((org.bukkit.block.Container) state).getInventory(), state instanceof ShulkerBox);
		}
		return null;
	}

	/** A player who just opened this storage block marks it: load, then unload, then off. */
	public static void cycle(Player player, Block block) {
		String owner = OwnershipAccess.ownerValue(player).orElse(null);
		if (owner == null) {
			player.sendMessage("§cYou need a character to mark a cargo dock");
			return;
		}
		String world = block.getWorld().getName();
		Dock dock = DockRegistry.at(world, block.getX(), block.getY(), block.getZ());
		if (dock != null && !dock.owner().equalsIgnoreCase(owner) && !Permissions.isAdmin(player)) {
			player.sendMessage("§cThis cargo dock belongs to someone else");
			return;
		}
		Mode next = dock == null ? Mode.LOAD : dock.mode().next();
		DockRegistry.set(world, block.getX(), block.getY(), block.getZ(), next, dock == null ? owner : dock.owner());
		if (next == Mode.LOAD) {
			player.sendMessage("§aCargo dock: load. Your tape-driven trains that stop here take items from it.");
		} else if (next == Mode.UNLOAD) {
			player.sendMessage("§aCargo dock: unload. Your tape-driven trains that stop here leave their cargo in it.");
		} else {
			player.sendMessage("§eCargo dock removed");
		}
		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, next == null ? 0.8f : 1.2f);
		RecorderLog.interact("dock", player.getName() + " mode=" + next + " at=" + DockRegistry.key(world,
				block.getX(), block.getY(), block.getZ()));
	}
}
