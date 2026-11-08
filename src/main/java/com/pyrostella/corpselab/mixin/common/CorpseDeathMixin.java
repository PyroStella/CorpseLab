package com.pyrostella.corpselab.mixin.common;

import com.pyrostella.corpselab.internal.corpse.PlanCorpseHandler;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 等所有死亡监听处理完并确认死亡，再创建尸体；此处仍早于物品掉落 */
@Mixin(LivingEntity.class)
public abstract class CorpseDeathMixin {
    // 同时匹配开发和正式运行时字段，整个组只允许命中一次
    @Group(name="confirmedDeath",min=1,max=1)
    @Inject(method={"die","m_6667_"},at=@At(value="FIELD",target="Lnet/minecraft/world/entity/LivingEntity;dead:Z",opcode=Opcodes.PUTFIELD),remap=false,require=0)
    private void corpselab$development(DamageSource damage,CallbackInfo ci) { corpselab$create(damage); }

    @Group(name="confirmedDeath",min=1,max=1)
    @Inject(method={"die","m_6667_"},at=@At(value="FIELD",target="Lnet/minecraft/world/entity/LivingEntity;f_20890_:Z",opcode=Opcodes.PUTFIELD),remap=false,require=0)
    private void corpselab$runtime(DamageSource damage,CallbackInfo ci) { corpselab$create(damage); }

    private void corpselab$create(DamageSource damage) {
        LivingEntity source=(LivingEntity)(Object)this;
        if (source.level.isClientSide) return;
        PlanCorpseHandler.onDeath(source);
    }
}
