package com.pyrostella.corpselab.internal.registry;

import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 通用方块注册。
 * <p>
 * 素材库（{@code 设计文件/通用素材库/界面图标/方块}）里的方块贴图全部按普通方块注册，
 * 用最朴素的 {@link Block}，没有 BlockEntity、没有特殊行为。
 * <p>
 * 模型是整格立方体（0,0,0 → 16,16,16），所以保持默认的遮挡属性，正常挡光、正常剔除相邻面。
 * 如果以后模型改成不足一格的小方块，这里要补 {@code .noOcclusion()}，否则相邻方块的接触面会被剔除。
 */
public class CorpseLabBlock {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CorpseLab.MOD_ID);

    public static final RegistryObject<Block> MONSTER_SKULL = registerSkullBlock("monster_skull");
    public static final RegistryObject<Block> PLAYER_SKULL = registerSkullBlock("player_skull");

    /**
     * 注册一个头骨方块：硬度与音效按原版头骨来，同时注册对应的 BlockItem。
     */
    private static RegistryObject<Block> registerSkullBlock(String id) {
        RegistryObject<Block> block = BLOCKS.register(id, () -> new Block(
                BlockBehaviour.Properties.of()
                        .strength(1.0F)
                        .sound(SoundType.BONE_BLOCK)));
        CorpseLabItem.registerBlockItem(id, block);
        return block;
    }
}
