package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeckTest {
    // A deck on top of a car: 4 wide, from z -5 to 5.6875, 5 blocks up.
    private static final Deck ROOF = new Deck(-2, 2, -5, 5.6875, 5, Deck.MAX_BOX);

    @Test
    void theRoofIsCoveredByShulkerSizedBoxesToItsEdges() {
        List<Deck.Box> boxes = ROOF.boxes(new Deck.Frame(0, 64, 0, 0, 0));
        // Two across the 4 wide roof, four along its 10.7 blocks.
        assertEquals(8, boxes.size());
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Deck.Box box : boxes) {
            assertEquals(3, box.size(), 1e-9);
            // Tops level with the roof.
            assertEquals(69, box.y() + box.size(), 1e-9);
            minX = Math.min(minX, box.x() - box.size() / 2);
            maxX = Math.max(maxX, box.x() + box.size() / 2);
            minZ = Math.min(minZ, box.z() - box.size() / 2);
            maxZ = Math.max(maxZ, box.z() + box.size() / 2);
        }
        assertEquals(-2, minX, 1e-9);
        assertEquals(2, maxX, 1e-9);
        assertEquals(-5, minZ, 1e-9);
        assertEquals(5.6875, maxZ, 1e-9);
    }

    @Test
    void aNarrowDeckUsesBoxesNoWiderThanItself() {
        Deck plank = new Deck(-0.5, 0.5, -4, 4, 2, Deck.MAX_BOX);
        assertEquals(1, plank.boxSize(), 1e-9);
        assertEquals(8, plank.boxes(new Deck.Frame(0, 0, 0, 0, 0)).size());
    }

    @Test
    void theDeckRunsAlongTheWayTheCarFaces() {
        // Facing +x (yaw -90): the front of the car is towards +x.
        Deck.Frame east = new Deck.Frame(10, 64, 20, -90, 0);
        double[] front = east.toWorld(0, 5, 4);
        assertEquals(14, front[0], 1e-9);
        assertEquals(69, front[1], 1e-9);
        assertEquals(20, front[2], 1e-9);
    }

    @ParameterizedTest
    @ValueSource(floats = {0, 37, -90, 135, 180})
    void aPointOnTheCarComesBackToItself(float yaw) {
        Deck.Frame frame = new Deck.Frame(3, 70, -8, yaw, 6);
        double[] world = frame.toWorld(1.25, 5, -3.5);
        double[] local = frame.toLocal(world[0], world[1], world[2]);
        assertEquals(1.25, local[0], 1e-9);
        assertEquals(5, local[1], 1e-9);
        assertEquals(-3.5, local[2], 1e-9);
    }

    @Test
    void aPlayerStandingOnTheRoofIsOnTheDeck() {
        Deck.Frame frame = new Deck.Frame(0, 64, 0, 0, 0);
        assertTrue(ROOF.carries(frame, 1, 69, 3));
        // Feet over the edge, still standing on it.
        assertTrue(ROOF.carries(frame, 2.2, 69, 3));
        // Jumping from the roof.
        assertTrue(ROOF.carries(frame, 0, 70.2, 0));
    }

    @Test
    void aPlayerBesideOrUnderTheCarIsNotOnTheDeck() {
        Deck.Frame frame = new Deck.Frame(0, 64, 0, 0, 0);
        assertFalse(ROOF.carries(frame, 3, 69, 0));
        assertFalse(ROOF.carries(frame, 0, 69, 6.5));
        // On the ground by the track, or on a bridge well above.
        assertFalse(ROOF.carries(frame, 0, 64, 0));
        assertFalse(ROOF.carries(frame, 0, 72, 0));
    }

    @Test
    void goingStraightThePlayerMovesAsFarAsTheCar() {
        Deck.Frame from = new Deck.Frame(0, 64, 0, 0, 0);
        Deck.Frame to = new Deck.Frame(0, 64, 0.72, 0, 0);
        double[] next = Deck.carry(from, to, 1, 69, 3);
        assertEquals(1, next[0], 1e-9);
        assertEquals(69, next[1], 1e-9);
        assertEquals(3.72, next[2], 1e-9);
        assertEquals(0, Deck.turn(from, to), 1e-6);
    }

    @Test
    void roundABendThePlayerTurnsWithTheCar() {
        Deck.Frame from = new Deck.Frame(0, 64, 0, 0, 0);
        Deck.Frame to = new Deck.Frame(0, 64, 0, -90, 0);
        // At the front of the car, facing +z; once the car faces +x, it is at +x.
        double[] next = Deck.carry(from, to, 0, 69, 4);
        assertEquals(4, next[0], 1e-9);
        assertEquals(69, next[1], 1e-9);
        assertEquals(0, next[2], 1e-9);
        assertEquals(-90, Deck.turn(from, to), 1e-6);
    }

    @Test
    void turningAcrossSouthIsAShortTurn() {
        Deck.Frame from = new Deck.Frame(0, 64, 0, 179, 0);
        Deck.Frame to = new Deck.Frame(0, 64, 0, -179, 0);
        assertEquals(2, Deck.turn(from, to), 1e-4);
    }

    @Test
    void onAGradeThePlayerRisesWithTheCar() {
        // Pitched up 10 degrees: the front is higher than the back.
        Deck.Frame from = new Deck.Frame(0, 64, 0, 0, -10);
        Deck.Frame to = new Deck.Frame(0, 64 + 0.72 * Math.sin(Math.toRadians(10)), 0.72, 0, -10);
        double[] start = from.toWorld(0, 5, 4);
        assertTrue(start[1] > 69);
        assertTrue(ROOF.carries(from, start[0], start[1], start[2]));
        double[] next = Deck.carry(from, to, start[0], start[1], start[2]);
        assertTrue(next[1] > start[1]);
    }

    @Test
    void readsTheWalkableSection() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("walkable.x", List.of(-2.0, 2.0));
        config.set("walkable.z", List.of(-5.0, 5.6875));
        config.set("walkable.top", 5.0);
        Deck deck = Deck.fromConfig(config.getConfigurationSection("walkable"));
        assertEquals(5, deck.top(), 1e-9);
        assertEquals(3, deck.boxSize(), 1e-9);
    }

    @Test
    void anIncompleteWalkableSectionIsIgnored() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("walkable.x", List.of(-2.0, 2.0));
        config.set("walkable.top", 5.0);
        assertNull(Deck.fromConfig(config.getConfigurationSection("walkable")));
        config.set("walkable.z", List.of(1.0, 1.1));
        assertNull(Deck.fromConfig(config.getConfigurationSection("walkable")));
    }
}
