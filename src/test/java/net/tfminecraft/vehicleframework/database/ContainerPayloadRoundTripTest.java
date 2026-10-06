package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.TreeMap;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class ContainerPayloadRoundTripTest {

	private static final String SNBT = "{components:{\"minecraft:custom_name\":{extra:[{bold:0b,text:\"<#c4b99f>Arcane 'Fuel'\"}],"
			+ "text:\"\"},\"minecraft:attribute_modifiers\":[{amount:0.0d}]},count:52,id:\"minecraft:iron_nugget\"}";

	@Test
	void snbtStringEntry_survivesSaveAndLoadUnchanged() {
		JsonArray entry = new JsonArray();
		entry.add(3);
		entry.add(SNBT);
		JsonArray items = new JsonArray();
		items.add(entry);
		JsonObject container = new JsonObject();
		container.addProperty("id", "cart");
		container.add("items", items);

		// Same path as ActiveVehicleSnapshotFactory.saveContainers and the payload write.
		JSONObject vehicle = new JSONObject();
		JSONObject section = new JSONObject();
		section.put("cart", JSONValue.parse(container.toString()));
		vehicle.put("containers", section);
		TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		sorted.putAll(vehicle);
		String payload = new GsonBuilder().setPrettyPrinting().create().toJson(sorted);

		List<JsonObject> loaded = VehiclePayloadCodec.loadContainers((JSONObject) JSONValue.parse(payload));

		assertEquals(1, loaded.size());
		JsonArray loadedEntry = loaded.get(0).getAsJsonArray("items").get(0).getAsJsonArray();
		assertEquals(3, loadedEntry.get(0).getAsInt());
		assertEquals(SNBT, loadedEntry.get(1).getAsString());
	}
}
