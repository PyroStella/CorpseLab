package com.pyrostella.corpselab.mixin.common;

import com.pyrostella.corpselab.internal.corpse.CorpseHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ServerPlayer 独立覆写死亡流程，在取消检查完成且物品尚未掉落时提交尸体 */
@Mixin(ServerPlayer.class)
public abstract class PlayerCorpseDeathMixin {
    @Group(name="playerDeathCommit",min=1,max=1)
    @Inject(method={"die","m_6667_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot(Lnet/minecraft/world/damagesource/DamageSource;)V"),remap=false,require=0)
    private void corpselab$development(DamageSource source,CallbackInfo ci) { corpselab$create(source); }
    @Group(name="playerDeathCommit",min=1,max=1)
    @Inject(method={"die","m_6667_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_6668_(Lnet/minecraft/world/damagesource/DamageSource;)V"),remap=false,require=0)
    private void corpselab$runtime(DamageSource source,CallbackInfo ci) { corpselab$create(source); }
    private void corpselab$create(DamageSource source) {
        CorpseHandler.onDeath((ServerPlayer)(Object)this,source);
    }
}
