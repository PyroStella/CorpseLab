package com.pyrostella.corpselab.compat.geckolib;

import com.pyrostella.corpselab.api.client.VanillaRevivalPoses;

import com.eliotlash.mclib.math.Constant;
import com.pyrostella.corpselab.api.RevivalPose;
import com.pyrostella.corpselab.compat.geckolib.GeckoModelScan;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.*;
import software.bernie.geckolib.core.state.BoneSnapshot;
import java.util.*;

/** 仅安装 GeckoLib 的客户端使用；读取已烘焙动画，不运行控制器或修改共享骨骼 */
public final class GeckoRevivalPoses {
    private GeckoRevivalPoses() { }
    public static RevivalPose firstFrame(BakedGeoModel model, Animation animation) {
        var tracks = new HashMap<String,BoneAnimation>();
        for (var track : animation.boneAnimations()) tracks.put(track.boneName(),track);
        Map<String,Matrix4f> animated = new HashMap<>();
        for (var root : model.topLevelBones()) collect(root,root.getName(),new Matrix4f(),tracks,animated);
        var rest = GeckoModelScan.scan(model,new Matrix4f());
        RevivalPose pose = RevivalPose.empty();
        for (var box : rest.plan().boxes()) {
            var reference = rest.parts().get(box.index());
            Matrix4f delta = new Matrix4f(animated.get(box.path())).mul(new Matrix4f(reference.transform()).invert());
            Vector3f centre = delta.transformPosition(new Vector3f((float)-box.cx(),(float)(1.501-box.cy()),(float)box.cz()));
            Quaternionf q = delta.getUnnormalizedRotation(new Quaternionf()).normalize();
            pose = pose.bone(box.path(),new Vec3(centre.x,centre.y,centre.z),new Quaterniond(q),VanillaRevivalPoses.uniformScale(delta));
        }
        return pose;
    }
    private static void collect(GeoBone bone,String path,Matrix4f parent,Map<String,BoneAnimation> tracks,Map<String,Matrix4f> matrices) {
        BoneSnapshot initial = bone.getInitialSnapshot();
        if (initial == null) initial = new BoneSnapshot(bone);
        float x=initial.getOffsetX(),y=initial.getOffsetY(),z=initial.getOffsetZ();
        float rx=initial.getRotX(),ry=initial.getRotY(),rz=initial.getRotZ();
        float sx=initial.getScaleX(),sy=initial.getScaleY(),sz=initial.getScaleZ();
        var track = tracks.get(bone.getName());
        if (track != null) {
            var pos=track.positionKeyFrames(); var rot=track.rotationKeyFrames(); var scale=track.scaleKeyFrames();
            x=first(pos.xKeyframes(),x,false,0); y=first(pos.yKeyframes(),y,false,1); z=first(pos.zKeyframes(),z,false,2);
            rx+=first(rot.xKeyframes(),0,true,0); ry+=first(rot.yKeyframes(),0,true,1); rz+=first(rot.zKeyframes(),0,true,2);
            sx=first(scale.xKeyframes(),sx,false,0); sy=first(scale.yKeyframes(),sy,false,1); sz=first(scale.zKeyframes(),sz,false,2);
        }
        float px=bone.getPivotX()/16,py=bone.getPivotY()/16,pz=bone.getPivotZ()/16;
        Matrix4f matrix=new Matrix4f(parent).translate(-x/16,y/16,z/16).translate(px,py,pz)
                .rotateZ(rz).rotateY(ry).rotateX(rx).scale(sx,sy,sz).translate(-px,-py,-pz);
        matrices.put(path,matrix);
        for (var child : bone.getChildBones()) collect(child,path+"."+child.getName(),matrix,tracks,matrices);
    }
    private static float first(List<? extends Keyframe<?>> frames,float fallback,boolean rotation,int axis) {
        if (frames.isEmpty()) return fallback;
        var frame=frames.get(frames.size()-1);
        for (var candidate : frames) if (candidate.length()>0) { frame=candidate; break; }
        var value=frame.length()==0 ? frame.endValue() : frame.startValue();
        double result=value.get();
        // GeckoLib 已将常量角度转换为弧度，表达式角度在求值后才转换
        if (rotation && !(value instanceof Constant)) result=Math.toRadians(result)*(axis<2 ? -1 : 1);
        return (float)result;
    }
}
