package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

class ContainerSnbtTest {

	// Trimmed from the Arcane Fuel stack that Main failed to load on 2026-10-05.
	private static final String SNBT = "{DataVersion:4554,components:{\"minecraft:attribute_modifiers\":"
			+ "[{amount:0.0d,id:\"mmoitems:decoy\",operation:\"add_value\",type:\"minecraft:attack_speed\"}],"
			+ "\"minecraft:custom_data\":{HSTRY_NAME:'{\"Stat\":\"NAME\"}',MMOITEMS_CUSTOM_MODEL_DATA:4210,"
			+ "MMOITEMS_DISABLE_CRAFTING:1b},\"minecraft:custom_model_data\":{floats:[4210.0f]},"
			+ "\"minecraft:custom_name\":{extra:[{bold:0b,color:\"#C4B99F\",text:\"Arcane Fuel\"}],text:\"\"},"
			+ "\"minecraft:profile\":{id:[I;1,-2,3,4]}},count:52,id:\"minecraft:iron_nugget\"}";

	@Test
	void stringEntry_isUsedAsSnbtAsIs() {
		assertEquals(List.of(SNBT), Container.snbtCandidates(new JsonPrimitive(SNBT)));
	}

	@Test
	void legacyEntry_triesRestoredTypesThenSavedForm() {
		JsonElement legacy = JsonParser.parseString(SNBT);

		List<String> candidates = Container.snbtCandidates(legacy);

		assertEquals(2, candidates.size());
		assertEquals(Container.legacySnbt(legacy), candidates.get(0));
		assertEquals(legacy.toString(), candidates.get(1));
	}

	@Test
	void nullEntry_hasNoCandidates() {
		assertEquals(List.of(), Container.snbtCandidates(JsonNull.INSTANCE));
		assertEquals(List.of(), Container.snbtCandidates(null));
	}

	@Test
	void legacyParse_hadTurnedTypedNumbersIntoStrings() {
		String saved = JsonParser.parseString(SNBT).toString();

		assertTrue(saved.contains("\"amount\":\"0.0d\""));
		assertTrue(saved.contains("\"bold\":\"0b\""));
		assertTrue(saved.contains("[\"I\",1,-2,3,4]"));
	}

	@Test
	void legacySnbt_restoresTypedNumbersAndArrays() {
		String repaired = Container.legacySnbt(JsonParser.parseString(SNBT));

		assertTrue(repaired.contains("\"amount\":0.0d"));
		assertTrue(repaired.contains("\"bold\":0b"));
		assertTrue(repaired.contains("\"MMOITEMS_DISABLE_CRAFTING\":1b"));
		assertTrue(repaired.contains("\"floats\":[4210.0f]"));
		assertTrue(repaired.contains("\"id\":[I;1,-2,3,4]"));
		assertTrue(repaired.contains("\"count\":52"));
		assertTrue(repaired.contains("\"MMOITEMS_CUSTOM_MODEL_DATA\":4210"));
	}

	@Test
	void legacySnbt_keepsTextQuotedAndEscaped() {
		String repaired = Container.legacySnbt(JsonParser.parseString(SNBT));

		assertTrue(repaired.contains("\"text\":\"Arcane Fuel\""));
		assertTrue(repaired.contains("\"HSTRY_NAME\":\"{\\\"Stat\\\":\\\"NAME\\\"}\""));
		assertTrue(repaired.contains("\"id\":\"minecraft:iron_nugget\""));
		assertTrue(repaired.contains("\"text\":\"\""));
	}

	@Test
	void legacySnbt_typedArraysOfTypedValues() {
		String repaired = Container.legacySnbt(JsonParser.parseString("{b:[B;1b,0b],l:[L;5L,-6L]}"));

		assertEquals("{\"b\":[B;1b,0b],\"l\":[L;5L,-6L]}", repaired);
	}

	@Test
	void legacySnbt_plainListsStayLists() {
		assertEquals("[\"I\"]", Container.legacySnbt(JsonParser.parseString("[\"I\"]")));
		assertEquals("[\"I\",\"me\"]", Container.legacySnbt(JsonParser.parseString("[\"I\",\"me\"]")));
		assertEquals("[\"X\",1]", Container.legacySnbt(JsonParser.parseString("[\"X\",1]")));
		assertEquals("[[1],2]", Container.legacySnbt(JsonParser.parseString("[[1],2]")));
		assertEquals("[\"I\",[1]]", Container.legacySnbt(JsonParser.parseString("[\"I\",[1]]")));
	}

	@Test
	void legacySnbt_skipsNullsAndKeepsBooleans() {
		assertEquals("{\"a\":[1,2],\"c\":true}", Container.legacySnbt(JsonParser.parseString("{a:[1,null,2],b:null,c:true}")));
	}

	@Test
	void legacySnbt_numberLikeTextWithoutSuffixStaysQuoted() {
		assertEquals("{\"t\":\"12\",\"u\":\"1.5x\"}", Container.legacySnbt(JsonParser.parseString("{t:\"12\",u:\"1.5x\"}")));
	}

	@Test
	void legacySnbt_noLongerMatchesSavedForm() {
		JsonElement legacy = JsonParser.parseString(SNBT);

		assertFalse(Container.legacySnbt(legacy).contains("\"0.0d\""));
	}
}
