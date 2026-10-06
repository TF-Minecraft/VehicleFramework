package net.tfminecraft.vehicleframework.test;

import static org.mockito.Mockito.*;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

/** Supplies the server-owned sound and attribute registries used by unit fixtures. */
public final class RegistryFixture {
    private RegistryFixture() {}
    private static final Map<String,Registry<?>> registries = new HashMap<>();

    public static void initialize() {
        try (var accessScope = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, call -> {
                if (call.getMethod().getName().equals("getRegistry")) {
                    Object key = call.getArgument(0);
                    return registry(key instanceof RegistryKey<?> named ? named.key().value()
                            : key == Attribute.class ? "attribute" : key == Sound.class ? "sound_event"
                            : key == PotionEffectType.class ? "mob_effect"
                            : ((Class<?>) key).getName());
                }
                return RETURNS_DEFAULTS.answer(call);
            });
            accessScope.when(RegistryAccess::registryAccess).thenReturn(access);
            java.util.Objects.requireNonNull(Sound.BLOCK_NOTE_BLOCK_BIT);
            java.util.Objects.requireNonNull(Attribute.MOVEMENT_SPEED);
            java.util.Objects.requireNonNull(PotionEffectType.SPEED);
            java.util.Objects.requireNonNull(org.bukkit.block.BlockType.AIR);
        }
    }

    private static Registry<?> registry(String id) {
        Registry<?> existing = registries.get(id);
        if (existing != null) return existing;
        Map<NamespacedKey,Object> values = new HashMap<>();
        Class<?> type = id.equals("attribute") ? Attribute.class : Sound.class;
        Registry<?> created = mock(Registry.class, call -> {
            if (id.equals("block") && (call.getMethod().getName().equals("get")
                    || call.getMethod().getName().equals("getOrThrow"))) {
                NamespacedKey key = namespaced(call.getArgument(0));
                return values.computeIfAbsent(key, ignored -> Proxy.newProxyInstance(
                        org.bukkit.block.BlockType.class.getClassLoader(), new Class<?>[]{org.bukkit.block.BlockType.Typed.class},
                        (self, method, args) -> switch (method.getName()) {
                            case "getKey", "key" -> key;
                            case "typed" -> self;
                            case "isAir" -> java.util.Set.of("air", "cave_air", "void_air").contains(key.getKey());
                            case "isSolid" -> !java.util.Set.of("air", "cave_air", "void_air", "water", "lava").contains(key.getKey());
                            case "getMaterial" -> org.bukkit.Material.valueOf(key.getKey().toUpperCase(java.util.Locale.ROOT));
                            case "getBlastResistance" -> switch (key.getKey()) {
                                case "stone" -> 6f;
                                case "obsidian" -> 1200f;
                                default -> 0f;
                            };
                            case "equals" -> self == args[0];
                            case "hashCode" -> key.hashCode();
                            case "toString" -> key.toString();
                            default -> throw new UnsupportedOperationException(method.getName());
                        }));
            }
            if (call.getMethod().getName().equals("get")) return values.get(namespaced(call.getArgument(0)));
            if (!call.getMethod().getName().equals("getOrThrow")) return RETURNS_DEFAULTS.answer(call);
            NamespacedKey key = namespaced(call.getArgument(0));
            if (id.equals("mob_effect")) {
                return values.computeIfAbsent(key, ignored -> mock(PotionEffectType.class, effect -> switch(effect.getMethod().getName()) {
                    case "getKey", "key" -> key;
                    case "getName" -> key.getKey().toUpperCase(java.util.Locale.ROOT);
                    case "getColor" -> org.bukkit.Color.BLUE;
                    case "getDurationModifier" -> 1.0;
                    default -> RETURNS_DEFAULTS.answer(effect);
                }));
            }
            return values.computeIfAbsent(key, ignored -> Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
                    (self,method,args) -> switch (method.getName()) {
                        case "equals" -> self == args[0];
                        case "hashCode" -> key.hashCode();
                        case "getKey", "key" -> key;
                        case "toString" -> key.toString();
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
        });
        registries.put(id,created);
        return created;
    }
    private static NamespacedKey namespaced(Object value) {
        if (value instanceof NamespacedKey key) return key;
        net.kyori.adventure.key.Key key = (net.kyori.adventure.key.Key) value;
        return new NamespacedKey(key.namespace(), key.value());
    }

}
