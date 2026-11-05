package com.pyrostella.corpselab.physics;

/** 位置层面的约束，在每个物理子步里求解 */
public interface Constraint {

    void solvePosition(double h);

    /**
     * 同一个子步里是否要反复求解这条约束
     *
     * <p>关节、限位、地面接触都需要：它们互相矛盾，只投一遍会留下残余位移，而残余位移
     * 会被 {@link RigidBody#deriveVelocity} 折算成速度，表现为持续的抖动和漂移。</p>
     *
     * <p>纯粹的排斥类约束（部件自碰撞）相反：每遍都往外推、关节每遍又拉回来，来回拉锯
     * 才是抖动的来源，所以它们返回 false，只在第一遍里投一次。</p>
     */
    default boolean iterated() {
        return true;
    }
}
