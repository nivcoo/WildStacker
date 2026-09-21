package com.bgsoftware.wildstacker.handlers;

import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.SpawnCause;
import com.bgsoftware.wildstacker.api.objects.StackedItem;
import com.bgsoftware.wildstacker.nms.NMSEntities;
import com.bgsoftware.wildstacker.utils.data.structures.FastEnumMap;
import com.bgsoftware.wildstacker.utils.items.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class SystemHandlerItemDropsTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{
                {"disabled 200", Material.STONE, 200, false, true, 4096, 4, 8},
                {"disabled 4097", Material.STONE, 4097, false, true, 4096, 65, 1},
                {"disabled 16 max", Material.ENDER_PEARL, 35, false, true, 4096, 3, 3},
                {"disabled 1 max", Material.DIAMOND_SWORD, 3, false, true, 4096, 3, 1},
                {"disabled zero", Material.STONE, 0, false, true, 4096, 0, 0},
                {"disabled exact stack", Material.STONE, 64, false, true, 4096, 1, 64},
                {"disabled configured limit ignored", Material.STONE, 200, false, true, 1, 4, 8},
                {"enabled 200", Material.STONE, 200, true, true, 4096, 1, 200},
                {"enabled 4097", Material.STONE, 4097, true, true, 4096, 2, 1},
                {"enabled unlimited", Material.STONE, 4097, true, true, 0, 1, 4097},
                {"enabled small limit", Material.STONE, 70, true, true, 32, 3, 6},
                {"enabled excluded item or world", Material.STONE, 200, true, false, 4096, 4, 8}
        });
    }

    @Parameterized.Parameter
    public String scenario;
    @Parameterized.Parameter(1)
    public Material material;
    @Parameterized.Parameter(2)
    public int amount;
    @Parameterized.Parameter(3)
    public boolean stackingEnabled;
    @Parameterized.Parameter(4)
    public boolean eligible;
    @Parameterized.Parameter(5)
    public int configuredLimit;
    @Parameterized.Parameter(6)
    public int expectedDrops;
    @Parameterized.Parameter(7)
    public int expectedLastAmount;

    @Test
    public void preservesEveryRequestedItemWithoutMutatingTemplate() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.8.8-R0.1-SNAPSHOT");
            try (MockedStatic<ItemUtils> itemUtils = mockStatic(ItemUtils.class)) {
                itemUtils.when(() -> ItemUtils.canBeStacked(any(ItemStack.class), any(World.class)))
                        .thenReturn(eligible);

                SettingsHandler settings = mock(SettingsHandler.class);
                setField(settings, "itemsStackingEnabled", stackingEnabled);
                FastEnumMap<Material, Integer> limits = new FastEnumMap<>(Material.class);
                limits.put(material, configuredLimit);
                setField(settings, "itemsLimits", limits);

                WildStackerPlugin plugin = mock(WildStackerPlugin.class);
                NMSEntities entities = mock(NMSEntities.class);
                when(plugin.getSettings()).thenReturn(settings);
                when(plugin.getNMSEntities()).thenReturn(entities);
                SystemHandler handler = mock(SystemHandler.class, CALLS_REAL_METHODS);
                setField(handler, "plugin", plugin);

                Location location = new Location(mock(World.class), 10, 64, 20);
                ItemStack template = new ItemStack(material, 7);
                ItemMeta metadata = mock(ItemMeta.class);
                when(metadata.clone()).thenReturn(metadata);
                when(metadata.getDisplayName()).thenReturn("Harvest reward");
                setField(template, "meta", metadata);
                List<StackedItem> drops = new ArrayList<>();
                List<Integer> quantities = new ArrayList<>();

                when(entities.createItem(eq(location), any(ItemStack.class), eq(SpawnCause.CUSTOM), any()))
                        .thenAnswer(invocation -> {
                            ItemStack physicalStack = invocation.getArgument(1);
                            assertNotSame(template, physicalStack);
                            assertEquals(material, physicalStack.getType());
                            assertEquals("Harvest reward", physicalStack.getItemMeta().getDisplayName());
                            assertTrue(physicalStack.getAmount() > 0);
                            assertTrue(physicalStack.getAmount() <= physicalStack.getMaxStackSize());
                            AtomicInteger quantity = new AtomicInteger(physicalStack.getAmount());
                            StackedItem droppedItem = mock(StackedItem.class);
                            when(droppedItem.isCached()).thenReturn(stackingEnabled && eligible);
                            doAnswer(update -> {
                                quantity.set(update.getArgument(0));
                                return null;
                            }).when(droppedItem).setStackAmount(anyInt(), anyBoolean());
                            Consumer<StackedItem> callback = invocation.getArgument(3);
                            callback.accept(droppedItem);
                            if (!stackingEnabled)
                                verify(droppedItem, never()).setStackAmount(anyInt(), anyBoolean());
                            drops.add(droppedItem);
                            quantities.add(quantity.get());
                            return droppedItem;
                        });

                StackedItem result = handler.spawnItemWithAmount(location, template, amount);

                assertEquals("All requested items must remain recoverable", amount,
                        quantities.stream().mapToInt(Integer::intValue).sum());
                assertEquals(expectedDrops, drops.size());
                if (drops.isEmpty()) {
                    assertNull(result);
                } else {
                    assertSame(drops.get(drops.size() - 1), result);
                    assertEquals(expectedLastAmount, (int) quantities.get(quantities.size() - 1));
                }
                assertEquals(7, template.getAmount());
                assertEquals(material, template.getType());
                assertEquals("Harvest reward", template.getItemMeta().getDisplayName());
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
